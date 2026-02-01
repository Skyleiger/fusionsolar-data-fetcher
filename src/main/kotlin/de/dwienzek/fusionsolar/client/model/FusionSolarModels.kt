package de.dwienzek.fusionsolar.client.model

import kotlin.time.Instant

data class HistoryData(
    val totalPvEnergy: Double?,
    val totalUseEnergy: Double?,
    val totalSelfUseEnergy: Double?,
    val totalGridImportEnergy: Double?,
    val totalGridExportEnergy: Double?,
    val dataPoints: List<HistoryDataPoint>,
)

data class HistoryDataPoint(
    val timestamp: Instant,
    val pvPower: Double?,
    val usePower: Double?,
    val pvUsePower: Double?,
    val batteryPower: Double?,
    val batterySoc: Double?,
)
