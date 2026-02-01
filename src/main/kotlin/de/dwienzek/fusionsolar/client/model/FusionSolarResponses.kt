package de.dwienzek.fusionsolar.client.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class GenericResponse<T>(
    @SerialName("code") val code: Int? = null,
    @SerialName("data") val data: T? = null,
    @SerialName("exceptionId") val exceptionId: String? = null,
)

@Serializable
internal data class PublicKeyResponse(
    @SerialName("version") val version: String? = null,
    @SerialName("pubKey") val pubKey: String? = null,
    @SerialName("timeStamp") val timeStamp: Long,
    @SerialName("enableEncrypt") val enableEncrypt: Boolean,
)

@Serializable
internal data class LoginResponse(
    @SerialName("errorCode") val errorCode: String? = null,
    @SerialName("errorMsg") val errorMsg: String? = null,
    @SerialName("respMultiRegionName") val respMultiRegionName: List<String>? = null,
)

@Serializable
internal data class SessionResponse(
    @SerialName("csrfToken") val csrfToken: String,
)

@Serializable
internal data class CompanyDataResponse(
    @SerialName("moDn") val moDn: String,
)

@Serializable
internal data class KeepAliveResponse(
    @SerialName("code") val code: Int,
    @SerialName("payload") val payload: String,
)

@Serializable
internal data class EnergyBalanceResponse(
    @SerialName("data") val data: EnergyBalancePayload? = null,
)

@Serializable
internal data class EnergyBalancePayload(
    @SerialName("productPower") val productPower: List<String>,
    @SerialName("usePower") val usePower: List<String>,
    @SerialName("selfUsePower") val selfUsePower: List<String>,
    @SerialName("chargePower") val chargePower: List<String>,
    @SerialName("dischargePower") val dischargePower: List<String>,
    @SerialName("xAxis") val xAxis: List<String>,
    @SerialName("totalSelfUsePower") val totalSelfUsePower: String? = null,
    @SerialName("totalBuyPower") val totalBuyPower: String? = null,
    @SerialName("totalProductPower") val totalProductPower: String? = null,
    @SerialName("totalUsePower") val totalUsePower: String? = null,
    @SerialName("totalOnGridPower") val totalOnGridPower: String? = null,
)

@Serializable
internal data class DeviceHistoryResponse(
    @SerialName("data") val data: Map<String, DeviceSignalData>? = null,
)

@Serializable
internal data class DeviceSignalData(
    @SerialName("pmDataList") val pmDataList: List<RawSignalDataPoint>,
)

@Serializable
internal data class RawSignalDataPoint(
    @SerialName("startTime") val startTime: Long,
    @SerialName("counterValue") val value: Double,
)
