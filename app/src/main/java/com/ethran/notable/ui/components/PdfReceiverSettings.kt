package com.ethran.notable.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ethran.notable.R
import com.ethran.notable.data.datastore.AppSettings
import com.ethran.notable.receiver.PdfReceiver

/** The switch for the PDF receiver and, while it is on, its token and how to reach it. */
@Composable
fun PdfReceiverSettings(
    settings: AppSettings,
    onSettingsChange: (AppSettings) -> Unit,
    token: String?,
    onNewToken: () -> Unit,
) {
    SettingToggleRow(
        label = stringResource(R.string.receive_pdfs),
        value = settings.receivePdfs,
        onToggle = { onSettingsChange(settings.copy(receivePdfs = it)) })
    if (!settings.receivePdfs) return

    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.receive_pdfs_token, token ?: "…"),
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colors.onSurface
        )
        Text(
            text = stringResource(R.string.receive_pdfs_usb, PdfReceiver.PORT),
            style = MaterialTheme.typography.body2,
            color = MaterialTheme.colors.onSurface
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EInkButton(onClick = {
                token ?: return@EInkButton
                context.getSystemService(ClipboardManager::class.java)
                    ?.setPrimaryClip(ClipData.newPlainText("Notable token", token))
            }) { Text(stringResource(R.string.copy_token)) }
            EInkButton(onClick = onNewToken) { Text(stringResource(R.string.new_token)) }
        }
    }
    SettingsDivider()
}
