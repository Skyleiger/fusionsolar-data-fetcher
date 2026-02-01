package de.dwienzek.fusionsolar.client.session

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * Custom serializer for kotlin.time.Instant that serializes to ISO-8601 string
 */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("io.flux.agent.fusionsolar.session.Instant", PrimitiveKind.STRING)

    override fun serialize(
        encoder: Encoder,
        value: Instant,
    ) {
        // Convert to ISO-8601 string format
        val millis = value.toEpochMilliseconds()
        val instant = java.time.Instant.ofEpochMilli(millis)
        encoder.encodeString(instant.toString())
    }

    override fun deserialize(decoder: Decoder): Instant {
        // Parse ISO-8601 string
        val isoString = decoder.decodeString()
        val instant = java.time.Instant.parse(isoString)
        return Instant.fromEpochMilliseconds(instant.toEpochMilli())
    }
}

/**
 * Serializable snapshot of a FusionSolar session
 * Can be used to persist and restore sessions
 */
@Serializable
data class SessionSnapshot(
    val cookies: List<CookieData>,
    val companyId: String?,
    val csrfToken: String?,
    @Serializable(with = InstantSerializer::class)
    val timestamp: Instant,
)

/**
 * Serializable representation of an HTTP cookie
 */
@Serializable
data class CookieData(
    val name: String,
    val value: String,
    val domain: String,
    val path: String,
    val expires: Long?,
    val secure: Boolean,
    val httpOnly: Boolean,
)
