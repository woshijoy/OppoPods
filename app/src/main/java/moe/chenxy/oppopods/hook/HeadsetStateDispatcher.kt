package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.pods.PodDeviceMatcher
import moe.chenxy.oppopods.pods.RfcommController
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false
    private var appContext: Context? = null
    private var aclReceiverRegistered = false
    private var lastFallbackConnectMs = 0L
    private var fallbackConnectInFlight = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private const val FALLBACK_CONNECT_COOLDOWN_MS = 20_000L
    private const val ACL_FALLBACK_DELAY_MS = 2_500L

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
                registerAclAutoConnectReceiver(instance as? Context)
            }
        }.onFailure {
            Log.w("OppoPods", "AdapterService.onCreate hook skipped", it)
        }

        hookAfter(findMethodByParamCount("com.android.bluetooth.a2dp.A2dpService", "handleConnectionStateChanged", 3)) {
            val currState = args[2] as Int
            val fromState = args[1] as Int
            val device = args[0] as BluetoothDevice?
            val handler = getObjectField(instance, "mHandler") as Handler
            if (device == null || currState == fromState) {
                return@hookAfter
            }
            handler.post {
                val context = instance as ContextWrapper
                registerAppRequestReceiver(context)
                Log.d("OppoPods", "A2DP Connection State: $currState, isOppoPod ${isOppoPod(context, device)}")
                if (!isOppoPod(context, device)) return@post

                val statusBarManager = context.getSystemService("statusbar") as StatusBarManager
                if (currState == BluetoothHeadset.STATE_CONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", true)
                    RfcommController.connectPod(context, device, prefs)
                } else if (currState == BluetoothHeadset.STATE_DISCONNECTING || currState == BluetoothHeadset.STATE_DISCONNECTED) {
                    statusBarManager.setIconVisibility("wireless_headset", false)
                    RfcommController.disconnectedPod(context, device)
                }
            }
        }
    }

    private fun registerAppRequestReceiver(context: Context?) {
        if (context == null || appRequestReceiverRegistered) return
        appContext = context
        context.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (context == null) return
                when (intent?.action) {
                    OppoPodsAction.ACTION_PODS_UI_INIT,
                    OppoPodsAction.ACTION_REFRESH_STATUS -> {
                        context.sendBroadcast(Intent(OppoPodsAction.ACTION_MODULE_BLUETOOTH_SERVICE_ALIVE).apply {
                            setPackage(BuildConfig.APPLICATION_ID)
                            addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
                        })
                        maybeFallbackAutoConnect(context)
                    }
                    OppoPodsAction.ACTION_CONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("OppoPods", "connect request from app device=${device.name}/${device.address}")
                        RfcommController.connectPod(context, device, prefs, appRequested = true)
                    }
                    OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST -> {
                        val device = intent.getParcelableExtra("device", BluetoothDevice::class.java) ?: return
                        Log.d("OppoPods", "disconnect request from app device=${device.name}/${device.address}")
                        RfcommController.disconnectedPod(context, device)
                    }
                }
            }
        }, IntentFilter().apply {
            addAction(OppoPodsAction.ACTION_PODS_UI_INIT)
            addAction(OppoPodsAction.ACTION_REFRESH_STATUS)
            addAction(OppoPodsAction.ACTION_CONNECT_POD_REQUEST)
            addAction(OppoPodsAction.ACTION_DISCONNECT_POD_REQUEST)
        }, Context.RECEIVER_EXPORTED)
        appRequestReceiverRegistered = true
    }

    /**
     * Detect BBK-family earbuds (OPPO / OnePlus / realme, Enco models) via [PodDeviceMatcher],
     * falling back to the context captured when the receiver was registered.
     */
    @SuppressLint("MissingPermission")
    fun isOppoPod(context: Context?, device: BluetoothDevice): Boolean {
        val name = runCatching { device.name ?: device.alias }.getOrNull() ?: return false
        return PodDeviceMatcher.matches(context ?: appContext, name)
    }

    /**
     * Failsafe trigger for buds whose A2DP hook never fires (e.g. LE-Audio-only connections):
     * listen for any ACL connect of a matching device and retry the pod session shortly after.
     */
    private fun registerAclAutoConnectReceiver(context: Context?) {
        if (context == null || aclReceiverRegistered) return
        runCatching {
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(receiverContext: Context?, intent: Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_ACL_CONNECTED) return
                    val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
                    val name = runCatching { device.name ?: device.alias }.getOrNull() ?: return
                    if (!PodDeviceMatcher.matches(receiverContext ?: context, name)) return
                    Log.i("OppoPods", "ACL connected ${device.address}, checking pod session")
                    mainHandler.postDelayed({ maybeFallbackAutoConnect(context) }, ACL_FALLBACK_DELAY_MS)
                }
            }, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED), Context.RECEIVER_NOT_EXPORTED)
            aclReceiverRegistered = true
        }.onFailure { Log.w("OppoPods", "ACL auto-connect receiver skipped", it) }
    }

    /**
     * Failsafe auto-connect: when the app refreshes its UI and no session exists yet, connect to
     * a bonded BBK-family device that is actually connected to the phone.
     */
    @SuppressLint("MissingPermission")
    private fun maybeFallbackAutoConnect(context: Context) {
        if (RfcommController.hasActiveSession() || fallbackConnectInFlight) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastFallbackConnectMs < FALLBACK_CONNECT_COOLDOWN_MS) return
        val adapter = runCatching {
            (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        }.getOrNull() ?: return
        if (adapter.isEnabled != true) return
        val device = runCatching {
            adapter.bondedDevices.orEmpty().firstOrNull { candidate ->
                val candidateName = runCatching { candidate.name ?: candidate.alias }.getOrNull()
                PodDeviceMatcher.matches(context, candidateName) && isConnectedToPhone(candidate)
            }
        }.getOrNull() ?: return
        lastFallbackConnectMs = now
        fallbackConnectInFlight = true
        try {
            Log.i("OppoPods", "fallback auto-connect device=${device.address}")
            RfcommController.connectPod(context, device, prefs, appRequested = true)
        } catch (t: Throwable) {
            Log.w("OppoPods", "fallback auto-connect failed", t)
        } finally {
            fallbackConnectInFlight = false
        }
    }

    /** [BluetoothDevice.isConnected] is hidden; reflection is available inside the Bluetooth process. */
    @SuppressLint("MissingPermission")
    private fun isConnectedToPhone(device: BluetoothDevice): Boolean =
        runCatching { callMethod(device, "isConnected") as? Boolean == true }.getOrDefault(false)
}
