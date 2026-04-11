package com.ethran.notable.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

val LocalSnackContext = staticCompositionLocalOf { SnackState() }

data class SnackConf(
    val id: String = UUID.randomUUID().toString(),
    val text: String? = null,
    val duration: Int? = 60000, // no longer then 60s
    val progress: Float? = null, // from 0.0f to 1.0f
    val content: (@Composable () -> Unit)? = null,
    val actions: List<Pair<String, () -> Unit>>? = null
)


/**
 * State manager for snacks localized to a specific UI scope (e.g. within a Composable).
 * For app-wide snacks, use [SnackDispatcher] injected via Hilt.
 */
class SnackState {
    private val _activeSnacks = MutableStateFlow<List<SnackConf>>(emptyList())
    val activeSnacks = _activeSnacks.asStateFlow()

    fun showOrUpdateSnack(conf: SnackConf) {
        _activeSnacks.update { currentList ->
            val index = currentList.indexOfFirst { it.id == conf.id }
            if (index != -1) {
                val newList = currentList.toMutableList()
                newList[index] = conf
                newList
            } else {
                currentList + conf
            }
        }
    }

    fun removeSnack(id: String) {
        _activeSnacks.update { currentList -> currentList.filterNot { it.id == id } }
    }

    suspend fun displaySnack(conf: SnackConf): suspend () -> Unit {
        showOrUpdateSnack(conf)
        return suspend { removeSnack(conf.id) }
    }

    suspend fun <T> showSnackDuring(
        text: String,
        task: suspend () -> T,
    ): T {
        val dismissSnack =
            displaySnack(SnackConf(text = text, duration = null)) // Ensure it doesn't timeout

        return try {
            task()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            withContext(NonCancellable) {
                showOrUpdateSnack(SnackConf(text = "Error: ${e.message}", duration = 3000))
            }
            throw e
        } finally {
            withContext(NonCancellable) {
                dismissSnack()
            }
        }
    }

    suspend fun runWithSnack(
        textDuring: String,
        resultDurationMs: Int = 2000,
        block: suspend () -> String
    ): String {
        val message = showSnackDuring(text = textDuring) { block() }
        if (resultDurationMs > 0)
            showOrUpdateSnack(SnackConf(text = message, duration = resultDurationMs))
        return message
    }
}

@Composable
fun SnackBar(state: SnackState, dispatcher: SnackDispatcher) {
    val globalSnacks by dispatcher.activeSnacks.collectAsState()
    val localSnacks by state.activeSnacks.collectAsState()

    // Combine both states. DistinctBy ensures no weird duplicate ID crashes.
    val allSnacks = (globalSnacks + localSnacks).distinctBy { it.id }

    Column(
        Modifier
            .fillMaxHeight()
            .padding(3.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        allSnacks.forEach { snack ->
            key(snack.id) {
                SnackItem(
                    snack = snack,
                    onDismiss = { id ->
                        state.removeSnack(id)
                        dispatcher.removeSnack(id)
                    }
                )
            }
        }
    }
}

@Composable
private fun SnackItem(snack: SnackConf, onDismiss: (String) -> Unit) {
    // This effect starts when the snack appears. It resets if the duration changes.
    LaunchedEffect(snack.id, snack.duration) {
        if (snack.duration != null && snack.duration > 0) {
            delay(snack.duration.toLong())
            onDismiss(snack.id)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(3.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .background(Color.Black)
                .padding(15.dp, 5.dp),
            contentAlignment = Alignment.Center
        ) {
            if (snack.text != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = snack.text, color = Color.White)

                    if (!snack.actions.isNullOrEmpty()) {
                        snack.actions.forEach { action ->
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = action.first,
                                color = Color.White,
                                modifier = Modifier.noRippleClickable { action.second() }
                            )
                        }
                    }
                }
            } else {
                snack.content?.invoke()
            }
        }
    }
}
