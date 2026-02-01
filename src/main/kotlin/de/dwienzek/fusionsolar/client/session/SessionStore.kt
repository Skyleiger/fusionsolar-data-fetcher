package de.dwienzek.fusionsolar.client.session

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * Interface for persisting and loading FusionSolar sessions
 */
interface SessionStore {
    /**
     * Save a session snapshot
     */
    suspend fun save(snapshot: SessionSnapshot)

    /**
     * Load a previously saved session snapshot
     * @return The session snapshot or null if no session was saved
     */
    suspend fun load(): SessionSnapshot?
}

/**
 * File-based session store that persists sessions as JSON
 */
class FileSessionStore(
    private val filePath: Path,
) : SessionStore {
    private val logger = KotlinLogging.logger {}
    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    override suspend fun save(snapshot: SessionSnapshot) {
        withContext(Dispatchers.IO) {
            try {
                val jsonContent = json.encodeToString(snapshot)
                filePath.writeText(jsonContent)
                logger.debug { "Session saved to $filePath" }
            } catch (e: Exception) {
                logger.error(e) { "Failed to save session to $filePath" }
                throw e
            }
        }
    }

    override suspend fun load(): SessionSnapshot? =
        withContext(Dispatchers.IO) {
            try {
                if (filePath.exists()) {
                    val jsonContent = filePath.readText()
                    val snapshot = json.decodeFromString<SessionSnapshot>(jsonContent)
                    logger.debug { "Session loaded from $filePath" }
                    snapshot
                } else {
                    logger.debug { "No session file found at $filePath" }
                    null
                }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to load session from $filePath" }
                null
            }
        }
}
