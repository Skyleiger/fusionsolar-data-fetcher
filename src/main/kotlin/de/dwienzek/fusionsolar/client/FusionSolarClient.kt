package de.dwienzek.fusionsolar.client

import de.dwienzek.fusionsolar.client.http.FusionSolarHttpClient
import de.dwienzek.fusionsolar.client.model.DeviceHistoryResponse
import de.dwienzek.fusionsolar.client.model.EnergyBalanceResponse
import de.dwienzek.fusionsolar.client.model.HistoryData
import de.dwienzek.fusionsolar.client.model.HistoryDataPoint
import de.dwienzek.fusionsolar.client.session.SessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.client.call.body
import io.ktor.client.request.parameter
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.char
import kotlinx.datetime.toInstant
import kotlin.time.Instant

/**
 * Main client for interacting with the Huawei FusionSolar API (V2)
 *
 * Features:
 * - Optional session persistence and restoration
 * - Automatic session recovery on unauthenticated responses
 *
 * @param username The username for authentication
 * @param password The password for authentication
 * @param huaweiSubdomain The subdomain for the FusionSolar API (e.g., "region01eu5" or "uni001eu5")
 */
class FusionSolarClient(
    username: String,
    password: String,
    huaweiSubdomain: String = "region01eu5",
) : AutoCloseable {
    private val logger = KotlinLogging.logger {}

    private val http =
        FusionSolarHttpClient(
            huaweiSubdomain = huaweiSubdomain,
            username = username,
            password = password,
        )

    // ========== Session Management ==========

    suspend fun loadSession(sessionStore: SessionStore) {
        sessionStore.load()?.let { snapshot ->
            logger.info { "Attempting to restore session from store" }

            try {
                http.restoreSession(snapshot)
                logger.info { "Session restored successfully" }
            } catch (e: Exception) {
                logger.warn(e) { "Failed to restore session, continuing without it" }
            }
        }
    }

    /**
     * Save the current session to the session store
     */
    suspend fun saveSession(sessionStore: SessionStore) {
        sessionStore.let { store ->
            val snapshot = http.exportSession()
            store.save(snapshot)
            logger.debug { "Session saved to store" }
        }
    }

    // ========== Concrete APIs ==========

    suspend fun getHistoryData(
        stationId: String,
        batteryId: String,
        date: LocalDate,
    ): HistoryData =
        coroutineScope {
            try {
                val energyBalanceJob = async { getEnergyBalanceHistory(stationId, date) }
                val batteryHistoryJob = async { getBatteryDeviceHistory(batteryId, date) }

                val energyBalanceResponse = energyBalanceJob.await()
                val batteryHistoryResponse = batteryHistoryJob.await()

                val energyBalancePayload =
                    checkNotNull(energyBalanceResponse.data) {
                        "Energy balance response missing data for station $stationId at $date"
                    }

                val batterySocByTimestamp =
                    batteryHistoryResponse.data
                        ?.get(BATTERY_SOC_SIGNAL_ID)
                        ?.pmDataList
                        ?.mapNotNull { dataPoint ->
                            if (isNoData(dataPoint.value)) {
                                null
                            } else {
                                Instant.fromEpochSeconds(dataPoint.startTime) to dataPoint.value
                            }
                        }?.toMap()
                        ?: emptyMap()

                val pvPower = energyBalancePayload.productPower.map { parsePowerValue(it) }
                val usePower = energyBalancePayload.usePower.map { parsePowerValue(it) }
                val pvUsePower = energyBalancePayload.selfUsePower.map { parsePowerValue(it) }
                val chargePower = energyBalancePayload.chargePower.map { parsePowerValue(it) }
                val dischargePower = energyBalancePayload.dischargePower.map { parsePowerValue(it) }

                val dataPoints =
                    energyBalancePayload.xAxis.mapIndexed { index, timeText ->
                        val timestamp = parseToInstantUtc(timeText)

                        val charge = chargePower.getOrNull(index)
                        val discharge = dischargePower.getOrNull(index)
                        val batteryPower =
                            if (charge == null && discharge == null) {
                                null
                            } else {
                                (charge ?: 0.0) - (discharge ?: 0.0)
                            }

                        HistoryDataPoint(
                            timestamp = timestamp,
                            pvPower = pvPower.getOrNull(index),
                            usePower = usePower.getOrNull(index),
                            pvUsePower = pvUsePower.getOrNull(index),
                            batteryPower = batteryPower,
                            batterySoc = batterySocByTimestamp[timestamp],
                        )
                    }

                logger.debug { "Retrieved ${dataPoints.size} data points for $date" }

                HistoryData(
                    totalPvEnergy = parsePowerValue(energyBalancePayload.totalProductPower),
                    totalUseEnergy = parsePowerValue(energyBalancePayload.totalUsePower),
                    totalSelfUseEnergy = parsePowerValue(energyBalancePayload.totalSelfUsePower),
                    totalGridImportEnergy = parsePowerValue(energyBalancePayload.totalBuyPower),
                    totalGridExportEnergy = parsePowerValue(energyBalancePayload.totalOnGridPower),
                    dataPoints = dataPoints,
                )
            } catch (e: Exception) {
                logger.error(e) { "Failed to fetch history data for date $date: ${e.message}" }
                throw e
            }
        }

    private suspend fun getEnergyBalanceHistory(
        stationId: String,
        date: LocalDate,
    ): EnergyBalanceResponse {
        val queryTime = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

        return http.get("/rest/pvms/web/station/v1/overview/energy-balance", {
            parameter("stationDn", stationId)
            parameter("timeDim", 2)
            parameter("queryTime", queryTime)
            parameter("timeZone", 0)
            parameter("timeZoneStr", "UTC")
        }) { response ->
            response.body<EnergyBalanceResponse>()
        }
    }

    private suspend fun getBatteryDeviceHistory(
        batteryId: String,
        date: LocalDate,
    ): DeviceHistoryResponse {
        val dateMillis = date.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()

        return http.get("/rest/pvms/web/device/v1/device-history-data", {
            parameter("signalIds", BATTERY_SOC_SIGNAL_ID)
            parameter("deviceDn", batteryId)
            parameter("date", dateMillis)
        }) { response ->
            response.body<DeviceHistoryResponse>()
        }
    }

    override fun close() {
        http.close()
    }
}

const val BATTERY_SOC_SIGNAL_ID = "30007"
private const val MAX_JS_NUMBER = 1.7976931348623157E308

private fun parsePowerValue(value: String?): Double? {
    if (value == null) return null

    val content = value.trim()
    if (content == "-" || content == "--" || content.equals("N/A", ignoreCase = true)) {
        return null
    }
    val value = content.toDoubleOrNull() ?: return null
    return if (isNoData(value)) null else value
}

private fun isNoData(value: Double): Boolean = value == MAX_JS_NUMBER

fun parseToInstantUtc(input: String): Instant = LocalDateTime.parse(input, INSTANT_UTC_FORMAT).toInstant(TimeZone.UTC)

private val INSTANT_UTC_FORMAT =
    LocalDateTime.Format {
        date(LocalDate.Formats.ISO) // yyyy-MM-dd
        char(' ')
        hour()
        char(':')
        minute() // HH:mm
    }
