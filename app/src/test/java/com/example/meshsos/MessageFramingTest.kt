package com.example.meshsos

import com.example.meshsos.protocol.FrameBufferDecoder
import com.example.meshsos.protocol.MessageFraming
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class MessageFramingTest {

    @Test
    fun testSingleFrameEncodingAndDecoding() {
        val payload = "Hello Offgrid Mesh SOS!".toByteArray(Charsets.UTF_8)
        val framedBytes = MessageFraming.encodeFrame(payload)

        // 4 bytes length header + payload
        assertEquals(4 + payload.size, framedBytes.size)

        val inputStream = ByteArrayInputStream(framedBytes)
        val decoded = MessageFraming.readFrame(inputStream)

        assertArrayEquals(payload, decoded)
    }

    @Test
    fun testPartialReadsWithDecoder() {
        val decoder = FrameBufferDecoder()
        val payload = "Test Emergency Partial Read Payload 1234567890".toByteArray(Charsets.UTF_8)
        val framed = MessageFraming.encodeFrame(payload)

        // Feed bytes in small 3-byte chunks
        val recovered = mutableListOf<ByteArray>()
        var offset = 0
        val chunkSize = 3

        while (offset < framed.size) {
            val length = minOf(chunkSize, framed.size - offset)
            val chunk = framed.copyOfRange(offset, offset + length)
            val frames = decoder.feed(chunk)
            recovered.addAll(frames)
            offset += length
        }

        assertEquals(1, recovered.size)
        assertArrayEquals(payload, recovered[0])
        assertEquals(0, decoder.getBufferedByteCount())
    }

    @Test
    fun testMultipleMessagesInSingleBuffer() {
        val decoder = FrameBufferDecoder()
        val msg1 = "Message Alpha".toByteArray(Charsets.UTF_8)
        val msg2 = "Message Beta".toByteArray(Charsets.UTF_8)
        val msg3 = "Message Gamma".toByteArray(Charsets.UTF_8)

        val buffer = ByteBuffer.allocate(
            (4 + msg1.size) + (4 + msg2.size) + (4 + msg3.size)
        )
        buffer.put(MessageFraming.encodeFrame(msg1))
        buffer.put(MessageFraming.encodeFrame(msg2))
        buffer.put(MessageFraming.encodeFrame(msg3))

        val allBytes = buffer.array()
        val decoded = decoder.feed(allBytes)

        assertEquals(3, decoded.size)
        assertArrayEquals(msg1, decoded[0])
        assertArrayEquals(msg2, decoded[1])
        assertArrayEquals(msg3, decoded[2])
    }

    @Test
    fun testOversizedFrameRejection() {
        val decoder = FrameBufferDecoder(maxFrameSize = 16384)

        // Create an invalid header claiming 20,000 bytes
        val badHeader = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(20000).array()

        try {
            decoder.feed(badHeader)
            fail("Expected IllegalArgumentException for oversized frame")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("exceeds max size"))
        }
    }

    @Test
    fun testInvalidZeroLengthFrameRejection() {
        val decoder = FrameBufferDecoder()
        val badHeader = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(0).array()

        try {
            decoder.feed(badHeader)
            fail("Expected IllegalArgumentException for 0 length frame")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Invalid frame length"))
        }
    }

    @Test
    fun testCleanEofHandling() {
        val emptyStream = ByteArrayInputStream(ByteArray(0))
        val result = MessageFraming.readFrame(emptyStream)
        assertNull(result)
    }
}
