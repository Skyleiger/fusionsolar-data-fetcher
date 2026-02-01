package de.dwienzek.fusionsolar.client.exception

/**
 * Base exception for all FusionSolar-related errors
 */
open class FusionSolarException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Exception thrown when authentication fails
 */
class AuthenticationException(
    message: String,
    cause: Throwable? = null,
) : FusionSolarException(message, cause)
