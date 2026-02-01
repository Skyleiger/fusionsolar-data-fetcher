package de.dwienzek.fusionsolar.client.auth

import de.dwienzek.fusionsolar.client.exception.FusionSolarException
import io.ktor.http.encodeURLParameter
import io.ktor.utils.io.charsets.Charsets
import io.ktor.utils.io.core.toByteArray
import java.security.KeyFactory
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import kotlin.io.encoding.Base64
import kotlin.random.Random

/**
 * RSA encryption utilities for FusionSolar password encryption
 */
internal object RsaEncryption {
    /**
     * Generates a secure random hex string (16 bytes)
     */
    fun getSecureRandom(): String {
        val bytes = Random.nextBytes(16)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Encrypts a password using RSA with OAEP padding (SHA-384)
     */
    fun encryptPassword(
        pubKey: String,
        password: String,
        version: String,
    ): String {
        // Load the public key
        val publicKey =
            try {
                val keyBytes =
                    Base64.decode(
                        pubKey
                            .replace("-----BEGIN PUBLIC KEY-----", "")
                            .replace("-----END PUBLIC KEY-----", "")
                            .replace("\n", "")
                            .replace("\r", "")
                            .trim(),
                    )
                val keySpec = X509EncodedKeySpec(keyBytes)
                val keyFactory = KeyFactory.getInstance("RSA")
                keyFactory.generatePublic(keySpec)
            } catch (e: Exception) {
                throw FusionSolarException("Failed to load public key for encryption", e)
            }

        // URL encode the password
        val valueEncode = password.encodeURLParameter()

        // Initialize cipher with OAEP padding using SHA-384
        val cipher =
            try {
                Cipher.getInstance("RSA/ECB/OAEPPadding").apply {
                    val oaepParams =
                        OAEPParameterSpec(
                            "SHA-384",
                            "MGF1",
                            MGF1ParameterSpec.SHA384,
                            PSource.PSpecified.DEFAULT,
                        )
                    init(Cipher.ENCRYPT_MODE, publicKey, oaepParams)
                }
            } catch (e: Exception) {
                throw FusionSolarException("Failed to initialize cipher", e)
            }

        // Encrypt in chunks of 270 bytes
        return buildString {
            val chunkSize = 270
            val chunks = (valueEncode.length + chunkSize - 1) / chunkSize

            for (i in 0 until chunks) {
                val start = i * chunkSize
                val end = minOf((i + 1) * chunkSize, valueEncode.length)
                val currentValue = valueEncode.substring(start, end)

                val encryptedChunk = cipher.doFinal(currentValue.toByteArray(Charsets.UTF_8))

                if (isNotEmpty()) {
                    append("00000001")
                }

                append(Base64.encode(encryptedChunk))
            }

            append(version)
        }
    }
}
