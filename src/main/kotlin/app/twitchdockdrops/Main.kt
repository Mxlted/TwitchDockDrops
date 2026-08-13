package app.twitchdockdrops

import com.nathan.twitchdropsminer.android.data.local.LogRepository
import com.nathan.twitchdropsminer.android.data.local.SecureSessionStore
import com.nathan.twitchdropsminer.android.data.local.SettingsRepository
import com.nathan.twitchdropsminer.android.data.network.JvmNetworkStatusProvider
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiClient
import com.nathan.twitchdropsminer.android.runtime.LocalMinerRuntime
import java.nio.file.Files
import java.time.Duration
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

fun main() {
    val environment = AppEnvironment.fromEnvironment()
    Files.createDirectories(environment.dataDirectory)

    val settingsRepository = SettingsRepository(environment.dataDirectory)
    val sessionStore = SecureSessionStore(environment.dataDirectory, environment.sessionKey)
    val logRepository = LogRepository(environment.dataDirectory)
    val httpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .readTimeout(Duration.ofSeconds(30))
        .writeTimeout(Duration.ofSeconds(30))
        .build()
    val networkStatusProvider = JvmNetworkStatusProvider()
    val runtime = LocalMinerRuntime(
        settingsRepository = settingsRepository,
        secureSessionStore = sessionStore,
        logRepository = logRepository,
        twitchApiClient = TwitchApiClient(httpClient),
        networkStatusProvider = networkStatusProvider,
    )

    runBlocking {
        logRepository.load()
        settingsRepository.loadStatus.diagnostic?.let { logRepository.append("WARN", it) }
        runtime.bootstrap()
        sessionStore.loadStatus.diagnostic?.let { logRepository.append("WARN", it) }
        logRepository.append("INFO", "Twitch Dock Drops host started")
    }

    val webServer = WebServer(
        port = environment.port,
        listenHost = environment.listenHost,
        trustedHosts = environment.trustedHosts,
        trustedOrigins = environment.trustedOrigins,
        runtime = runtime,
        settingsRepository = settingsRepository,
        logRepository = logRepository,
    )
    webServer.start()
    println("Twitch Dock Drops ${environment.listenHost}:${environment.port} ready")

    Runtime.getRuntime().addShutdownHook(
        Thread {
            runBlocking { runtime.stopMiningAndJoin() }
            webServer.close()
            networkStatusProvider.close()
            httpClient.connectionPool.evictAll()
            httpClient.dispatcher.executorService.shutdown()
        },
    )

    CountDownLatch(1).await()
}
