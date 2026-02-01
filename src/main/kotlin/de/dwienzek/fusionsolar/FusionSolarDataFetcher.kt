package de.dwienzek.fusionsolar

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.path
import de.dwienzek.fusionsolar.client.FusionSolarClient
import de.dwienzek.fusionsolar.client.model.HistoryData
import de.dwienzek.fusionsolar.client.model.HistoryDataPoint
import de.dwienzek.fusionsolar.client.session.FileSessionStore
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.todayIn
import java.nio.file.Path
import kotlin.time.Clock

val BERLIN_TIME_ZONE = TimeZone.of("Europe/Berlin")
private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) = FusionSolarDataFetcher().main(args)

class FusionSolarDataFetcher : CliktCommand(name = "fusionsolar-data-fetcher") {
    private val username: String by option("--username", help = "FusionSolar username").required()
    private val password: String by option("--password", help = "FusionSolar password").required()
    private val subdomain: String by option(
        "--subdomain",
        help = "FusionSolar subdomain (e.g. region01eu5)",
    ).required()
    private val targetFile: Path by option("--target-file", help = "Target CSV file path")
        .path(canBeDir = false, canBeFile = true)
        .required()
    private val stationId: String by option("--station-id", help = "FusionSolar station ID").required()
    private val batteryId: String by option(
        "--battery-id",
        help = "FusionSolar battery device ID",
    ).required()
    private val startDate: String by option(
        "--start-date",
        help = "Start date in format YYYY-MM-DD",
    ).required()
    private val endDate: String by option(
        "--end-date",
        help = "End date in format YYYY-MM-DD (defaults to today)",
    ).default("")
    private val sessionFile: Path? by option(
        "--session-file",
        help = "Optional session file path to persist authentication session",
    ).path(canBeDir = false, canBeFile = true)

    override fun run(): Unit =
        runBlocking {
            createClient().use { client ->
                val sessionStore = sessionFile?.let { FileSessionStore(it) }

                sessionStore?.let { client.loadSession(it) }
                fetchHistoryData(client)
                sessionStore?.let { client.saveSession(it) }
            }
        }

    private fun createClient() =
        FusionSolarClient(
            username = username,
            password = password,
            huaweiSubdomain = subdomain,
        )

    private suspend fun fetchHistoryData(client: FusionSolarClient) {
        try {
            fetchHistory(client)
        } catch (e: Exception) {
            logger.error(e) { "Failed to fetch history data: ${e.message}" }
            throw e
        }
    }

    private suspend fun fetchHistory(client: FusionSolarClient) {
        val csvFile = initializeCsvFile()
        val dateRange = parseDateRange().toList()

        logger.info { "Starting data fetch for station $stationId and battery $batteryId" }
        logger.info { "Date range: ${dateRange.first()} to ${dateRange.last()} (${dateRange.size} days)" }
        logger.info { "Target file: $targetFile" }

        var totalRows = 0

        dateRange.forEach { date ->
            val rows = fetchHistoryForDate(client, csvFile, date)
            totalRows += rows
        }

        logger.info { "Data fetch completed successfully" }
        logger.info { "Total rows written: $totalRows" }
    }

    private fun initializeCsvFile(): CsvFile {
        val csvFile = CsvFile(targetFile)
        csvFile.writeRow(
            "utc_timestamp",
            "europe_berlin_timestamp",
            "pv_power",
            "use_power",
            "pv_use_power",
            "battery_power",
            "battery_soc",
            "total_pv_energy",
            "total_use_energy",
            "total_self_use_energy",
            "total_grid_import_energy",
            "total_grid_export_energy",
        )
        return csvFile
    }

    private fun parseDateRange(): Sequence<LocalDate> {
        val start = LocalDate.parse(startDate)
        val end =
            if (endDate.isEmpty()) {
                Clock.System.todayIn(BERLIN_TIME_ZONE)
            } else {
                LocalDate.parse(endDate)
            }

        return generateSequence(start) { it.plus(1, DateTimeUnit.DAY) }
            .takeWhile { it <= end }
    }

    private suspend fun fetchHistoryForDate(
        client: FusionSolarClient,
        csvFile: CsvFile,
        date: LocalDate,
    ): Int {
        logger.info { "Fetching data for date: $date" }
        val historyData = client.getHistoryData(stationId, batteryId, date)

        historyData.dataPoints.forEach { dataPoint ->
            writeDataPointToCsv(csvFile, dataPoint, historyData)
        }

        val rowCount = historyData.dataPoints.size
        logger.info { "✓ Date $date: $rowCount data points written" }
        return rowCount
    }

    private fun writeDataPointToCsv(
        csvFile: CsvFile,
        dataPoint: HistoryDataPoint,
        historyData: HistoryData,
    ) {
        val europeBerlinTimestamp = dataPoint.timestamp.toLocalDateTime(BERLIN_TIME_ZONE)

        csvFile.writeRow(
            dataPoint.timestamp,
            europeBerlinTimestamp,
            dataPoint.pvPower,
            dataPoint.usePower,
            dataPoint.pvUsePower,
            dataPoint.batteryPower,
            dataPoint.batterySoc,
            historyData.totalPvEnergy,
            historyData.totalUseEnergy,
            historyData.totalSelfUseEnergy,
            historyData.totalGridImportEnergy,
            historyData.totalGridExportEnergy,
        )
    }
}
