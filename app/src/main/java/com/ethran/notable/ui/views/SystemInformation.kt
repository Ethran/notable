package com.ethran.notable.ui.views

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavController
import com.onyx.android.sdk.api.device.epd.EPDMode
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.api.device.epd.UpdateOption
import com.onyx.android.sdk.device.BaseDevice
import com.onyx.android.sdk.device.Device
import com.onyx.android.sdk.pen.style.StrokeStyle
import java.io.File

/**
 * THIS FILE WAS WRITTEN BY AI.
 *
 * Monochrome system information view tailored for e-ink devices.
 * Priority of sections:
 * 1) Basic system info
 * 2) Screen info
 * 3) Writing info (includes Input/Pen and StrokeStyle parameters)
 * 4) Rest (connectivity, storage, fonts, misc)
 *
 * The view is hardened against exceptions. All calls are safe; failures are shown in an "Errors" section.
 * Includes a Refresh button and auto-refresh on focus (Activity resume).
 */
@Composable
fun SystemInformationView(navController: NavController) {
    val context = LocalContext.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val device = remember { Device.currentDevice() }

    var info by remember { mutableStateOf<DeviceSnapshot?>(null) }
    var strokeInfo by remember { mutableStateOf<List<StrokeStyleInfo>>(emptyList()) }

    fun refresh() {
        try {
            val snapshot = collectDeviceSnapshot(context, device)
            info = snapshot
            strokeInfo = buildStrokeStyleInfo(context, device, snapshot)
            Log.d("SystemInformationView", "Refreshed snapshot")
        } catch (t: Throwable) {
            Log.e("SystemInformationView", "Error refreshing snapshot: ${t.message}")
        }
    }

    // Initial load
    LaunchedEffect(Unit) {
        refresh()
    }

    // Refresh on RESUME (focus gain)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            TitleBarSimple(
                title = "System Information",
                onBack = { navController.popBackStack() },
                onRefresh = { refresh() }
            )

            Spacer(modifier = Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .background(Color.White)
            ) {
                Column {
                    // 1) Basic system info
                    SectionTitle("Basic System Info")
                    InfoRow("Manufacturer", Build.MANUFACTURER)
                    InfoRow("Model", Build.MODEL)
                    InfoRow("Build Type", Build.TYPE)
                    InfoRow("SDK", Build.VERSION.SDK_INT.toString())
                    InfoRow("Actual Device Class", device.javaClass.name)
                    InfoRow(
                        "Class Hierarchy",
                        generateSequence<Class<*>>(device.javaClass) { it.superclass }
                            .joinToString(" -> ") { it.simpleName }
                    )
                    InfoRow("System Config Prefix", info?.systemConfigPrefix)
                    InfoRow("Eng Build", info?.isEngBuild.toYesNoNullable())
                    InfoRow("UserDebug Build", info?.isUserDebugBuild.toYesNoNullable())
                    InfoRow("Boot Up Time (ms)", info?.bootUpTimeMs?.toString())
                    InfoRow(
                        "Reset Password Supported",
                        info?.resetPasswordSupported.toYesNoNullable()
                    )
                    InfoRow("Min Password Length", info?.minPasswordLength?.toString())
                    InfoRow("Max Password Length", info?.maxPasswordLength?.toString())

                    DividerMono()

                    // 2) Screen info
                    SectionTitle("Screen Info")
                    InfoRow("EPD Mode", info?.epdMode?.name)
                    InfoRow("System Default Update Mode", info?.systemDefaultUpdateMode?.name)
                    InfoRow("App Scope Refresh Mode", info?.appScopeRefreshMode?.name)
                    InfoRow("In System Fast Mode", info?.inSystemFastMode.toYesNoNullable())
                    InfoRow("In App Fast Mode", info?.inAppFastMode.toYesNoNullable())
                    InfoRow("In Fast Mode", info?.inFastMode.toYesNoNullable())
                    InfoRow("Global Contrast", info?.globalContrast?.toString())
                    InfoRow("Dither Threshold", info?.ditherThreshold?.toString())
                    InfoRow("Color Type", info?.colorType?.toString())
                    InfoRow("Support Night Mode", info?.supportNightMode.toYesNoNullable())
                    InfoRow(
                        "Support Wide Color Gamut",
                        info?.supportWideColorGamut.toYesNoNullable()
                    )

                    DividerMono()

                    // 3) Writing info (Input/Pen + Stroke Styles)
                    SectionTitle("Writing Info")
                    // Input / Pen
                    InfoRow("Touchpad Enabled", info?.touchpadEnabled.toYesNoNullable())
                    InfoRow("Support Active Pen", info?.supportActivePen.toYesNoNullable())
                    InfoRow("Active Pen Enabled", info?.activePenEnabled.toYesNoNullable())
                    InfoRow("Active Pen Battery", info?.activePenBattery?.toString())
                    InfoRow("Active Pen MAC", info?.activePenMac)
                    InfoRow(
                        "Pen UI Visibility Enabled",
                        info?.penUIVisibilityEnabled.toYesNoNullable()
                    )
                    InfoRow("Pen Haptic Enabled", info?.penHapticEnabled.toYesNoNullable())
                    // Touch / EPD geometry
                    InfoRow("Touch Width", info?.touchWidth?.toString())
                    InfoRow("Touch Height", info?.touchHeight?.toString())
                    InfoRow("Max Touch Pressure", info?.maxTouchPressure?.toString())
                    InfoRow("EPD Width", info?.epdWidth?.toString())
                    InfoRow("EPD Height", info?.epdHeight?.toString())
                    InfoRow("isValidPenState", info?.isValidPenState?.toString())


                    // Stroke style details (separate function builds this list)
                    DividerMono()
                    SectionTitle("Stroke Styles")
                    strokeInfo.forEach { s ->
                        InfoRow("Style", s.styleName)
                        InfoRow("Parameters (${s.styleName})", s.parameters?.joinToString())
                        if (!s.extraNotes.isNullOrBlank()) {
                            InfoRow("Notes (${s.styleName})", s.extraNotes)
                        }
                        DividerMono()
                    }

                    DividerMono()

                    // 4) Rest
                    SectionTitle("Connectivity")
                    InfoRow("Has Wi-Fi", info?.hasWifi.toYesNoNullable())
                    InfoRow("Has Bluetooth", info?.hasBluetooth.toYesNoNullable())
                    InfoRow("Has Audio", info?.hasAudio.toYesNoNullable())
                    InfoRow("Fixed Wi-Fi MAC", info?.fixedWifiMac)
                    InfoRow("Bluetooth Address", info?.bluetoothAddress)
                    InfoRow("Encrypted Device ID", info?.encryptedDeviceId)

                    DividerMono()

                    SectionTitle("Light")
                    InfoRow(
                        "Has Front Light Brightness",
                        info?.hasFrontLightBrightness.toYesNoNullable()
                    )
                    InfoRow("Has CTM Brightness", info?.hasCTMBrightness.toYesNoNullable())
                    InfoRow("Light On", info?.isLightOn.toYesNoNullable())
                    InfoRow("Front Light Min", info?.frontLightMin?.toString())
                    InfoRow("Front Light Max", info?.frontLightMax?.toString())
                    InfoRow("Front Light Default", info?.frontLightDefault?.toString())
                    InfoRow("Front Light Config", info?.frontLightConfigValue?.toString())
                    InfoRow("Warm Light Config", info?.warmLightConfigValue?.toString())
                    InfoRow("Cold Light Config", info?.coldLightConfigValue?.toString())
                    InfoRow("Check CTM Available", info?.checkCTM.toYesNoNullable())
                    InfoRow("CTM BR Default", info?.brDefault?.toString())
                    InfoRow("CTM CT Default", info?.ctDefault?.toString())

                    DividerMono()

                    SectionTitle("Wireless Charging")
                    InfoRow(
                        "Support Wireless Charging",
                        info?.supportWirelessCharging.toYesNoNullable()
                    )
                    InfoRow("Wireless Charge Battery", info?.wirelessChargingBattery?.toString())
                    InfoRow("Wireless Charge State", info?.wirelessChargingState?.toString())
                    InfoRow("Wireless Chip ID", info?.wirelessChargingChipId)
                    InfoRow("Wireless Chip Version", info?.wirelessChargingChipVersion)

                    DividerMono()

                    SectionTitle("Multi-Window")
                    InfoRow("Origin Multi-Window", info?.originMultiWindow.toYesNoNullable())
                    InfoRow("Current Multi-Screen Mode", info?.currentMultiScreenMode?.toString())
                    InfoRow(
                        "Limited Multi-Screen Mode",
                        info?.limitedMultiScreenMode.toYesNoNullable()
                    )
                    InfoRow(
                        "Full Function Multi-Screen Mode",
                        info?.fullFunctionMultiScreenMode.toYesNoNullable()
                    )

                    DividerMono()

                    SectionTitle("Storage")
                    InfoRow(
                        "Primary Storage Removable",
                        info?.primaryStorageRemovable.toYesNoNullable()
                    )
                    InfoRow("Storage Root", info?.storageRoot?.path)
                    InfoRow("Removable SD Dirs", info?.removableSdDirs?.joinToString { it.path })
                    InfoRow("USB Storage Present", info?.usbStoragePresent.toYesNoNullable())

                    DividerMono()

                    SectionTitle("System / Fonts")
                    InfoRow("CPU ID", info?.cpuId ?: "-")
                    InfoRow("Support External SD", info?.supportExternalSd.toYesNoNullable())
                    InfoRow("Support Font Hot Reload", info?.supportFontHotReload.toYesNoNullable())
                    info?.systemFontFamilyMap?.entries
                        ?.take(6)
                        ?.forEach { (family, path) ->
                            InfoRow("Font: $family", path)
                        }

                    // Errors section: show any failures for transparency
                    if (!info?.errors.isNullOrEmpty()) {
                        DividerMono()
                        SectionTitle("Errors")
                        info?.errors?.forEach { err ->
                            InfoRow("Error", err, maxLines = 100)
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

/**
 * Simple monochrome title bar similar to Settings.kt.
 * Includes a Refresh button on the right side.
 */
@Composable
private fun TitleBarSimple(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(onClick = onBack) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.Black
            )
        }

        Text(
            text = title,
            style = MaterialTheme.typography.h5.copy(color = Color.Black),
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = onRefresh) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                tint = Color.Black
            )
        }
    }
}

@Composable
private fun DividerMono() {
    androidx.compose.material.Divider(
        color = Color.Black.copy(alpha = 0.12f),
        thickness = 1.dp,
        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)
    )
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.subtitle1.copy(
            color = Color.Black,
            fontWeight = FontWeight.Bold
        ),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    )
    DividerMono()
}

@Composable
private fun InfoRow(label: String, value: String?, maxLines: Int = 3) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.body1.copy(color = Color.Black),
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value ?: "-",
            style = MaterialTheme.typography.body2.copy(color = Color.Black.copy(alpha = 0.7f)),
            modifier = Modifier.weight(1f),
            maxLines = maxLines,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Snapshot for displaying read-only info gathered from BaseDevice (1.3.x).
 * All fields are nullable-safe for display; failures are recorded in [errors].
 */
private data class DeviceSnapshot(
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

/**
 * Exception-safe snapshot collection for BaseDevice (1.3.x).
 */
@Suppress("UsePropertyAccessSyntax")
private fun collectDeviceSnapshot(context: Context, base: BaseDevice): DeviceSnapshot {
    val errors = mutableListOf<String>()
    fun <T> safeNullable(name: String, call: () -> T?): T? = try {
        call()
    } catch (t: Throwable) {
        errors.add("$name failed: ${t.javaClass.simpleName}${t.message?.let { " - $it" } ?: ""}")
        null
    }

    // Screen
    val epdMode = safeNullable("getEpdMode") { base.getEpdMode() }
    val sysUpdateMode =
        safeNullable("getSystemDefaultUpdateMode") { base.getSystemDefaultUpdateMode() }
    val appRefreshMode = safeNullable("getAppScopeRefreshMode") { base.getAppScopeRefreshMode() }
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
        safeNullable("getFrontLightBrightnessMinimum") { base.getFrontLightBrightnessMinimum(context) }
    val flMax =
        safeNullable("getFrontLightBrightnessMaximum") { base.getFrontLightBrightnessMaximum(context) }
    val flDef = safeNullable("getBrDefaultValue") { base.getBrDefaultValue() }
    val flCfg = safeNullable("getFrontLightConfigValue") { base.getFrontLightConfigValue(context) }
    val warmCfg = safeNullable("getWarmLightConfigValue") { base.getWarmLightConfigValue(context) }
    val coldCfg = safeNullable("getColdLightConfigValue") { base.getColdLightConfigValue(context) }
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
    val sysPrefix = safeNullable("getSystemConfigPrefix") { base.getSystemConfigPrefix(context) }
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
    val supportWireless = safeNullable("supportWirelessCharging") { base.supportWirelessCharging() }
    val wirelessBattery =
        safeNullable("getWirelessChargingBatteryLevel") { base.getWirelessChargingBatteryLevel() }
    val wirelessState = safeNullable("getWirelessChargingState") { base.getWirelessChargingState() }
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
        safeNullable("isFullFunctionMultiScreenMode") { base.isFullFunctionMultiScreenMode(context) }

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

/**
 * Separate builder for the Stroke Styles section.
 * Iterates through StrokeStyle constants and fetches parameters via BaseDevice.getStrokeParameters(int),
 * guarding against exceptions. Adds simple notes where useful.
 */
private data class StrokeStyleInfo(
    val styleId: Int,
    val styleName: String,
    val parameters: List<Float>?,
    val extraNotes: String? = null
)

private fun buildStrokeStyleInfo(
    context: Context,
    base: BaseDevice,
    snapshot: DeviceSnapshot?
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
        val params = safe("getStrokeParameters($name)") { base.getStrokeParameters(id) }?.toList()
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

private fun Boolean?.toYesNoNullable(): String? = when (this) {
    null -> null
    true -> "Yes"
    false -> "No"
}