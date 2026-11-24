package com.example.ui

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Verifies the application access password using a salted SHA-256 digest
 * so plaintext credentials are not stored in source code.
 */
object AuthHelper {
    private const val SALT = "si_sera_kemenag_mojokerto_salt_2026"
    private const val EXPECTED_DIGEST_HEX =
        "aec543af69d44bf05572f5c7ab4c4a4de66df1e9543f47c57183a8727ac55841"

    fun verifyPassword(input: String): Boolean {
        if (input.isEmpty()) return false
        val digest = MessageDigest.getInstance("SHA-256")
        val inputBytes = (SALT + input).toByteArray(StandardCharsets.UTF_8)
        val computedHash = digest.digest(inputBytes)
        val expectedBytes = hexToBytes(EXPECTED_DIGEST_HEX)
        return MessageDigest.isEqual(computedHash, expectedBytes)
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
