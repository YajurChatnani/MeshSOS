package com.example.meshsos.transport.wifi

import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Compact, versioned identity payload advertised via Wi-Fi Aware service discovery
 * (using PublishConfig.Builder.setServiceSpecificInfo).
 *
 * Designed to fit comfortably within the ~255 byte service-specific info limit of Wi-Fi Aware beacons.
 * Total wire payload is typically 50-75 bytes.
 */
data class WifiAwareIdentity(
    val version: Int = CURRENT_VERSION,
    val deviceId: String,
    val deviceName: String
) {
    init {
        require(deviceId.isNotBlank()) { "deviceId cannot be blank" }
        require(deviceName.isNotBlank()) { "deviceName cannot be blank" }
    }

    /**
     * Serializes to a compact binary payload:
     * [1 byte: version] +
     * [1 byte: idLength] + [idLength bytes: deviceId (UTF-8)] +
     * [1 byte: nameLength] + [nameLength bytes: deviceName (UTF-8, max 32 chars)]
     */
    fun toByteArray(): ByteArray {
        val idBytes = deviceId.toByteArray(StandardCharsets.UTF_8).take(MAX_ID_BYTES).toByteArray()
        val nameBytes = deviceName.trim().take(MAX_NAME_CHARS).toByteArray(StandardCharsets.UTF_8).take(MAX_NAME_BYTES).toByteArray()

        val totalLength = 1 + 1 + idBytes.size + 1 + nameBytes.size
        val buffer = ByteBuffer.allocate(totalLength)
        buffer.put(version.toByte())
        buffer.put(idBytes.size.toByte())
        buffer.put(idBytes)
        buffer.put(nameBytes.size.toByte())
        buffer.put(nameBytes)
        return buffer.array()
    }

    companion object {
        const val CURRENT_VERSION = 1
        const val MAX_ID_BYTES = 48
        const val MAX_NAME_CHARS = 32
        const val MAX_NAME_BYTES = 48

        /**
         * Parses an identity payload from serviceSpecificInfo bytes.
         * Supports compact binary format (version 1) and JSON fallback.
         * Returns null if the byte array is null, empty, or malformed.
         */
        fun fromByteArray(bytes: ByteArray?): WifiAwareIdentity? {
            if (bytes == null || bytes.isEmpty()) return null

            // 1. Attempt binary parse if starts with version byte (0x01)
            if (bytes[0] == CURRENT_VERSION.toByte() && bytes.size >= 4) {
                try {
                    val buffer = ByteBuffer.wrap(bytes)
                    val version = buffer.get().toInt() and 0xFF
                    val idLen = buffer.get().toInt() and 0xFF
                    if (idLen > 0 && buffer.remaining() >= idLen + 1) {
                        val idBytes = ByteArray(idLen)
                        buffer.get(idBytes)
                        val id = String(idBytes, StandardCharsets.UTF_8)

                        val nameLen = buffer.get().toInt() and 0xFF
                        if (nameLen > 0 && buffer.remaining() >= nameLen) {
                            val nameBytes = ByteArray(nameLen)
                            buffer.get(nameBytes)
                            val name = String(nameBytes, StandardCharsets.UTF_8)
                            if (id.isNotBlank() && name.isNotBlank()) {
                                return WifiAwareIdentity(
                                    version = version,
                                    deviceId = id,
                                    deviceName = name
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Binary parse failed; try JSON fallback below
                }
            }

            // 2. JSON fallback (starts with '{')
            if (bytes[0] == '{'.code.toByte()) {
                try {
                    val jsonStr = String(bytes, StandardCharsets.UTF_8)
                    val json = JSONObject(jsonStr)
                    val id = json.optString("id", "")
                    val name = json.optString("name", "")
                    val ver = json.optInt("v", CURRENT_VERSION)
                    if (id.isNotBlank() && name.isNotBlank()) {
                        return WifiAwareIdentity(
                            version = ver,
                            deviceId = id,
                            deviceName = name
                        )
                    }
                } catch (e: Exception) {
                    // Ignore JSON parse error
                }
            }

            return null
        }
    }
}
