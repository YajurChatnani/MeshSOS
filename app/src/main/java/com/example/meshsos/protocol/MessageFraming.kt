package com.example.meshsos.protocol

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Robust length-prefixed framing protocol for stream-based transports (TCP and RFCOMM).
 *
 * Wire format:
 * [4-byte big-endian Int: payloadLength] + [payloadLength bytes: UTF-8 payload]
 */
object MessageFraming {
    const val MAX_FRAME_SIZE = 16384 // 16 KiB
    const val HEADER_SIZE = 4 // 4 bytes for length prefix

    /**
     * Encodes a payload byte array into a framed byte array with a 4-byte length header.
     */
    fun encodeFrame(payload: ByteArray): ByteArray {
        require(payload.size <= MAX_FRAME_SIZE) {
            "Payload size ${payload.size} exceeds maximum allowed frame size $MAX_FRAME_SIZE"
        }
        val buffer = ByteBuffer.allocate(HEADER_SIZE + payload.size)
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(payload.size)
        buffer.put(payload)
        return buffer.array()
    }

    /**
     * Writes a framed message to the given OutputStream.
     */
    @Synchronized
    fun writeFrame(outputStream: OutputStream, payload: ByteArray) {
        val framed = encodeFrame(payload)
        outputStream.write(framed)
        outputStream.flush()
    }

    /**
     * Reads exactly [length] bytes from [inputStream] into [buffer] starting at [offset].
     * Throws EOFException if EOF is reached before all bytes are read.
     */
    @Throws(EOFException::class)
    fun readFully(inputStream: InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var bytesRead = 0
        while (bytesRead < length) {
            val count = inputStream.read(buffer, offset + bytesRead, length - bytesRead)
            if (count < 0) {
                throw EOFException("End of stream reached after reading $bytesRead of $length bytes")
            }
            bytesRead += count
        }
    }

    /**
     * Reads a single framed message from a blocking InputStream.
     * Returns null if clean EOF is encountered before reading any bytes of a new frame.
     * Throws IllegalArgumentException if frame size exceeds [MAX_FRAME_SIZE] or is negative.
     */
    @Throws(EOFException::class, IllegalArgumentException::class)
    fun readFrame(inputStream: InputStream): ByteArray? {
        val header = ByteArray(HEADER_SIZE)
        var firstByte = inputStream.read()
        if (firstByte == -1) {
            return null // Clean end of stream
        }
        header[0] = firstByte.toByte()

        // Read the remaining 3 header bytes
        readFully(inputStream, header, 1, HEADER_SIZE - 1)

        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        if (length <= 0) {
            throw IllegalArgumentException("Invalid frame length: $length (must be > 0)")
        }
        if (length > MAX_FRAME_SIZE) {
            throw IllegalArgumentException("Frame length $length exceeds maximum allowed size $MAX_FRAME_SIZE")
        }

        val payload = ByteArray(length)
        readFully(inputStream, payload, 0, length)
        return payload
    }
}

/**
 * State machine decoder for processing arbitrary incoming byte chunks.
 * Handles partial reads, multiple messages in a single chunk, and fragments across chunks.
 * Ideal for unit testing stream slicing and async channel operations.
 */
class FrameBufferDecoder(
    private val maxFrameSize: Int = MessageFraming.MAX_FRAME_SIZE
) {
    private val buffer = ByteArrayOutputStream()

    /**
     * Feeds incoming byte data into the decoder buffer.
     * Returns a list of all fully decoded frame payloads available.
     * Throws IllegalArgumentException if an incoming frame specifies an invalid size.
     */
    @Synchronized
    fun feed(data: ByteArray, offset: Int = 0, length: Int = data.size): List<ByteArray> {
        buffer.write(data, offset, length)
        val extractedFrames = mutableListOf<ByteArray>()

        var currentBytes = buffer.toByteArray()
        var currentOffset = 0

        while (currentBytes.size - currentOffset >= MessageFraming.HEADER_SIZE) {
            val headerSlice = ByteBuffer.wrap(currentBytes, currentOffset, MessageFraming.HEADER_SIZE)
                .order(ByteOrder.BIG_ENDIAN)
            val payloadLength = headerSlice.int

            if (payloadLength <= 0) {
                // Clear buffer on corrupt framing
                buffer.reset()
                throw IllegalArgumentException("Invalid frame length: $payloadLength")
            }
            if (payloadLength > maxFrameSize) {
                // Clear buffer on oversized frame
                buffer.reset()
                throw IllegalArgumentException("Frame length $payloadLength exceeds max size $maxFrameSize")
            }

            val totalFrameLength = MessageFraming.HEADER_SIZE + payloadLength
            if (currentBytes.size - currentOffset >= totalFrameLength) {
                // We have a complete frame!
                val payload = currentBytes.copyOfRange(
                    currentOffset + MessageFraming.HEADER_SIZE,
                    currentOffset + totalFrameLength
                )
                extractedFrames.add(payload)
                currentOffset += totalFrameLength
            } else {
                // Frame is incomplete; wait for more data
                break
            }
        }

        // Retain remaining unparsed bytes in buffer
        val remainingBytes = currentBytes.size - currentOffset
        buffer.reset()
        if (remainingBytes > 0) {
            buffer.write(currentBytes, currentOffset, remainingBytes)
        }

        return extractedFrames
    }

    /**
     * Returns the count of buffered bytes currently waiting for completion.
     */
    @Synchronized
    fun getBufferedByteCount(): Int {
        return buffer.size()
    }

    @Synchronized
    fun reset() {
        buffer.reset()
    }
}
