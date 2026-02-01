package de.dwienzek.fusionsolar.client.http

import de.dwienzek.fusionsolar.client.session.CookieData
import de.dwienzek.fusionsolar.client.session.FusionSolarSession
import de.dwienzek.fusionsolar.client.session.SessionSnapshot
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.cookies.AcceptAllCookiesStorage
import io.ktor.client.plugins.cookies.HttpCookies
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.logging.LogLevel
import io.ktor.client.plugins.logging.Logger
import io.ktor.client.plugins.logging.Logging
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.request
import io.ktor.http.Cookie
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.serialization.kotlinx.json.json
import io.ktor.util.date.GMTDate
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Clock

/**
 * HTTP abstraction layer for FusionSolar API with session recovery
 */
internal class FusionSolarHttpClient(
    huaweiSubdomain: String,
    username: String,
    password: String,
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}

    private val baseUrl = "https://$huaweiSubdomain.fusionsolar.huawei.com"

    private val loginSubdomain: String =
        when {
            huaweiSubdomain.startsWith("region") -> huaweiSubdomain.substring(8)
            huaweiSubdomain.startsWith("uni") -> huaweiSubdomain.substring(6)
            else -> huaweiSubdomain
        }

    private val cookieStorage = AcceptAllCookiesStorage()
    private val csrfMutex = Mutex()

    private val httpClient: HttpClient =
        HttpClient(CIO) {
            install(HttpCookies) {
                storage = cookieStorage
            }
            install(ContentNegotiation) {
                json(
                    Json {
                        ignoreUnknownKeys = true
                        isLenient = true
                    },
                )
            }
            install(Logging) {
                logger =
                    object : Logger {
                        private val ktorLogger = KotlinLogging.logger("ktor.client")

                        override fun log(message: String) {
                            ktorLogger.debug { message }
                        }
                    }
                level = LogLevel.INFO
            }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 30_000
            }
            defaultRequest {
                header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
                )
            }
        }

    internal val session: FusionSolarSession =
        FusionSolarSession(
            httpClient = httpClient,
            baseUrl = baseUrl,
            loginSubdomain = loginSubdomain,
            username = username,
            password = password,
        )

    /**
     * Performs a GET request and re-authenticates on unauthenticated responses
     */
    suspend fun <T> get(
        path: String,
        builder: HttpRequestBuilder.() -> Unit = {},
        responseHandler: suspend (HttpResponse) -> T,
    ): T {
        logger.debug { "GET request to $path" }

        return executeWithReauth(
            requestLabel = "GET $path",
            request = {
                httpClient.get(buildUrl(path)) {
                    addTimestamp()
                    builder()
                }
            },
            responseHandler = responseHandler,
        )
    }

    /**
     * Exports the current session as a snapshot for persistence
     */
    suspend fun exportSession(): SessionSnapshot {
        logger.debug { "Exporting session snapshot" }

        val cookies = cookieStorage.get(Url(baseUrl))
        val cookieDataList =
            cookies.map { cookie ->
                CookieData(
                    name = cookie.name,
                    value = cookie.value,
                    domain = cookie.domain ?: baseUrl,
                    path = cookie.path ?: "/",
                    expires = cookie.expires?.timestamp,
                    secure = cookie.secure,
                    httpOnly = cookie.httpOnly,
                )
            }

        return SessionSnapshot(
            cookies = cookieDataList,
            companyId = getCompanyId(),
            csrfToken = getCsrfToken(),
            timestamp = Clock.System.now(),
        )
    }

    /**
     * Restores a session from a snapshot
     */
    suspend fun restoreSession(snapshot: SessionSnapshot) {
        logger.info { "Restoring session from snapshot (timestamp: ${snapshot.timestamp})" }

        // Restore cookies
        snapshot.cookies.forEach { cookieData ->
            val cookie =
                Cookie(
                    name = cookieData.name,
                    value = cookieData.value,
                    domain = cookieData.domain,
                    path = cookieData.path,
                    secure = cookieData.secure,
                    httpOnly = cookieData.httpOnly,
                    expires =
                        cookieData.expires?.let {
                            GMTDate(it * 1000) // Convert seconds to milliseconds
                        },
                )
            // Add cookie to the URL that matches its domain
            val cookieUrl = "https://${cookieData.domain}${cookieData.path}"
            logger.debug { "Restoring cookie ${cookieData.name} to $cookieUrl" }
            cookieStorage.addCookie(Url(cookieUrl), cookie)
        }

        // Restore session data
        setCompanyId(snapshot.companyId)
        setCsrfToken(snapshot.csrfToken)

        logger.info { "Session snapshot restored" }
    }

    /**
     * Builds full URL from base and path
     */
    private fun buildUrl(path: String): String =
        if (path.startsWith("http")) {
            path
        } else {
            "$baseUrl${if (path.startsWith("/")) "" else "/"}$path"
        }

    /**
     * Adds timestamp parameter to request
     */
    private fun HttpRequestBuilder.addTimestamp() {
        parameter("_", System.currentTimeMillis())
    }

    /**
     * Adds CSRF token header to request if available
     */
    private suspend fun HttpRequestBuilder.addCsrfToken() =
        csrfMutex.withLock {
            session.csrfToken?.let { token ->
                header("roarand", token)
                logger.debug { "Added CSRF token to request" }
            }
        }

    private suspend fun <T> executeWithReauth(
        requestLabel: String,
        request: suspend () -> HttpResponse,
        responseHandler: suspend (HttpResponse) -> T,
    ): T {
        val response = request()

        if (isUnauthenticated(response)) {
            logger.info { "$requestLabel failed with ${response.status}, re-authenticating" }
            session.configureSession()
            val retryResponse = request()
            return responseHandler(retryResponse)
        }

        return responseHandler(response)
    }

    private fun isUnauthenticated(response: HttpResponse): Boolean {
        val status = response.status
        if (status == HttpStatusCode.Unauthorized || status == HttpStatusCode.Forbidden) {
            return true
        }

        val finalUrl = response.request.url.toString()
        return finalUrl.contains("/unisso/login.action")
    }

    /**
     * Get company ID from session
     */
    fun getCompanyId(): String? = session.companyId

    /**
     * Get CSRF token from session
     */
    fun getCsrfToken(): String? = session.csrfToken

    /**
     * Set company ID in session
     */
    fun setCompanyId(companyId: String?) {
        session.companyId = companyId
    }

    /**
     * Set CSRF token in session
     */
    fun setCsrfToken(token: String?) {
        session.csrfToken = token
    }

    override fun close() {
        logger.info { "Closing FusionSolar HTTP client" }
        httpClient.close()
    }
}
