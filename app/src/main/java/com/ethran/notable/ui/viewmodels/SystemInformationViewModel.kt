package com.ethran.notable.ui.viewmodels

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.onyx.android.sdk.api.device.epd.EPDMode
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.api.device.epd.UpdateOption
import com.onyx.android.sdk.device.BaseDevice
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.style.StrokeStyle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject


/**
 * Snapshot for displaying read-only info gathered from BaseDevice (1.3.x).
 * All fields are nullable-safe for display; failures are recorded in [errors].
 */
data class DeviceSnapshot(
    //classes
    val actualDeviceClass: String? = null,
    val deviceClassHierarchy: String? = null,


    // Screen
    val epdMode: EPDMode?,
    val systemDefaultUpdateMode: UpdateMode?,
    val appScopeRefreshMode: UpdateOption?,
    val inSystemFastMode: Boolean?,
    val inAppFastMode: Boolean?,
    val inFastMode: Boolean?,
    val globalContrast: Int?,
    val ditherThreshold: Int?,
    val colorType: Int?,
    val supportNightMode: Boolean?,
    val supportWideColorGamut: Boolean?,

    // Writing/Input
    val touchpadEnabled: Boolean?,
    val supportActivePen: Boolean?,
    val activePenEnabled: Boolean?,
    val activePenBattery: Int?,
    val activePenMac: String?,
    val penUIVisibilityEnabled: Boolean?,
    val penHapticEnabled: Boolean?,
    val touchWidth: Float?,
    val touchHeight: Float?,
    val maxTouchPressure: Float?,
    val epdWidth: Float?,
    val epdHeight: Float?,
    val isValidPenState: Boolean?,

    // Light
    val hasFrontLightBrightness: Boolean?,
    val hasCTMBrightness: Boolean?,
    val isLightOn: Boolean?,
    val frontLightMin: Int?,
    val frontLightMax: Int?,
    val frontLightDefault: Int?,
    val frontLightConfigValue: Int?,
    val warmLightConfigValue: Int?,
    val coldLightConfigValue: Int?,
    val checkCTM: Boolean?,
    val brDefault: Int?,
    val ctDefault: Int?,

    // Basic system
    val powerSavedMode: Boolean?,
    val hallControlEnabled: Boolean?,
    val bootUpTimeMs: Long?,
    val isEngBuild: Boolean?,
    val isUserDebugBuild: Boolean?,
    val resetPasswordSupported: Boolean?,
    val systemConfigPrefix: String?,
    val minPasswordLength: Int?,
    val maxPasswordLength: Int?,

    // Connectivity
    val hasAudio: Boolean?,
    val hasWifi: Boolean?,
    val hasBluetooth: Boolean?,
    val fixedWifiMac: String?,
    val bluetoothAddress: String?,
    val encryptedDeviceId: String?,

    // Wireless charging
    val supportWirelessCharging: Boolean?,
    val wirelessChargingBattery: Int?,
    val wirelessChargingState: Int?,
    val wirelessChargingChipId: String?,
    val wirelessChargingChipVersion: String?,

    // Multi-Window
    val originMultiWindow: Boolean?,
    val currentMultiScreenMode: Int?,
    val limitedMultiScreenMode: Boolean?,
    val fullFunctionMultiScreenMode: Boolean?,

    // Storage
    val primaryStorageRemovable: Boolean?,
    val storageRoot: File?,
    val removableSdDirs: List<File>?,
    val usbStoragePresent: Boolean?,

    // Fonts / Misc
    val cpuId: String?,
    val supportExternalSd: Boolean?,
    val supportFontHotReload: Boolean?,
    val systemFontFamilyMap: Map<String, String>?,

    val errors: List<String> = emptyList()
)

data class StrokeStyleInfo(
    val styleId: Int,
    val styleName: String,
    val parameters: List<Float>?,
    val extraNotes: String? = null
)


@HiltViewModel
class SystemInformationViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow<SystemInfoUiState?>(null)
    val uiState: StateFlow<SystemInfoUiState?> = _uiState.asStateFlow()

    data class SystemInfoUiState(
        val snapshot: DeviceSnapshot,
        val strokeInfo: List<StrokeStyleInfo>
    )

    fun refresh(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val device = Device.currentDevice()
            val snapshot = collectDeviceSnapshot(context, device)
            val strokes = buildStrokeStyleInfo(device)
            _uiState.value = SystemInfoUiState(snapshot, strokes)
        }
    }



    /**
     * Separate builder for the Stroke Styles section.
     * Iterates through StrokeStyle constants and fetches parameters via BaseDevice.getStrokeParameters(int),
     * guarding against exceptions. Adds simple notes where useful.
     */


    /**
     * Exception-safe snapshot collection for BaseDevice (1.3.x).
     */
    @Suppress("UsePropertyAccessSyntax")
    fun collectDeviceSnapshot(context: Context, base: BaseDevice): DeviceSnapshot {
        val errors = mutableListOf<String>()
        fun <T> safeNullable(name: String, call: () -> T?): T? = try {
            call()
        } catch (t: Throwable) {
            errors.add("$name failed: ${t.javaClass.simpleName}${t.message?.let { " - $it" } ?: ""}")
            null
        }

        val actualDeviceClass = base.javaClass.name
        val deviceClassHierarchy = generateSequence<Class<*>>(base.javaClass) { it.superclass }
            .joinToString(" -> ") { it.simpleName }

        // Screen
        val epdMode = safeNullable("getEpdMode") { base.getEpdMode() }
        val sysUpdateMode =
            safeNullable("getSystemDefaultUpdateMode") { base.getSystemDefaultUpdateMode() }
        val appRefreshMode =
            safeNullable("getAppScopeRefreshMode") { base.getAppScopeRefreshMode() }
        val inSysFast = safeNullable("isInSystemFastMode") { base.isInSystemFastMode() }
        val inAppFast = safeNullable("isInAppFastMode") { base.isInAppFastMode() }
        val inFast = safeNullable("isInFastMode") { base.isInFastMode() }
        val globalContrast = safeNullable("getGlobalContrast") { base.getGlobalContrast() }
        val dither = safeNullable("getDitherThreshold") { base.getDitherThreshold() }
        val colorType = safeNullable("getColorType") { base.getColorType() }
        val supportNight = safeNullable("isSupportNightMode") { base.isSupportNightMode() }
        val wideCG = safeNullable("isSupportWidecg") { base.isSupportWidecg(context) }

        // Writing/Input
        val touchpadEnabled = safeNullable("isTouchpadEnable") { base.isTouchpadEnable() }
        val supportActivePen = safeNullable("supportActivePen") { base.supportActivePen() }
        val activePenEnabled = safeNullable("getActivePenEnable") { base.getActivePenEnable() }
        val activePenBattery =
            safeNullable("getActivePenBatteryLevel") { base.getActivePenBatteryLevel() }
        val activePenMac = safeNullable("getActivePenMacAddress") { base.getActivePenMacAddress() }
        val penUIVisibilityEnabled =
            safeNullable("isPenUIVisibilityEnable") { base.isPenUIVisibilityEnable() }
        val penHapticEnabled = safeNullable("isPenHapticEnabled") { base.isPenHapticEnabled() }
        val touchWidth = safeNullable("getTouchWidth") { base.getTouchWidth() }
        val touchHeight = safeNullable("getTouchHeight") { base.getTouchHeight() }
        val maxTouchPressure = safeNullable("getMaxTouchPressure") { base.getMaxTouchPressure() }
        val epdWidth = safeNullable("getEpdWidth") { base.getEpdWidth() }
        val epdHeight = safeNullable("getEpdHeight") { base.getEpdHeight() }
        val isValidPenState = safeNullable("isValidPenState") { base.isValidPenState() }


        // Light
        val hasFL = safeNullable("hasFLBrightness") { base.hasFLBrightness(context) }
        val hasCTM = safeNullable("hasCTMBrightness") { base.hasCTMBrightness(context) }
        val lightOn = safeNullable("isLightOn") { base.isLightOn(context) }
        val flMin =
            safeNullable("getFrontLightBrightnessMinimum") {
                base.getFrontLightBrightnessMinimum(
                    context
                )
            }
        val flMax =
            safeNullable("getFrontLightBrightnessMaximum") {
                base.getFrontLightBrightnessMaximum(
                    context
                )
            }
        val flDef = safeNullable("getBrDefaultValue") { base.getBrDefaultValue() }
        val flCfg =
            safeNullable("getFrontLightConfigValue") { base.getFrontLightConfigValue(context) }
        val warmCfg =
            safeNullable("getWarmLightConfigValue") { base.getWarmLightConfigValue(context) }
        val coldCfg =
            safeNullable("getColdLightConfigValue") { base.getColdLightConfigValue(context) }
        val checkCTM = safeNullable("checkCTM") { base.checkCTM() }
        val brDefault = safeNullable("getBrDefaultValue") { base.getBrDefaultValue() }
        val ctDefault = safeNullable("getCtDefaultValue") { base.getCtDefaultValue() }

        // Basic system
        val powerSaved = safeNullable("isPowerSavedMode") { base.isPowerSavedMode(context) }
        val hallEnabled = safeNullable("isHallControlEnable") { base.isHallControlEnable(context) }
        val bootTime = safeNullable("getBootUpTime") { base.getBootUpTime() }
        val isEng = safeNullable("isEngVersion") { base.isEngVersion() }
        val isUserDebug = safeNullable("isUserDebugVersion") { base.isUserDebugVersion() }
        val resetPwd = safeNullable("isResetPasswordSupported") { base.isResetPasswordSupported() }
        val sysPrefix =
            safeNullable("getSystemConfigPrefix") { base.getSystemConfigPrefix(context) }
        val minPwd = safeNullable("getMinPasswordLength") { base.getMinPasswordLength(context) }
        val maxPwd = safeNullable("getMaxPasswordLength") { base.getMaxPasswordLength(context) }

        // Connectivity
        val hasAudio = safeNullable("hasAudio") { base.hasAudio(context) }
        val hasWifi = safeNullable("hasWifi") { base.hasWifi(context) }
        val hasBt = safeNullable("hasBluetooth") { base.hasBluetooth(context) }
        val fixedWifiMac =
            safeNullable("getFixedWifiMacAddress") { base.getFixedWifiMacAddress(context) }
        val btAddr = safeNullable("getBluetoothAddress") { base.getBluetoothAddress() }
        val encId = safeNullable("getEncryptedDeviceID") { base.getEncryptedDeviceID() }

        // Wireless charging
        val supportWireless =
            safeNullable("supportWirelessCharging") { base.supportWirelessCharging() }
        val wirelessBattery =
            safeNullable("getWirelessChargingBatteryLevel") { base.getWirelessChargingBatteryLevel() }
        val wirelessState =
            safeNullable("getWirelessChargingState") { base.getWirelessChargingState() }
        val wirelessChipId =
            safeNullable("getWirelessChargingChipId") { base.getWirelessChargingChipId() }
        val wirelessChipVer =
            safeNullable("getWirelessChargingChipVersion") { base.getWirelessChargingChipVersion() }

        // Multi-window
        val originMW = safeNullable("isOriginMultiWindow") { base.isOriginMultiWindow() }
        val currentMWMode =
            safeNullable("getCurrentMultiScreenMode") { base.getCurrentMultiScreenMode(context) }
        val limitedMW =
            safeNullable("isLimitedMultiScreenMode") { base.isLimitedMultiScreenMode(context) }
        val fullMW =
            safeNullable("isFullFunctionMultiScreenMode") {
                base.isFullFunctionMultiScreenMode(
                    context
                )
            }

        // Storage
        val storageRoot = safeNullable("getStorageRootDirectory") { base.getStorageRootDirectory() }
        val primaryRemovable =
            safeNullable("isPrimaryStorageRemovable") { base.isPrimaryStorageRemovable(context) }
        val removableDirs = safeNullable("getRemovableSDCardDirs") { base.getRemovableSDCardDirs() }
        val usbStorage =
            safeNullable("isUSBStorage") { storageRoot?.path?.let { base.isUSBStorage(it) } }

        // Fonts / Misc
        val cpuId = safeNullable("getCpuId") { base.getCpuId() }
        val externalSD = safeNullable("supportExternalSD") { base.supportExternalSD(context) }
        val fontHotReload = safeNullable("supportFontHotReload") { base.supportFontHotReload() }
        val fontMap = safeNullable("loadSystemFamilyPathMap") { base.loadSystemFamilyPathMap() }

        return DeviceSnapshot(
            actualDeviceClass = actualDeviceClass,
            deviceClassHierarchy = deviceClassHierarchy,
            epdMode = epdMode,
            systemDefaultUpdateMode = sysUpdateMode,
            appScopeRefreshMode = appRefreshMode,
            inSystemFastMode = inSysFast,
            inAppFastMode = inAppFast,
            inFastMode = inFast,
            globalContrast = globalContrast,
            ditherThreshold = dither,
            colorType = colorType,
            supportNightMode = supportNight,
            supportWideColorGamut = wideCG,

            touchpadEnabled = touchpadEnabled,
            supportActivePen = supportActivePen,
            activePenEnabled = activePenEnabled,
            activePenBattery = activePenBattery,
            activePenMac = activePenMac,
            penUIVisibilityEnabled = penUIVisibilityEnabled,
            penHapticEnabled = penHapticEnabled,
            touchWidth = touchWidth,
            touchHeight = touchHeight,
            maxTouchPressure = maxTouchPressure,
            epdWidth = epdWidth,
            epdHeight = epdHeight,
            isValidPenState = isValidPenState,

            hasFrontLightBrightness = hasFL,
            hasCTMBrightness = hasCTM,
            isLightOn = lightOn,
            frontLightMin = flMin,
            frontLightMax = flMax,
            frontLightDefault = flDef,
            frontLightConfigValue = flCfg,
            warmLightConfigValue = warmCfg,
            coldLightConfigValue = coldCfg,
            checkCTM = checkCTM,
            brDefault = brDefault,
            ctDefault = ctDefault,

            powerSavedMode = powerSaved,
            hallControlEnabled = hallEnabled,
            bootUpTimeMs = bootTime,
            isEngBuild = isEng,
            isUserDebugBuild = isUserDebug,
            resetPasswordSupported = resetPwd,
            systemConfigPrefix = sysPrefix,
            minPasswordLength = minPwd,
            maxPasswordLength = maxPwd,

            hasAudio = hasAudio,
            hasWifi = hasWifi,
            hasBluetooth = hasBt,
            fixedWifiMac = fixedWifiMac,
            bluetoothAddress = btAddr,
            encryptedDeviceId = encId,

            supportWirelessCharging = supportWireless,
            wirelessChargingBattery = wirelessBattery,
            wirelessChargingState = wirelessState,
            wirelessChargingChipId = wirelessChipId,
            wirelessChargingChipVersion = wirelessChipVer,

            originMultiWindow = originMW,
            currentMultiScreenMode = currentMWMode,
            limitedMultiScreenMode = limitedMW,
            fullFunctionMultiScreenMode = fullMW,

            primaryStorageRemovable = primaryRemovable,
            storageRoot = storageRoot,
            removableSdDirs = removableDirs,
            usbStoragePresent = usbStorage,

            cpuId = cpuId,
            supportExternalSd = externalSD,
            supportFontHotReload = fontHotReload,
            systemFontFamilyMap = fontMap,
            errors = errors
        )
    }


    fun buildStrokeStyleInfo(
        base: BaseDevice,
    ): List<StrokeStyleInfo> {
        val styles = listOf(
            StrokeStyle.PENCIL to "PENCIL",
            StrokeStyle.FOUNTAIN to "FOUNTAIN",
            StrokeStyle.MARKER to "MARKER",
            StrokeStyle.NEO_BRUSH to "NEO_BRUSH",
            StrokeStyle.CHARCOAL to "CHARCOAL",
            StrokeStyle.DASH to "DASH",
            StrokeStyle.CHARCOAL_V2 to "CHARCOAL_V2",
            StrokeStyle.SQUARE_PEN to "SQUARE_PEN"
        )

        fun <T> safe(name: String, call: () -> T?): T? = try {
            call()
        } catch (t: Throwable) {
            Log.w("StrokeStyleInfo", "$name failed: ${t.javaClass.simpleName} ${t.message ?: ""}")
            null
        }

        return styles.map { (id, name) ->
            val params =
                safe("getStrokeParameters($name)") { base.getStrokeParameters(id) }?.toList()
            val notes = buildString {
                // Provide small hints based on known style behavior if possible.
                // Without vendor docs, we keep notes minimal.
                if (params != null) {
                    append("Parameters count=${params.size}")
                } else {
                    append("No parameters available (SDK returned null or empty).")
                }
            }
            StrokeStyleInfo(
                styleId = id,
                styleName = name,
                parameters = params,
                extraNotes = notes
            )
        }
    }

}