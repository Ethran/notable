package com.ethran.notable.ui.views

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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.ethran.notable.navigation.NavigationDestination
import com.ethran.notable.ui.viewmodels.DeviceSnapshot
import com.ethran.notable.ui.viewmodels.StrokeStyleInfo
import com.ethran.notable.ui.viewmodels.SystemInformationViewModel
import com.onyx.android.sdk.device.Device


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
    onBack: () -> Unit = {},
    viewModel: SystemInformationViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val context = LocalContext.current
    val device = remember { Device.currentDevice() }

    var info by remember { mutableStateOf<DeviceSnapshot?>(null) }
    var strokeInfo by remember { mutableStateOf<List<StrokeStyleInfo>>(emptyList()) }

    fun refresh() {
        try {
            val snapshot = viewModel.collectDeviceSnapshot(context, device)
            info = snapshot
            strokeInfo = viewModel.buildStrokeStyleInfo(context, device, snapshot)
            Log.d("SystemInformationView", "Refreshed snapshot")
        } catch (t: Throwable) {
            Log.e("SystemInformationView", "Error refreshing snapshot: ${t.message}")
        }
    }


}

@Composable
fun SystemInformationViewContent(
    onBack: () -> Unit = {},
    refresh: () -> Unit = {},
    info: DeviceSnapshot?,
    strokeInfo: List<StrokeStyleInfo>
) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

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
                onBack = { onBack},
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


@Preview(showBackground = true)
@Composable
fun SystemInformationPreview() {

}