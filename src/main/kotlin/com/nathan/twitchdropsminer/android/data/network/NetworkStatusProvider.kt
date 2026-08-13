package com.nathan.twitchdropsminer.android.data.network

import java.net.InetSocketAddress
import java.net.Socket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface NetworkStatusProvider {
    val isOnline: StateFlow<Boolean>
    val advisoryOnly: Boolean
        get() = false

    suspend fun awaitOnline() {
        isOnline.filter { it }.first()
    }
}

class JvmNetworkStatusProvider : NetworkStatusProvider, AutoCloseable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutableOnline = MutableStateFlow(false)

    override val isOnline: StateFlow<Boolean> = mutableOnline
    override val advisoryOnly: Boolean = true

    init {
        scope.launch {
            while (isActive) {
                mutableOnline.value = withContext(Dispatchers.IO) { canReachTwitch() }
                delay(if (mutableOnline.value) 15_000 else 5_000)
            }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private fun canReachTwitch(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("gql.twitch.tv", 443), 3_000)
        }
        true
    }.getOrDefault(false)
}
