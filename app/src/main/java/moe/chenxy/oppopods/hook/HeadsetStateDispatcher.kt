package moe.chenxy.oppopods.hook

import android.annotation.SuppressLint
import android.app.StatusBarManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import moe.chenxy.oppopods.BuildConfig
import moe.chenxy.oppopods.pods.PodDeviceMatcher
import moe.chenxy.oppopods.pods.RfcommController
import moe.chenxy.oppopods.utils.SystemApisUtils.setIconVisibility
import moe.chenxy.oppopods.utils.miuiStrongToast.data.OppoPodsAction

object HeadsetStateDispatcher : HookContext() {
    private var appRequestReceiverRegistered = false
    private var appContext: Context? = null

    override fun onHook() {
        runCatching {
            hookAfter(findMethod("com.android.bluetooth.btservice.AdapterService", "onCreate")) {
                registerAppRequestReceiver(instance as? Context)
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
}
