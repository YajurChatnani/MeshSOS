package com.example.meshsos

import com.example.meshsos.transport.wifi.WifiAwareIdentity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class WifiAwareIdentityTest {

    @Test
    fun testIdentitySerializationAndDeserialization() {
        val original = WifiAwareIdentity(
            version = 1,
            deviceId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890",
            deviceName = "Samsung Galaxy A35"
        )

        val bytes = original.toByteArray()

        // Wire size must be strictly compact (< 100 bytes) for Wi-Fi Aware beacons
        assertTrue("Identity payload size must be <= 100 bytes, was ${bytes.size}", bytes.size <= 100)

        val parsed = WifiAwareIdentity.fromByteArray(bytes)
        assertNotNull(parsed)
        assertEquals(original.version, parsed!!.version)
        assertEquals(original.deviceId, parsed.deviceId)
        assertEquals(original.deviceName, parsed.deviceName)
    }

    @Test
    fun testLongDeviceNameTruncation() {
        val longName = "A Very Extremely Long Device Name That Exceeds Normal Limits By Far"
        val original = WifiAwareIdentity(
            version = 1,
            deviceId = "test-device-uuid-1234",
            deviceName = longName
        )

        val bytes = original.toByteArray()
        val parsed = WifiAwareIdentity.fromByteArray(bytes)

        assertNotNull(parsed)
        assertEquals(32, parsed!!.deviceName.length)
        assertEquals(longName.take(32), parsed.deviceName)
        assertTrue("Wire size must be compact", bytes.size <= 100)
    }

    @Test
    fun testJsonFallbackParsing() {
        val jsonPayload = """{"v":1,"id":"json-uuid-999","name":"Realme 9 Pro+"}"""
            .toByteArray(StandardCharsets.UTF_8)

        val parsed = WifiAwareIdentity.fromByteArray(jsonPayload)
        assertNotNull(parsed)
        assertEquals("json-uuid-999", parsed!!.deviceId)
        assertEquals("Realme 9 Pro+", parsed.deviceName)
        assertEquals(1, parsed.version)
    }

    @Test
    fun testNullAndEmptyHandling() {
        assertNull(WifiAwareIdentity.fromByteArray(null))
        assertNull(WifiAwareIdentity.fromByteArray(ByteArray(0)))
        assertNull(WifiAwareIdentity.fromByteArray(byteArrayOf(0x00, 0x01)))
    }
}

