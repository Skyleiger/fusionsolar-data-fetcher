package de.dwienzek.fusionsolar.client.session

import de.dwienzek.fusionsolar.client.auth.RsaEncryption
import de.dwienzek.fusionsolar.client.exception.AuthenticationException
import de.dwienzek.fusionsolar.client.exception.FusionSolarException
import de.dwienzek.fusionsolar.client.model.CompanyDataResponse
import de.dwienzek.fusionsolar.client.model.GenericResponse
import de.dwienzek.fusionsolar.client.model.KeepAliveResponse
import de.dwienzek.fusionsolar.client.model.LoginResponse
import de.dwienzek.fusionsolar.client.model.PublicKeyResponse
import de.dwienzek.fusionsolar.client.model.SessionResponse
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages authentication and session lifecycle for FusionSolar API
 */
internal class FusionSolarSession(
    private val httpClient: HttpClient,
    private val baseUrl: String,
    private val loginSubdomain: String,
    private val username: String,
    private val password: String,
) {
    private val logger = KotlinLogging.logger {}

    private val loginMutex = Mutex()

    internal var companyId: String? = null
    internal var csrfToken: String? = null

    /**
     * Keeps the session alive (should be called manually if needed)
     */
    suspend fun keepAlive(): String? {
        logger.debug { "Sending keep-alive request" }

        return try {
            val response = httpClient.get("$baseUrl/rest/dpcloud/auth/v1/keep-alive")
            val data = response.body<KeepAliveResponse>()

            if (data.code != 0) {
                logger.warn { "Keep-alive failed with code ${data.code}" }
                throw FusionSolarException("Failed to keep session alive: code ${data.code}")
            }

            csrfToken = data.payload
            logger.debug { "Keep-alive successful, CSRF token updated" }

            data.payload
        } catch (e: Exception) {
            logger.warn(e) { "Keep-alive request failed" }
            throw e
        }
    }

    /**
     * Configures the session by logging in and retrieving necessary tokens
     */
    suspend fun configureSession() =
        loginMutex.withLock {
            try {
                logger.info { "Configuring FusionSolar session..." }

                login()

                val payload = keepAlive()
                if (payload == null) {
                    throw FusionSolarException("Login failed. No payload received from keep-alive.")
                }

                // Get company ID
                val response =
                    httpClient.get("$baseUrl/rest/neteco/web/organization/v2/company/current") {
                        parameter("_", System.currentTimeMillis())
                    }

                if (response.status == HttpStatusCode.InternalServerError) {
                    logger.error { "Received 500 error when retrieving company data" }
                    val errorData = response.body<GenericResponse<Unit>>()
                    if (errorData.exceptionId in listOf("Query company failed.", "bad status")) {
                        throw AuthenticationException("Invalid response received. Please check the correct Huawei subdomain.")
                    }
                }

                val companyData = response.body<GenericResponse<CompanyDataResponse>>()
                companyId =
                    companyData.data?.moDn
                        ?: throw AuthenticationException("Failed to retrieve company data")

                logger.debug { "Company ID: $companyId" }

                // Try to get CSRF token
                try {
                    val sessionResponse = httpClient.get("$baseUrl/unisess/v1/auth/session")
                    val sessionData = sessionResponse.body<SessionResponse>()
                    csrfToken = sessionData.csrfToken
                    logger.debug { "CSRF token retrieved" }
                } catch (_: Exception) {
                    logger.debug { "Could not retrieve CSRF token from session endpoint (will use keep-alive payload)" }
                }

                logger.info { "✓ Session configured successfully" }
            } catch (e: Exception) {
                logger.error(e) { "✗ Failed to configure session: ${e.message}" }
                throw e
            }
        }

    /**
     * Performs the login operation
     */
    private suspend fun login() {
        logger.debug { "Authenticating with FusionSolar API..." }

        val keyResponse = httpClient.get("https://eu5.fusionsolar.huawei.com/unisso/pubkey")

        if (keyResponse.status != HttpStatusCode.OK) {
            throw FusionSolarException("Failed to retrieve public key. Status: ${keyResponse.status}")
        }

        val keyData = keyResponse.body<PublicKeyResponse>()

        val (loginUrl, urlParams, encryptedPassword) =
            when {
                keyData.enableEncrypt && keyData.pubKey != null && keyData.version != null -> {
                    Triple(
                        "https://$loginSubdomain.fusionsolar.huawei.com/unisso/v3/validateUser.action",
                        mapOf(
                            "timeStamp" to keyData.timeStamp.toString(),
                            "nonce" to RsaEncryption.getSecureRandom(),
                        ),
                        RsaEncryption.encryptPassword(keyData.pubKey, password, keyData.version),
                    )
                }

                else -> {
                    Triple(
                        "https://$loginSubdomain.fusionsolar.huawei.com/unisso/v2/validateUser.action",
                        mapOf(
                            "decision" to "1",
                            "service" to "$baseUrl/unisess/v1/auth?service=/netecowebext/home/index.html#/LOGIN",
                        ),
                        password,
                    )
                }
            }

        val jsonData =
            mapOf(
                "organizationName" to "",
                "username" to username,
                "password" to encryptedPassword,
            )

        val loginResponse =
            httpClient.post(loginUrl) {
                contentType(ContentType.Application.Json)
                urlParams.forEach { (key, value) -> parameter(key, value) }
                setBody(jsonData)
            }

        val loginData = loginResponse.body<LoginResponse>()

        // Handle error code 470 (new login procedure)
        if (loginData.errorCode == "470") {
            val targetSubdomain =
                loginData.respMultiRegionName?.getOrNull(1)
                    ?: throw AuthenticationException("Missing target subdomain in login response")
            val targetUrl = "https://$loginSubdomain.fusionsolar.huawei.com$targetSubdomain"
            httpClient.get(targetUrl)
        }

        // Check for errors
        loginData.errorMsg?.takeIf { it.isNotBlank() }?.let { error ->
            logger.error { "Authentication failed: $error" }
            throw AuthenticationException("Failed to login: $error")
        }

        logger.debug { "Authentication successful" }
    }
}
