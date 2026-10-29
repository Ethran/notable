package com.ethran.notable.navigation

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Where to go next, from intents and from the PDF receiver. Only the latest one waits for the UI. */
@Singleton
class LinkRequests @Inject constructor() {
    sealed interface Request {
        /** A link to resolve, asking before a PDF is imported. */
        data class Open(val link: DeepLink) : Request

        /** A target already resolved, by the PDF receiver. */
        data class Show(val target: DeepLinkResolver.Target) : Request
    }

    private val channel = Channel<Request>(Channel.CONFLATED)

    val requests: Flow<Request> = channel.receiveAsFlow()

    fun open(link: DeepLink) {
        channel.trySend(Request.Open(link))
    }

    fun show(target: DeepLinkResolver.Target) {
        channel.trySend(Request.Show(target))
    }

    /** Forgets what waits, when the user leaves the app: it shouldn't open at the next launch. */
    fun drop() {
        while (channel.tryReceive().isSuccess) Unit
    }
}
