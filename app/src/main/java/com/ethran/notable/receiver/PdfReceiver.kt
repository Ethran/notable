package com.ethran.notable.receiver

import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.ethran.notable.BuildConfig
import com.ethran.notable.data.PageDataManager
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.events.AppEvent
import com.ethran.notable.data.events.AppEventBus
import com.ethran.notable.di.ApplicationScope
import com.ethran.notable.io.getPdfPageCount
import com.ethran.notable.io.getReceivedFilesDir
import com.ethran.notable.navigation.DeepLink
import com.ethran.notable.navigation.DeepLinkResolver
import com.ethran.notable.navigation.DeepLinkResolver.Target
import com.ethran.notable.navigation.LinkRequests
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Receives PDFs pushed from a computer over HTTP ([pdfReceiver] has the endpoints), so that a
 * script can replace a PDF a notebook observes and turn to a page in one request, with an answer.
 *
 * It runs while the setting is on and Notable is in the foreground, and listens on 127.0.0.1 only:
 * a computer reaches it over USB with `adb forward tcp:8765 tcp:8765`. Any app on the tablet can
 * connect to 127.0.0.1 as well, so every write needs the token.
 */
@Singleton
class PdfReceiver @Inject constructor(
    @param:ApplicationContext private val context: Context,
    // Lazy: the receiver is created at app start, before storage access is granted, and these
    // open the database.
    private val deepLinkResolver: dagger.Lazy<DeepLinkResolver>,
    private val pageDataManager: dagger.Lazy<PageDataManager>,
    private val linkRequests: LinkRequests,
    private val appEventBus: AppEventBus,
    @param:ApplicationScope private val appScope: CoroutineScope,
) : DefaultLifecycleObserver, ReceiverHost {
    private val logger = ShipBook.getLogger("PdfReceiver")
    private var running: Job? = null

    private val tokenMutex = Mutex()
    private val _token = MutableStateFlow<String?>(null)

    /** The token a push must send, once loaded; it is made the first time the receiver runs. */
    val token: StateFlow<String?> = _token.asStateFlow()

    // In app-private storage, not in Notable's database: that is in shared storage, where other
    // apps with storage access, and copies made by sync tools, could read it.
    private val prefs by lazy { context.getSharedPreferences("pdf_receiver", Context.MODE_PRIVATE) }

    /** Pushes go one at a time from finding their notebook to showing it. */
    private val openMutex = Mutex()

    override fun onStart(owner: LifecycleOwner) {
        val previous = running
        running = appScope.launch {
            // The previous server must have let go of the port before this one binds it.
            previous?.cancelAndJoin()
            snapshotFlow { GlobalAppSettings.current.receivePdfs }.collectLatest { on ->
                if (on) serve()
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        running?.cancel()
    }

    /** Serves until cancelled. */
    private suspend fun serve() {
        loadToken()
        val server = try {
            // Not cancellable: a server started while this was cancelled would be lost, and keep
            // the port. Cancellation is seen by awaitCancellation below, which stops it.
            withContext(NonCancellable + Dispatchers.IO) {
                receiveDir.mkdirs()
                deleteLeftoverParts(receiveDir)
                embeddedServer(CIO, port = PORT, host = "127.0.0.1") { pdfReceiver(this@PdfReceiver) }
                    .start(wait = false)
            }
        } catch (e: Exception) {
            logger.e("Can't start the PDF receiver", e)
            appEventBus.tryEmit(AppEvent.ActionHint("Can't receive PDFs: ${e.message}", 5000))
            return
        }
        logger.i("Receiving PDFs on port $PORT")
        try {
            awaitCancellation()
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { server.stop(100, 1000) }
            logger.i("Stopped receiving PDFs")
        }
    }

    /** The stored token, made and stored if there is none yet. */
    private suspend fun loadToken(): String = tokenMutex.withLock {
        _token.value ?: withContext(Dispatchers.IO) {
            prefs.getString(TOKEN_KEY, null) ?: newToken().also { store(it) }
        }.also { _token.value = it }
    }

    /** Replaces the token; computers with the old one are refused from now on. */
    suspend fun renewToken() = tokenMutex.withLock {
        val token = newToken()
        withContext(Dispatchers.IO) { store(token) }
        _token.value = token
    }

    private fun store(token: String) = prefs.edit(commit = true) { putString(TOKEN_KEY, token) }

    override val version: String = BuildConfig.VERSION_NAME

    override val receiveDir: File
        get() = getReceivedFilesDir()

    override fun isAuthorized(authorization: String?): Boolean {
        val token = _token.value ?: return false
        return MessageDigest.isEqual(
            authorization.orEmpty().encodeToByteArray(), "Bearer $token".encodeToByteArray()
        )
    }

    override fun isReady(): Boolean = !GlobalAppSettings.current.showWelcome

    override fun countPages(file: File): Int = getPdfPageCount(file.path)

    override fun written(file: File) = pageDataManager.get().backgroundFileWritten(file.path)

    override suspend fun open(link: DeepLink.Pdf): Pushed = openMutex.withLock {
        // Not cancellable: an import cut off when Notable leaves the foreground would leave a
        // notebook with only some of the pages, which later pushes would find and keep.
        withContext(NonCancellable) {
            val found = deepLinkResolver.get().resolve(link)
            val imported = found is Target.AskToImport
            // Turning the receiver on is the consent a link asks for: a received file is followed.
            val target = if (imported) deepLinkResolver.get().import(link) else found
            if (link.page != null || imported) linkRequests.show(target)
            Pushed(target, imported)
        }
    }

    override fun log(message: String) = logger.i(message)

    companion object {
        const val PORT = 8765
        private const val TOKEN_KEY = "token"

        private fun newToken(): String {
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
