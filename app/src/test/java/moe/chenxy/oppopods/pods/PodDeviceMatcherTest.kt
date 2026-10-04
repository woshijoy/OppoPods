package moe.chenxy.oppopods.pods

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PodDeviceMatcherTest {
    @Test
    fun `oneplus earbuds are recognized`() {
        assertTrue(PodDeviceMatcher.matchesName("OnePlus Buds 4"))
        assertTrue(PodDeviceMatcher.matchesName("OnePlus Buds Pro 3"))
        assertTrue(PodDeviceMatcher.matchesName("oneplus nord buds 3"))
        assertTrue(PodDeviceMatcher.matchesName("OnePlus Bullets Wireless Z3"))
        assertTrue(PodDeviceMatcher.matchesName("一加Buds 4"))
    }

    @Test
    fun `oppo enco and realme earbuds are recognized`() {
        assertTrue(PodDeviceMatcher.matchesName("OPPO Enco X3"))
        assertTrue(PodDeviceMatcher.matchesName("Enco Air5 Pro"))
        assertTrue(PodDeviceMatcher.matchesName("realme Buds Air7"))
    }

    @Test
    fun `other brands are not recognized`() {
        assertFalse(PodDeviceMatcher.matchesName("Xiaomi Buds 5 Pro"))
        assertFalse(PodDeviceMatcher.matchesName("Galaxy Buds3 Pro"))
        assertFalse(PodDeviceMatcher.matchesName("FreeBuds Pro 4"))
        assertFalse(PodDeviceMatcher.matchesName("Redmi Buds 6 Pro"))
    }

    @Test
    fun `blank names are not recognized`() {
        assertFalse(PodDeviceMatcher.matchesName(null))
        assertFalse(PodDeviceMatcher.matchesName(""))
        assertFalse(PodDeviceMatcher.matchesName("   "))
    }
}
