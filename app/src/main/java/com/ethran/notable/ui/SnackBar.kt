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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.shipbook.shipbooksdk.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException

val LocalSnackContext = staticCompositionLocalOf { SnackState() }

data class SnackConf(
    val id: String = UUID.randomUUID().toString(),
    val text: String? = null,
    val duration: Int? = null,
    val content: (@Composable () -> Unit)? = null,
    val actions: List<Pair<String, () -> Unit>>? = null
)

class SnackState {
    val snackFlow = MutableSharedFlow<SnackConf?>()
    val cancelSnackFlow = MutableSharedFlow<String?>()
    suspend fun displaySnack(conf: SnackConf): suspend () -> Unit {
        snackFlow.emit(conf)
        return suspend {
            Log.d("SnackState", "Removing snack ${conf.id}")
            removeSnack(conf.id)
        }
    }

    // TODO: check if this is a good approach,
    // this does work, but I have doubts if it is a proper way for doing it
    // Register Observers for Global Actions
    companion object {
        val globalSnackFlow = MutableSharedFlow<SnackConf>(extraBufferCapacity = 10)
        val cancelGlobalSnack = MutableSharedFlow<String>(extraBufferCapacity = 10)
        fun logAndShowError(
            reason: String,
            message: String,
            logger: (String, String) -> Unit = Log::e
        ) {
            logger(reason, message)
            // It will succeed if the buffer is not full.
            val emitted = globalSnackFlow.tryEmit(SnackConf(text = message, duration = 3000))
            if (!emitted) {
                logger("SnackState", "Failed to emit snackbar, buffer is full.")
            }
        }
    }

    fun registerGlobalSnackObserver() {
        CoroutineScope(Dispatchers.Main).launch {
            globalSnackFlow.collect {
                displaySnack(it)
            }
        }
    }

    fun registerCancelGlobalSnackObserver() {
        CoroutineScope(Dispatchers.Main).launch {
            cancelGlobalSnack.collect {
                removeSnack(it)
            }
        }
    }


    private suspend fun removeSnack(id: String) {
        cancelSnackFlow.emit(id)
    }

    /**
     * Shows a snackbar that remains visible until the provided task completes
     * @param text Text to display in the snackbar
     * @param task Suspending task to execute while snackbar is visible
     * @return Result of the task
     * @throws Exception If the task throws an exception, it will be displayed as an error snackbar, and the exception will be rethrown
     */
    suspend fun <T> showSnackDuring(
        text: String,
        task: suspend () -> T,
    ): T {
        val dismissSnack = displaySnack(SnackConf(text = text))
        return try {
            val result = task()
            dismissSnack()
            result
        } catch (e: CancellationException) {
            Log.d("showSnackDuring", "Task cancelled")
            withContext(NonCancellable) {
                dismissSnack()
            }
            throw e
        } catch (e: Exception) {
            Log.e("showSnackDuring", "Task failed", e)
            withContext(NonCancellable) {
                dismissSnack()
                displaySnack(SnackConf(text = "Error: ${e.message}", duration = 3000))
            }
            throw e
        }
    }

    /**
     * Runs [block] while showing a "working" snack, then shows the resulting message as a new snack.
     * Returns the result message from [block].
     */
    suspend fun runWithSnack(
        textDuring: String,
        resultDurationMs: Int = 2000,
        block: suspend () -> String
    ): String {
        val message = showSnackDuring(text = textDuring) { block() }
        displaySnack(SnackConf(text = message, duration = resultDurationMs))
        return message
    }

}

@Composable
fun SnackBar(state: SnackState) {
    val snacks = remember {
        mutableStateListOf<SnackConf>()
    }

    fun getSnacks() = snacks

    LaunchedEffect(Unit) {
        launch {
            state.cancelSnackFlow.collect { snackId ->
                getSnacks().removeIf { it.id == snackId }
            }
        }
        launch {
            state.snackFlow.collect { snack ->
                if (snack != null) {
                    getSnacks().add(snack)
                    if (snack.duration != null) {
                        launch {
                            delay(snack.duration.toLong())
                            getSnacks().removeIf { it.id == snack.id }
                        }
                    }
                }
            }
        }
    }

    Column(
        Modifier
            .fillMaxHeight()
            .padding(3.dp), verticalArrangement = Arrangement.Bottom
    ) {
        snacks.map { snack ->
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
                        Row {
                            Text(text = snack.text, color = Color.White)
                            if (snack.actions != null && snack.actions.isEmpty().not()) {
                                snack.actions.map {
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = it.first,
                                        color = Color.White,
                                        modifier = Modifier.noRippleClickable { it.second() })
                                }
                            }
                        }

                    } else snack.content?.let { content ->
                        content()
                    }
                }
            }
        }
    }
}

fun showHint(
    text: String,
    scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    duration: Int = 3000
) {
    scope.launch {
        SnackState.globalSnackFlow.emit(
            SnackConf(
                text = text,
                duration = duration,
            )
        )
    }
}