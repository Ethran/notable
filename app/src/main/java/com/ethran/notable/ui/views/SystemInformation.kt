package com.ethran.notable.ui.views

import android.os.Build
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.viewmodels.DeviceSnapshot
import com.ethran.notable.ui.viewmodels.StrokeStyleInfo
import com.ethran.notable.ui.viewmodels.SystemInformationViewModel
import java.io.File


object SystemInformationDestination : NavigationDestination {
    override val route = "SystemInformationView"
}


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
fun SystemInformationView(
    onBack: () -> Unit, viewModel: SystemInformationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Auto-refresh on Resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SystemInformationContent(
        uiState = uiState,
        onBack = onBack,
        onRefresh = { viewModel.refresh(context) },
    )
}

@Composable
fun SystemInformationContent(
    uiState: SystemInformationViewModel.SystemInfoUiState?,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = Color.White) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
            TitleBarSimple(
                title = "System Information", onBack = onBack, onRefresh = onRefresh
            )

            if (uiState == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Loading device data...")
                }
            } else {
                val info = uiState.snapshot
                val strokeInfo = uiState.strokeInfo

                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // 0) DeviceInfoUtil
                    SectionTitle("Device Info Util")
                    InfoRow("Device Info", info.deviceInfoStr, maxLines = 10)
                    InfoRow("Kernel Info", info.kernelInfo, maxLines = 10)
                    InfoRow("EMTP Info", info.emtpInfo)
                    InfoRow("VCom Info", info.vcomInfo)
                    InfoRow("Is Onyx Device", info.isOnyxDevice.toYesNo())
                    InfoRow("Is Color Device", info.isColorDevice.toYesNo())
                    InfoRow("CPU Serial", info.cpuSerial)
                    InfoRow("Resolution", "${info.resolutionX ?: "?"} x ${info.resolutionY ?: "?"}")

                    DividerMono()

                    // 1) Basic system info
                    SectionTitle("Basic System Info")
                    InfoRow("Manufacturer", Build.MANUFACTURER)
                    InfoRow("Model", Build.MODEL)
                    InfoRow("Build Type", Build.TYPE)
                    InfoRow("SDK", Build.VERSION.SDK_INT.toString())
                    InfoRow("Actual Device Class", info.actualDeviceClass)
                    InfoRow(
                        "Class Hierarchy", info.deviceClassHierarchy
                    )
                    InfoRow("System Config Prefix", info.systemConfigPrefix)
                    InfoRow("Eng Build", info.isEngBuild.toYesNo())
                    InfoRow("UserDebug Build", info.isUserDebugBuild.toYesNo())
                    InfoRow("Boot Up Time (ms)", info.bootUpTimeMs?.toString())
                    InfoRow(
                        "Reset Password Supported", info.resetPasswordSupported.toYesNo()
                    )
                    InfoRow("Min Password Length", info.minPasswordLength?.toString())
                    InfoRow("Max Password Length", info.maxPasswordLength?.toString())

                    DividerMono()

                    // 2) Screen info
                    SectionTitle("Screen Info")
                    InfoRow("EPD Mode", info.epdMode?.name)
                    InfoRow("System Default Update Mode", info.systemDefaultUpdateMode?.name)
                    InfoRow("App Scope Refresh Mode", info.appScopeRefreshMode?.name)
                    InfoRow("In System Fast Mode", info.inSystemFastMode.toYesNo())
                    InfoRow("In App Fast Mode", info.inAppFastMode.toYesNo())
                    InfoRow("In Fast Mode", info.inFastMode.toYesNo())
                    InfoRow("Global Contrast", info.globalContrast?.toString())
                    InfoRow("Dither Threshold", info.ditherThreshold?.toString())
                    InfoRow("Color Type", info.colorType?.toString())
                    InfoRow("Support Night Mode", info.supportNightMode.toYesNo())
                    InfoRow(
                        "Support Wide Color Gamut", info.supportWideColorGamut.toYesNo()
                    )

                    DividerMono()

                    // 3) Writing info (Input/Pen + Stroke Styles)
                    SectionTitle("Writing Info")
                    // Input / Pen
                    InfoRow("Touchpad Enabled", info.touchpadEnabled.toYesNo())
                    InfoRow("Support Active Pen", info.supportActivePen.toYesNo())
                    InfoRow("Active Pen Enabled", info.activePenEnabled.toYesNo())
                    InfoRow("Active Pen Battery", info.activePenBattery?.toString())
                    InfoRow("Active Pen MAC", info.activePenMac)
                    InfoRow(
                        "Pen UI Visibility Enabled", info.penUIVisibilityEnabled.toYesNo()
                    )
                    InfoRow("Pen Haptic Enabled", info.penHapticEnabled.toYesNo())
                    // Touch / EPD geometry
                    InfoRow("Touch Width", info.touchWidth?.toString())
                    InfoRow("Touch Height", info.touchHeight?.toString())
                    InfoRow("Max Touch Pressure", info.maxTouchPressure?.toString())
                    InfoRow("EPD Width", info.epdWidth?.toString())
                    InfoRow("EPD Height", info.epdHeight?.toString())
                    InfoRow("isValidPenState", info.isValidPenState?.toString())


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
                    InfoRow("Has Wi-Fi", info.hasWifi.toYesNo())
                    InfoRow("Has Bluetooth", info.hasBluetooth.toYesNo())
                    InfoRow("Has Audio", info.hasAudio.toYesNo())
                    InfoRow("Fixed Wi-Fi MAC", info.fixedWifiMac)
                    InfoRow("Bluetooth Address", info.bluetoothAddress)
                    InfoRow("Encrypted Device ID", info.encryptedDeviceId)

                    DividerMono()

                    SectionTitle("Light")
                    InfoRow(
                        "Has Front Light Brightness", info.hasFrontLightBrightness.toYesNo()
                    )
                    InfoRow("Has CTM Brightness", info.hasCTMBrightness.toYesNo())
                    InfoRow("Light On", info.isLightOn.toYesNo())
                    InfoRow("Front Light Min", info.frontLightMin?.toString())
                    InfoRow("Front Light Max", info.frontLightMax?.toString())
                    InfoRow("Front Light Default", info.frontLightDefault?.toString())
                    InfoRow("Front Light Config", info.frontLightConfigValue?.toString())
                    InfoRow("Warm Light Config", info.warmLightConfigValue?.toString())
                    InfoRow("Cold Light Config", info.coldLightConfigValue?.toString())
                    InfoRow("Check CTM Available", info.checkCTM.toYesNo())
                    InfoRow("CTM BR Default", info.brDefault?.toString())
                    InfoRow("CTM CT Default", info.ctDefault?.toString())

                    DividerMono()

                    SectionTitle("Wireless Charging")
                    InfoRow(
                        "Support Wireless Charging", info.supportWirelessCharging.toYesNo()
                    )
                    InfoRow("Wireless Charge Battery", info.wirelessChargingBattery?.toString())
                    InfoRow("Wireless Charge State", info.wirelessChargingState?.toString())
                    InfoRow("Wireless Chip ID", info.wirelessChargingChipId)
                    InfoRow("Wireless Chip Version", info.wirelessChargingChipVersion)

                    DividerMono()

                    SectionTitle("Multi-Window")
                    InfoRow("Origin Multi-Window", info.originMultiWindow.toYesNo())
                    InfoRow("Current Multi-Screen Mode", info.currentMultiScreenMode?.toString())
                    InfoRow(
                        "Limited Multi-Screen Mode", info.limitedMultiScreenMode.toYesNo()
                    )
                    InfoRow(
                        "Full Function Multi-Screen Mode",
                        info.fullFunctionMultiScreenMode.toYesNo()
                    )

                    DividerMono()

                    SectionTitle("Storage")
                    InfoRow(
                        "Primary Storage Removable", info.primaryStorageRemovable.toYesNo()
                    )
                    InfoRow("Storage Root", info.storageRoot?.path)
                    InfoRow("Removable SD Dirs", info.removableSdDirs?.joinToString { it.path })
                    InfoRow("USB Storage Present", info.usbStoragePresent.toYesNo())

                    DividerMono()

                    SectionTitle("System / Fonts")
                    InfoRow("CPU ID", info.cpuId ?: "-")
                    InfoRow("Support External SD", info.supportExternalSd.toYesNo())
                    InfoRow("Support Font Hot Reload", info.supportFontHotReload.toYesNo())
                    info.systemFontFamilyMap?.entries
                        ?.take(6)
                        ?.forEach { (family, path) ->
                            InfoRow("Font: $family", path)
                        }

                    // Errors section: show any failures for transparency
                    if (info.errors.isNotEmpty()) {
                        DividerMono()
                        SectionTitle("Errors")
                        info.errors.forEach { err ->
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

@Preview(showBackground = true, widthDp = 600)
@Composable
fun SystemInformationPreview() {
    val mockSnapshot = DeviceSnapshot(
        deviceInfoStr = "Mock OS Version",
        kernelInfo = "Mock Kernel",
        emtpInfo = "Mock EMTP",
        vcomInfo = "1.5 V",
        cpuSerial = "123456",
        resolutionX = 1404,
        resolutionY = 1872,
        isOnyxDevice = true,
        isColorDevice = false,

        // Screen
        epdMode = null,
        systemDefaultUpdateMode = null,
        appScopeRefreshMode = null,
        inSystemFastMode = true,
        inAppFastMode = false,
        inFastMode = true,
        globalContrast = 10,
        ditherThreshold = 5,
        colorType = 1,
        supportNightMode = true,
        supportWideColorGamut = false,

        // Writing/Input
        touchpadEnabled = true,
        supportActivePen = true,
        activePenEnabled = true,
        activePenBattery = 85,
        activePenMac = "00:11:22:33:44:55",
        penUIVisibilityEnabled = true,
        penHapticEnabled = false,
        touchWidth = 1080f,
        touchHeight = 1440f,
        maxTouchPressure = 1.0f,
        epdWidth = 1072f,
        epdHeight = 1448f,
        isValidPenState = true,

        // Light
        hasFrontLightBrightness = true,
        hasCTMBrightness = true,
        isLightOn = true,
        frontLightMin = 0,
        frontLightMax = 100,
        frontLightDefault = 50,
        frontLightConfigValue = 60,
        warmLightConfigValue = 40,
        coldLightConfigValue = 30,
        checkCTM = true,
        brDefault = 50,
        ctDefault = 45,

        // Basic system
        powerSavedMode = false,
        hallControlEnabled = true,
        bootUpTimeMs = 15000L,
        isEngBuild = false,
        isUserDebugBuild = false,
        resetPasswordSupported = true,
        systemConfigPrefix = "sys_",
        minPasswordLength = 6,
        maxPasswordLength = 16,

        // Connectivity
        hasAudio = true,
        hasWifi = true,
        hasBluetooth = true,
        fixedWifiMac = "AA:BB:CC:DD:EE:FF",
        bluetoothAddress = "11:22:33:44:55:66",
        encryptedDeviceId = "encrypted-device-id-123",

        // Wireless charging
        supportWirelessCharging = false,
        wirelessChargingBattery = null,
        wirelessChargingState = null,
        wirelessChargingChipId = null,
        wirelessChargingChipVersion = null,

        // Multi-Window
        originMultiWindow = true,
        currentMultiScreenMode = 1,
        limitedMultiScreenMode = false,
        fullFunctionMultiScreenMode = true,

        // Storage
        primaryStorageRemovable = false,
        storageRoot = File("/storage/emulated/0"),
        removableSdDirs = listOf(File("/storage/sdcard1")),
        usbStoragePresent = false,

        // Fonts / Misc
        cpuId = "CPU123456789",
        supportExternalSd = true,
        supportFontHotReload = false,
        systemFontFamilyMap = mapOf(
            "sans-serif" to "Roboto", "serif" to "Noto Serif"
        ),

        errors = emptyList()
    )

    SystemInformationContent(
        uiState = SystemInformationViewModel.SystemInfoUiState(
            snapshot = mockSnapshot,
            strokeInfo = listOf(StrokeStyleInfo(1, "PENCIL", listOf(1f, 2f)))
        ),
        onBack = {},
        onRefresh = {},
    )
}


private fun Boolean?.toYesNo(): String? = when (this) {
    null -> "--"
    true -> "Yes"
    false -> "No"
}
