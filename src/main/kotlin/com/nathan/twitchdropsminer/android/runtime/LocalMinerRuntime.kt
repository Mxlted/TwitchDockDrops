package com.nathan.twitchdropsminer.android.runtime

import com.nathan.twitchdropsminer.android.data.local.LogRepository
import com.nathan.twitchdropsminer.android.data.local.SecureSessionStore
import com.nathan.twitchdropsminer.android.data.local.SettingsRepository
import com.nathan.twitchdropsminer.android.data.model.AppSettings
import com.nathan.twitchdropsminer.android.data.model.AutoModePriority
import com.nathan.twitchdropsminer.android.data.model.Campaign
import com.nathan.twitchdropsminer.android.data.model.CampaignDrop
import com.nathan.twitchdropsminer.android.data.model.Channel
import com.nathan.twitchdropsminer.android.data.model.LoginSession
import com.nathan.twitchdropsminer.android.data.model.LoginState
import com.nathan.twitchdropsminer.android.data.model.RuntimeActivity
import com.nathan.twitchdropsminer.android.data.model.RuntimePhase
import com.nathan.twitchdropsminer.android.data.model.RuntimeSnapshot
import com.nathan.twitchdropsminer.android.data.model.StoredTwitchSession
import com.nathan.twitchdropsminer.android.data.model.inEarningOrder
import com.nathan.twitchdropsminer.android.data.network.NetworkStatusProvider
import com.nathan.twitchdropsminer.android.data.twitch.CurrentDropProgress
import com.nathan.twitchdropsminer.android.data.twitch.DeviceTokenPollResult
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApi
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiErrorType
import com.nathan.twitchdropsminer.android.data.twitch.TwitchApiException
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel as CoroutineChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

private const val UnlinkedProgressCheckIntervals = 3L
private const val MinimumConfirmedProgressObservations = 3
private const val RejectedWatchFailureThreshold = 3
private const val MaxRuntimeActivityEntries = 100
private val MinUnlinkedProgressCheckDelay: Duration = Duration.ofMinutes(2)
private val MaxUnlinkedProgressCheckDelay: Duration = Duration.ofMinutes(5)
private val FailedChannelRetryDelay: Duration = Duration.ofMinutes(15)
private val HigherPriorityChannelCheckInterval: Duration = Duration.ofMinutes(2)
private val MinLinkedProgressCheckDelay: Duration = Duration.ofMinutes(5)
private val MaxLinkedProgressCheckDelay: Duration = Duration.ofMinutes(10)

class LocalMinerRuntime(
    private val settingsRepository: SettingsRepository,
    private val secureSessionStore: SecureSessionStore,
    private val logRepository: LogRepository,
    private val twitchApiClient: TwitchApi,
    private val networkStatusProvider: NetworkStatusProvider,
    claimFailureCooldown: Duration = DefaultClaimFailureCooldown,
    private val higherPriorityCheckInterval: Duration = HigherPriorityChannelCheckInterval,
    private val clock: () -> Instant = Instant::now,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val runtimeCommands = CoroutineChannel<RuntimeCommand>(capacity = 32)
    private val pendingCommandLock = Any()
    private val pendingCommandKeys = mutableSetOf<String>()
    private val _snapshot = MutableStateFlow(
        RuntimeSnapshot(
            phase = RuntimePhase.Stopped,
            currentTask = "Local miner stopped",
            progressSummary = "No local inventory loaded",
        ),
    )

    private var miningJob: Job? = null
    private var authJob: Job? = null
    private var inventoryRefreshJob: Job? = null
    @Volatile private var sessionGeneration = 0L
    @Volatile private var authRunGeneration = 0L
    @Volatile private var miningRunGeneration = 0L
    @Volatile private var inventoryRefreshRunGeneration = 0L
    private var dropsClaimedThisSession = 0
    private val dropClaimHandler = DropClaimHandler(
        twitchApiClient,
        ClaimAttemptTracker(claimFailureCooldown, clock),
    )
    private val failedChannelSkips = mutableMapOf<Long, Instant>()
    private val channelControlRequests = MutableStateFlow(ChannelControlRequest())
    private val inventoryRefreshRequests = MutableStateFlow(0L)
    private var lastLoggedExcludedCampaignIds: Set<String> = emptySet()
    private var waitingForNetwork = false

    init {
        scope.launch {
            processRuntimeCommands()
        }
    }

    val snapshot: StateFlow<RuntimeSnapshot> = _snapshot

    private fun now(): Instant = clock()

    suspend fun bootstrap() {
        val session = secureSessionStore.twitchSession()
        _snapshot.update {
            it.copy(
                account = if (session == null) {
                    LoginSession(LoginState.LoggedOut, "Twitch login required")
                } else {
                    LoginSession(
                        state = LoginState.LoggedIn,
                        statusText = "Stored Twitch session",
                        userId = session.userId,
                    )
                },
                lastUpdate = now(),
            )
        }
        if (session != null) {
            refreshInventory()
        }
    }

    private suspend fun processRuntimeCommands() {
        for (command in runtimeCommands) {
            try {
                when (command) {
                    RuntimeCommand.StartAuthentication -> handleStartAuthentication(replace = false)
                    RuntimeCommand.ReplaceAuthentication -> handleStartAuthentication(replace = true)
                    is RuntimeCommand.AuthenticationSucceeded -> handleAuthenticationSucceeded(command)
                    RuntimeCommand.StartMining -> handleStartMining()
                    is RuntimeCommand.StopMining -> handleStopMining(command)
                    RuntimeCommand.RefreshInventory -> handleRefreshInventory()
                    is RuntimeCommand.ResetSession -> handleResetSession(command)
                    is RuntimeCommand.ExpireSession -> handleExpiredSession(command)
                }
            } catch (error: CancellationException) {
                command.completeExceptionally(error)
                throw error
            } catch (error: Throwable) {
                command.completeExceptionally(error)
                runCatching {
                    reportUnexpectedCommandFailure(command, error)
                }
            } finally {
                command.coalescingKey?.let { key ->
                    synchronized(pendingCommandLock) { pendingCommandKeys.remove(key) }
                }
            }
        }
    }

    private suspend fun handleStartAuthentication(replace: Boolean) {
        if (_snapshot.value.account.isAuthenticated) {
            appendActivity(RuntimePhase.Idle, "Twitch is already connected")
            return
        }
        val currentAuthorization = _snapshot.value.account
        if (
            !replace &&
            authJob?.isActive == true &&
            (currentAuthorization.expiresAt == null || now().isBefore(currentAuthorization.expiresAt))
        ) {
            return
        }
        authRunGeneration += 1L
        val authGeneration = authRunGeneration
        authJob?.cancel()
        authJob = null
        miningRunGeneration += 1L
        miningJob?.cancel()
        miningJob = null
        inventoryRefreshRunGeneration += 1L
        inventoryRefreshJob?.cancel()
        inventoryRefreshJob = null
        waitingForNetwork = false
        val existingDeviceId = secureSessionStore.twitchSession()?.deviceId
        sessionGeneration += 1L
        secureSessionStore.clear()
        _snapshot.update {
            it.copy(
                phase = RuntimePhase.Connecting,
                account = LoginSession(LoginState.LoginRequired, "Preparing Twitch device login"),
                currentTask = "Preparing Twitch device login",
                currentChannel = null,
                activeCampaign = null,
                activeDrop = null,
                miningActive = false,
                channelSearchInProgress = false,
                error = null,
                lastUpdate = now(),
            )
        }
        val deviceId = existingDeviceId ?: twitchApiClient.newDeviceId()
        val job = scope.launch(
            context = RuntimeOperationGuard { isCurrentAuthentication(authGeneration) },
            start = CoroutineStart.LAZY,
        ) {
            runAuthentication(authGeneration, deviceId)
        }
        authJob = job
        job.start()
    }

    private suspend fun handleAuthenticationSucceeded(
        command: RuntimeCommand.AuthenticationSucceeded,
    ) {
        if (command.authGeneration != authRunGeneration) {
            return
        }
        sessionGeneration += 1L
        secureSessionStore.saveTwitchSession(command.session)
        authJob = null
        _snapshot.update {
            it.copy(
                phase = RuntimePhase.Idle,
                account = LoginSession(
                    state = LoginState.LoggedIn,
                    statusText = "Logged in with Twitch",
                    userId = command.session.userId,
                ),
                currentTask = "Twitch login complete",
                progressSummary = "Loading drops inventory.",
                campaigns = emptyList(),
                channels = emptyList(),
                currentChannel = null,
                activeCampaign = null,
                activeDrop = null,
                error = null,
                lastUpdate = now(),
            )
        }
        appendActivity(RuntimePhase.Idle, "Twitch session saved securely")
        enqueueCoalesced(RuntimeCommand.RefreshInventory)
    }

    private suspend fun handleStartMining() {
        if (miningJob?.isActive == true) {
            return
        }
        val session = secureSessionStore.twitchSession()
        if (session == null) {
            if (authJob?.isActive == true) {
                return
            }
            updateSnapshot(RuntimePhase.Authenticating, "Twitch login required") {
                it.copy(
                    account = LoginSession(LoginState.LoginRequired, "Start Twitch device login"),
                    progressSummary = "Authenticate before the local miner can call Twitch.",
                    miningActive = false,
                    error = "Twitch login required.",
                )
            }
            appendActivity(RuntimePhase.Authenticating, "Login required before mining")
            return
        }
        inventoryRefreshRunGeneration += 1L
        inventoryRefreshJob?.cancel()
        inventoryRefreshJob = null
        miningRunGeneration += 1L
        val runGeneration = miningRunGeneration
        val expectedSessionGeneration = sessionGeneration
        dropClaimHandler.clearAttempts()
        failedChannelSkips.clear()
        lastLoggedExcludedCampaignIds = emptySet()
        waitingForNetwork = false
        _snapshot.update { it.copy(miningActive = true, error = null) }
        val job = scope.launch(
            context = RuntimeOperationGuard {
                isCurrentMiningRun(expectedSessionGeneration, runGeneration)
            },
            start = CoroutineStart.LAZY,
        ) {
            appendActivity(RuntimePhase.LoadingInventory, "Local miner started")
            try {
                runMiningLoop(session, expectedSessionGeneration, runGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: TwitchApiException) {
                if (error.type == TwitchApiErrorType.InvalidToken) {
                    runtimeCommands.trySend(
                        RuntimeCommand.ExpireSession(expectedSessionGeneration, error.message),
                    )
                } else if (isCurrentMiningRun(expectedSessionGeneration, runGeneration)) {
                    reportUnexpectedMinerFailure(error)
                }
            } catch (error: Throwable) {
                if (isCurrentMiningRun(expectedSessionGeneration, runGeneration)) {
                    reportUnexpectedMinerFailure(error)
                }
            } finally {
                if (isCurrentMiningRun(expectedSessionGeneration, runGeneration)) {
                    _snapshot.update { it.copy(miningActive = false) }
                }
            }
        }
        miningJob = job
        job.start()
    }

    private suspend fun handleStopMining(command: RuntimeCommand.StopMining) {
        miningRunGeneration += 1L
        val job = miningJob
        miningJob = null
        job?.cancel()
        if (command.awaitCompletion) {
            job?.join()
        }
        updateSnapshot(RuntimePhase.Stopped, "Local miner stopped") {
            it.copy(
                currentChannel = null,
                activeCampaign = null,
                activeDrop = null,
                miningActive = false,
                channelSearchInProgress = false,
                error = null,
            )
        }
        appendActivity(RuntimePhase.Stopped, "Local miner stopped")
        command.completed?.complete(Unit)
    }

    private suspend fun handleRefreshInventory() {
        if (miningJob?.isActive == true) {
            inventoryRefreshRequests.update { it + 1L }
            _snapshot.update {
                it.copy(
                    currentTask = "Inventory refresh requested",
                    lastUpdate = now(),
                    error = null,
                )
            }
            return
        }
        if (inventoryRefreshJob?.isActive == true) {
            return
        }
        val session = secureSessionStore.twitchSession()
        if (session == null) {
            if (authJob?.isActive == true) {
                return
            }
            updateSnapshot(RuntimePhase.Authenticating, "Twitch login required") {
                it.copy(
                    account = LoginSession(LoginState.LoginRequired, "Start Twitch device login"),
                    error = "Twitch login required.",
                )
            }
            return
        }
        inventoryRefreshRunGeneration += 1L
        val refreshGeneration = inventoryRefreshRunGeneration
        val expectedSessionGeneration = sessionGeneration
        val job = scope.launch(
            context = RuntimeOperationGuard {
                isCurrentInventoryRefresh(expectedSessionGeneration, refreshGeneration)
            },
            start = CoroutineStart.LAZY,
        ) {
            try {
                refreshInventoryOnce(session, expectedSessionGeneration, refreshGeneration)
            } catch (error: CancellationException) {
                throw error
            } catch (error: TwitchApiException) {
                if (error.type == TwitchApiErrorType.InvalidToken) {
                    runtimeCommands.trySend(
                        RuntimeCommand.ExpireSession(expectedSessionGeneration, error.message),
                    )
                } else if (isCurrentInventoryRefresh(expectedSessionGeneration, refreshGeneration)) {
                    reportInventoryRefreshFailure(error)
                }
            } catch (error: Throwable) {
                if (isCurrentInventoryRefresh(expectedSessionGeneration, refreshGeneration)) {
                    reportInventoryRefreshFailure(error)
                }
            }
        }
        inventoryRefreshJob = job
        job.start()
    }

    private suspend fun handleResetSession(command: RuntimeCommand.ResetSession) {
        sessionGeneration += 1L
        authRunGeneration += 1L
        miningRunGeneration += 1L
        inventoryRefreshRunGeneration += 1L
        authJob?.cancel()
        miningJob?.cancel()
        inventoryRefreshJob?.cancel()
        authJob = null
        miningJob = null
        inventoryRefreshJob = null
        secureSessionStore.clear()
        settingsRepository.resetSessionSettings()
        dropsClaimedThisSession = 0
        dropClaimHandler.clearAttempts()
        failedChannelSkips.clear()
        waitingForNetwork = false
        logRepository.append("INFO", "Twitch session reset")
        _snapshot.value = RuntimeSnapshot(
            phase = RuntimePhase.Stopped,
            account = LoginSession(LoginState.LoggedOut, "Twitch login required"),
            currentTask = "Session reset",
            progressSummary = "Local session and selections cleared",
            lastUpdate = now(),
        )
        command.completed?.complete(Unit)
    }

    private suspend fun handleExpiredSession(command: RuntimeCommand.ExpireSession) {
        if (command.expectedSessionGeneration != sessionGeneration) {
            return
        }
        sessionGeneration += 1L
        authRunGeneration += 1L
        miningRunGeneration += 1L
        inventoryRefreshRunGeneration += 1L
        authJob?.cancel()
        miningJob?.cancel()
        inventoryRefreshJob?.cancel()
        authJob = null
        miningJob = null
        inventoryRefreshJob = null
        waitingForNetwork = false
        secureSessionStore.clear()
        appendActivity(RuntimePhase.Authenticating, "Stored Twitch session expired")
        updateSnapshot(RuntimePhase.Authenticating, "Stored Twitch session needs renewal") {
            it.copy(
                account = LoginSession(LoginState.Expired, "Twitch session expired"),
                channels = it.channels.map { channel -> channel.copy(watching = false) },
                currentChannel = null,
                activeCampaign = null,
                activeDrop = null,
                miningActive = false,
                channelSearchInProgress = false,
                error = command.message ?: "Twitch session expired",
            )
        }
    }

    private suspend fun reportUnexpectedCommandFailure(command: RuntimeCommand, error: Throwable) {
        val message = error.message ?: "Runtime command failed unexpectedly."
        updateSnapshot(RuntimePhase.Error, "Runtime command failed") {
            it.copy(error = message)
        }
        appendActivity(RuntimePhase.Error, "Runtime command failed", "${command.label}: $message")
    }

    fun startAuthentication() {
        enqueueCoalesced(RuntimeCommand.StartAuthentication)
    }

    fun replaceAuthentication() {
        enqueueCoalesced(RuntimeCommand.ReplaceAuthentication)
    }

    private suspend fun runAuthentication(
        authGeneration: Long,
        deviceId: String,
    ) {
        try {
            appendActivity(
                RuntimePhase.Authenticating,
                "Starting Twitch device login",
                "No Twitch password is collected by this app.",
            )
            var authorization: com.nathan.twitchdropsminer.android.data.twitch.DeviceAuthorization? = null
            var requestFailures = 0
            while (currentCoroutineContext().isActive && authorization == null) {
                ensureCurrentAuthentication(authGeneration)
                awaitUsableNetwork()
                ensureCurrentAuthentication(authGeneration)
                authorization = try {
                    twitchApiClient.requestDeviceCode(deviceId).also {
                        ensureCurrentAuthentication(authGeneration)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    ensureCurrentAuthentication(authGeneration)
                    requestFailures += 1
                    val retryDelay = RuntimeRetryBackoff.delayFor(requestFailures)
                    updateSnapshot(RuntimePhase.Connecting, "Twitch login temporarily unavailable") {
                        it.copy(
                            account = LoginSession(LoginState.LoginRequired, "Preparing Twitch device login"),
                            error = "${error.message ?: "Unable to request a Twitch device code."} " +
                                "Retrying in ${retryDelay.runtimeLabel()}.",
                        )
                    }
                    delay(retryDelay.toMillis())
                    null
                }
            }
            val activeAuthorization = authorization ?: return
            ensureCurrentAuthentication(authGeneration)
            updateSnapshot(RuntimePhase.Authenticating, "Waiting for Twitch activation") {
                it.copy(
                    account = LoginSession(
                        state = LoginState.LoginRequired,
                        statusText = "Enter Twitch device code ${activeAuthorization.userCode}",
                        oauthUrl = activeAuthorization.verificationUri,
                        oauthCode = activeAuthorization.userCode,
                        deviceCode = activeAuthorization.deviceCode,
                        expiresAt = activeAuthorization.expiresAt,
                    ),
                    progressSummary = "Open Twitch activation and approve the device code.",
                    error = null,
                )
            }

            var pollFailures = 0
            var validationFailures = 0
            var issuedToken: com.nathan.twitchdropsminer.android.data.twitch.TokenResponse? = null
            var pollIntervalSeconds = activeAuthorization.intervalSeconds
            while (
                currentCoroutineContext().isActive &&
                now().isBefore(activeAuthorization.expiresAt)
            ) {
                if (issuedToken == null) {
                    delay(
                        minOf(
                            pollIntervalSeconds * 1000L,
                            Duration.between(now(), activeAuthorization.expiresAt)
                                .toMillis()
                                .coerceAtLeast(0L),
                        ),
                    )
                    ensureCurrentAuthentication(authGeneration)
                    if (!awaitUsableNetworkBefore(activeAuthorization.expiresAt)) {
                        break
                    }
                    ensureCurrentAuthentication(authGeneration)
                    val pollResult = try {
                        twitchApiClient.pollDeviceToken(
                            activeAuthorization.deviceCode,
                            deviceId,
                        ).also { ensureCurrentAuthentication(authGeneration) }
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        ensureCurrentAuthentication(authGeneration)
                        pollFailures += 1
                        val retryDelay = RuntimeRetryBackoff.delayFor(pollFailures)
                        updateSnapshot(RuntimePhase.Authenticating, "Waiting for Twitch activation") {
                            it.copy(
                                error = "${error.message ?: "Twitch login polling was interrupted."} " +
                                    "Retrying in ${retryDelay.runtimeLabel()}.",
                            )
                        }
                        delay(
                            minOf(
                                retryDelay.toMillis(),
                                Duration.between(now(), activeAuthorization.expiresAt)
                                    .toMillis()
                                    .coerceAtLeast(0L),
                            ),
                        )
                        continue
                    }
                    pollFailures = 0
                    when (pollResult) {
                        DeviceTokenPollResult.AuthorizationPending -> continue
                        DeviceTokenPollResult.SlowDown -> {
                            pollIntervalSeconds = (pollIntervalSeconds + 5).coerceAtMost(60)
                            continue
                        }

                        is DeviceTokenPollResult.Authorized -> issuedToken = pollResult.token
                    }
                }
                ensureCurrentAuthentication(authGeneration)
                if (!awaitUsableNetworkBefore(activeAuthorization.expiresAt)) {
                    break
                }
                ensureCurrentAuthentication(authGeneration)
                val token = issuedToken
                val validated = try {
                    twitchApiClient.validateAccessToken(token.accessToken).also {
                        ensureCurrentAuthentication(authGeneration)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    ensureCurrentAuthentication(authGeneration)
                    if (error is TwitchApiException && error.type == TwitchApiErrorType.InvalidToken) {
                        throw error
                    }
                    validationFailures += 1
                    val retryDelay = RuntimeRetryBackoff.delayFor(validationFailures)
                    updateSnapshot(RuntimePhase.Authenticating, "Validating Twitch login") {
                        it.copy(
                            error = "${error.message ?: "Twitch login validation was interrupted."} " +
                                "Retrying in ${retryDelay.runtimeLabel()}.",
                        )
                    }
                    delay(
                        minOf(
                            retryDelay.toMillis(),
                            Duration.between(now(), activeAuthorization.expiresAt)
                                .toMillis()
                                .coerceAtLeast(0L),
                        ),
                    )
                    continue
                }
                ensureCurrentAuthentication(authGeneration)
                runtimeCommands.send(
                    RuntimeCommand.AuthenticationSucceeded(
                        authGeneration = authGeneration,
                        session = StoredTwitchSession(
                            accessToken = token.accessToken,
                            userId = validated.userId,
                            deviceId = deviceId,
                            savedAt = now(),
                        ),
                    ),
                )
                return
            }
            ensureCurrentAuthentication(authGeneration)
            updateSnapshot(RuntimePhase.Error, "Twitch device code expired") {
                it.copy(
                    account = LoginSession(LoginState.LoginRequired, "Device code expired"),
                    error = "Twitch device code expired. Start login again.",
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (isCurrentAuthentication(authGeneration)) {
                updateSnapshot(RuntimePhase.Error, "Twitch login failed") {
                    it.copy(
                        account = LoginSession(LoginState.LoginRequired, "Login failed"),
                        error = error.message ?: "Twitch login failed",
                    )
                }
                appendActivity(RuntimePhase.Error, "Twitch login failed", error.message)
            }
        }
    }

    fun startMining() {
        enqueueCoalesced(RuntimeCommand.StartMining)
    }

    fun stopMining() {
        enqueueCoalesced(RuntimeCommand.StopMining())
    }

    suspend fun stopMiningAndJoin() {
        val completed = CompletableDeferred<Unit>()
        runtimeCommands.send(RuntimeCommand.StopMining(awaitCompletion = true, completed = completed))
        completed.await()
    }

    fun refreshInventory() {
        enqueueCoalesced(RuntimeCommand.RefreshInventory)
    }

    private suspend fun refreshInventoryOnce(
        session: StoredTwitchSession,
        expectedSessionGeneration: Long,
        refreshGeneration: Long,
    ) {
        val settings = settingsRepository.settings.first()
        awaitUsableNetwork()
        ensureCurrentInventoryRefresh(expectedSessionGeneration, refreshGeneration)
        updateSnapshot(RuntimePhase.LoadingInventory, "Loading Twitch drops inventory")
        val campaignLoad = loadCampaigns(
            settings = settings,
            session = session,
            previousCampaigns = _snapshot.value.campaigns,
            isCurrent = {
                isCurrentInventoryRefresh(expectedSessionGeneration, refreshGeneration)
            },
        )
        ensureCurrentInventoryRefresh(expectedSessionGeneration, refreshGeneration)
        if (campaignLoad.failure != null) {
            updateSnapshot(RuntimePhase.Error, "Inventory refresh failed") {
                it.copy(
                    campaigns = markSelected(campaignLoad.campaigns, campaignLoad.settings),
                    progressSummary = campaignLoad.campaigns.progressSummary(),
                    error = campaignLoad.failure,
                )
            }
            return
        }
        val campaigns = campaignLoad.campaigns
        val effectiveSettings = campaignLoad.settings
        appendActivity(RuntimePhase.Idle, "Inventory refreshed", "${campaigns.size} campaigns")
        updateSnapshot(RuntimePhase.Idle, "Inventory refreshed") {
            it.copy(
                campaigns = markSelected(campaigns, effectiveSettings),
                channels = it.channels,
                progressSummary = campaigns.progressSummary(),
                error = campaignLoad.warning,
            )
        }
    }

    private suspend fun reportInventoryRefreshFailure(error: Throwable) {
        val message = error.message ?: "Unable to load Twitch inventory."
        updateSnapshot(RuntimePhase.Error, "Inventory refresh failed") {
            it.copy(error = message)
        }
        appendActivity(RuntimePhase.Error, "Inventory refresh failed", message)
    }

    suspend fun toggleGamePriority(gameName: String) {
        settingsRepository.toggleGamePriority(gameName)
        val settings = settingsRepository.settings.first()
        _snapshot.update {
            it.copy(
                campaigns = markSelected(it.campaigns, settings),
            )
        }
    }

    suspend fun moveGamePriority(gameName: String, offset: Int) {
        settingsRepository.moveGamePriority(gameName, offset)
        val settings = settingsRepository.settings.first()
        _snapshot.update {
            it.copy(campaigns = markSelected(it.campaigns, settings))
        }
    }

    suspend fun setGamePriority(gameName: String, priorityNumber: Int) {
        settingsRepository.setGamePriority(gameName, priorityNumber)
        val settings = settingsRepository.settings.first()
        _snapshot.update {
            it.copy(campaigns = markSelected(it.campaigns, settings))
        }
    }

    suspend fun clearGamePriority() {
        settingsRepository.clearGamePriority()
        val settings = settingsRepository.settings.first()
        _snapshot.update {
            it.copy(
                campaigns = markSelected(it.campaigns, settings),
                selectedCampaignIds = emptySet(),
            )
        }
    }

    fun selectChannel(channelId: Long) {
        val current = _snapshot.value
        if (
            !current.isRunning ||
            current.watchingChannel?.id == channelId ||
            current.channels.none { channel -> channel.id == channelId }
        ) {
            return
        }
        _snapshot.update {
            it.copy(
                phase = RuntimePhase.FindingChannel,
                currentTask = "Switching to selected channel",
                channelSearchInProgress = false,
                lastUpdate = now(),
                error = null,
            )
        }
        channelControlRequests.update { request ->
            ChannelControlRequest(
                id = request.id + 1L,
                selectedChannelId = channelId,
            )
        }
        scope.launch {
            appendActivity(RuntimePhase.FindingChannel, "Channel selection requested")
        }
    }

    fun resetSession() {
        enqueueCoalesced(RuntimeCommand.ResetSession())
    }

    suspend fun resetSessionAndJoin() {
        val completed = CompletableDeferred<Unit>()
        runtimeCommands.send(RuntimeCommand.ResetSession(completed))
        completed.await()
    }

    private suspend fun runMiningLoop(
        session: StoredTwitchSession,
        expectedSessionGeneration: Long,
        runGeneration: Long,
    ) {
        var validationFailures = 0
        while (currentCoroutineContext().isActive) {
            ensureCurrentMiningRun(expectedSessionGeneration, runGeneration)
            awaitUsableNetwork()
            val validation = runCatchingCancellable {
                twitchApiClient.validateAccessToken(session.accessToken)
            }
            ensureCurrentMiningRun(expectedSessionGeneration, runGeneration)
            if (validation.isSuccess) {
                break
            }
            val error = validation.exceptionOrNull() ?: continue
            if (error is TwitchApiException && error.type == TwitchApiErrorType.InvalidToken) {
                throw error
            }
            validationFailures += 1
            val retryDelay = RuntimeRetryBackoff.delayFor(validationFailures)
            updateSnapshot(RuntimePhase.Error, "Unable to validate Twitch session") {
                it.copy(
                    error = "${error.message ?: "Twitch validation failed"} Retrying in ${retryDelay.runtimeLabel()}.",
                )
            }
            delay(retryDelay.toMillis())
        }

        var inventoryFailures = 0
        var channelDiscoveryFailures = 0
        var handledChannelControlRequestId = channelControlRequests.value.id
        var handledInventoryRefreshRequestId = inventoryRefreshRequests.value
        while (currentCoroutineContext().isActive) {
            ensureCurrentMiningRun(expectedSessionGeneration, runGeneration)
            awaitUsableNetwork()
            var settings = settingsRepository.settings.first()
            updateSnapshot(RuntimePhase.LoadingInventory, "Loading Twitch drops inventory")
            val campaignLoad = loadCampaigns(
                settings = settings,
                session = session,
                previousCampaigns = _snapshot.value.campaigns,
                isCurrent = {
                    isCurrentMiningRun(expectedSessionGeneration, runGeneration)
                },
            )
            ensureCurrentMiningRun(expectedSessionGeneration, runGeneration)
            settings = campaignLoad.settings
            if (campaignLoad.failure != null) {
                inventoryFailures += 1
                val retryDelay = RuntimeRetryBackoff.delayFor(inventoryFailures)
                updateSnapshot(RuntimePhase.Error, "Inventory unavailable; retrying") {
                    it.copy(
                        campaigns = markSelected(campaignLoad.campaigns, settings),
                        channels = emptyList(),
                        currentChannel = null,
                        activeCampaign = null,
                        activeDrop = null,
                        progressSummary = campaignLoad.campaigns.progressSummary(),
                        error = "${campaignLoad.failure} Retrying in ${retryDelay.runtimeLabel()}.",
                    )
                }
                delay(retryDelay.toMillis())
                continue
            }
            inventoryFailures = 0
            val campaigns = campaignLoad.campaigns
            var campaignSnapshot = campaigns
            campaignSnapshot = claimCompletedDrops(settings, session, campaignSnapshot)
            ensureCurrentMiningRun(expectedSessionGeneration, runGeneration)
            val inventoryLoadedAt = now()
            val inventoryRefreshAt = inventoryLoadedAt.plus(
                Duration.ofMinutes(settings.inventoryRefreshMinutes.toLong()),
            )
            val selectedWork = try {
                selectCampaignWork(
                    settings = settings,
                    session = session,
                    campaignSnapshot = campaignSnapshot,
                )
            } catch (error: ChannelDiscoveryUnavailableException) {
                channelDiscoveryFailures += 1
                val retryDelay = RuntimeRetryBackoff.delayFor(channelDiscoveryFailures)
                updateSnapshot(RuntimePhase.Error, "Channel discovery unavailable; retrying") {
                    it.copy(
                        campaigns = markSelected(campaignSnapshot, settings),
                        channels = emptyList(),
                        currentChannel = null,
                        activeCampaign = null,
                        activeDrop = null,
                        progressSummary = campaignSnapshot.progressSummary(),
                        error = "${error.message} Retrying in ${retryDelay.runtimeLabel()}.",
                    )
                }
                delay(retryDelay.toMillis())
                continue
            }
            channelDiscoveryFailures = 0
            if (selectedWork is CampaignWorkSelection.Idle) {
                updateSnapshot(RuntimePhase.Idle, selectedWork.task) {
                    it.copy(
                        campaigns = markSelected(campaignSnapshot, settings),
                        currentChannel = null,
                        activeCampaign = null,
                        activeDrop = null,
                        progressSummary = campaignSnapshot.progressSummary(),
                        error = null,
                    )
                }
                appendActivity(RuntimePhase.Idle, selectedWork.activityTitle, selectedWork.detail)
                val idleNow = now()
                val idleDeadline = RuntimeTemporalSchedule.nextIdleDeadline(
                    campaigns = campaignSnapshot.filterNot(settings::isCampaignExcluded),
                    now = idleNow,
                    regularDeadline = idleNow.plusMillis(selectedWork.retryDelayMillis(settings)),
                    inventoryRefreshAt = inventoryRefreshAt,
                    claimRetryAt = dropClaimHandler.nextRetryAt(),
                )
                val idleWakeup = awaitIdleWakeup(
                    currentSettings = settings,
                    handledInventoryRefreshRequestId = handledInventoryRefreshRequestId,
                    timeoutMillis = Duration.between(idleNow, idleDeadline).toMillis().coerceAtLeast(0L),
                )
                if (idleWakeup == RuntimeWakeup.InventoryRefresh) {
                    handledInventoryRefreshRequestId = inventoryRefreshRequests.value
                }
                continue
            }

            val work = (selectedWork as CampaignWorkSelection.Selected).work
            var currentCampaign: Campaign = work.campaign
            var currentChannel = work.channel.copy(watching = true)
            var channels = work.channels
            var currentMode = work.mode
            var currentDropId = currentCampaign.watchableDrop(now = now())?.id
            var refreshAt = RuntimeTemporalSchedule.earliest(
                inventoryRefreshAt,
                dropClaimHandler.nextRetryAt(),
            )
            var unlinkedProgressProbe = currentCampaign.startUnlinkedProgressProbe(settings)
            var linkedProgressProbe = currentCampaign.startLinkedProgressProbe(settings)
            if (unlinkedProgressProbe != null) {
                appendActivity(
                    RuntimePhase.Watching,
                    "Watching unlinked game",
                    "${currentCampaign.gameName} on ${currentChannel.name}",
                )
                appendActivity(
                    RuntimePhase.Watching,
                    "Checking unlinked drop progress",
                    "Will verify real Twitch progress in about ${unlinkedProgressProbe.checkWindowLabel}.",
                )
            }
            var consecutiveRejectedWatchEvents = 0
            var transientWatchFailures = 0
            var consecutiveProgressFailures = 0
            var watchConfigurationRenewals = 0
            var nextWatchAt = now()
            var nextHigherPriorityCheckAt = now().plus(higherPriorityCheckInterval)
            var higherPriorityCheck: Deferred<Result<SelectedCampaignWork?>>? = null
            while (
                currentCoroutineContext().isActive &&
                now().isBefore(refreshAt)
            ) {
                val completedHigherPriorityCheck = higherPriorityCheck?.takeIf { check -> check.isCompleted }
                if (completedHigherPriorityCheck != null) {
                    higherPriorityCheck = null
                    val promotionSettings = settingsRepository.settings.first()
                    val promotion = completedHigherPriorityCheck.await().getOrThrow()
                    ensureCurrentOperation()
                    val promotionStillHigher = promotion != null &&
                        CampaignPrioritySelector.higherPriorityDecisions(
                            settings = promotionSettings,
                            campaigns = campaignSnapshot,
                            currentMode = currentMode,
                            currentCampaign = currentCampaign,
                        ).any { decision ->
                            decision.mode == promotion.mode &&
                                decision.candidates.any { candidate -> candidate.id == promotion.campaign.id }
                        }
                    if (promotion != null && promotionStillHigher) {
                        val previousCampaign = currentCampaign
                        val previousChannel = currentChannel
                        currentCampaign = promotion.campaign
                        currentChannel = promotion.channel.copy(watching = true)
                        channels = promotion.channels
                        currentMode = promotion.mode
                        currentDropId = currentCampaign.watchableDrop(now = now())?.id
                        unlinkedProgressProbe = currentCampaign.startUnlinkedProgressProbe(promotionSettings)
                        linkedProgressProbe = currentCampaign.startLinkedProgressProbe(promotionSettings)
                        consecutiveRejectedWatchEvents = 0
                        transientWatchFailures = 0
                        consecutiveProgressFailures = 0
                        watchConfigurationRenewals = 0
                        nextWatchAt = now()
                        nextHigherPriorityCheckAt = now().plus(higherPriorityCheckInterval)
                        updateSnapshot(
                            RuntimePhase.Watching,
                            "Higher-priority stream available; watching ${currentCampaign.gameName}",
                        ) {
                            it.copy(
                                campaigns = markSelected(campaignSnapshot, promotionSettings),
                                channels = channels.markWatching(currentChannel.id),
                                currentChannel = currentChannel,
                                activeCampaign = currentCampaign,
                                activeDrop = currentCampaign.watchableDrop(currentDropId, now()),
                                progressSummary = listOf(currentCampaign).progressSummary(),
                                error = null,
                            )
                        }
                        appendActivity(
                            RuntimePhase.Watching,
                            "Switched to higher-priority stream",
                            "${previousCampaign.gameName} on ${previousChannel.name} → " +
                                "${currentCampaign.gameName} on ${currentChannel.name}.",
                        )
                        if (unlinkedProgressProbe != null) {
                            appendActivity(
                                RuntimePhase.Watching,
                                "Checking unlinked drop progress",
                                "Will verify real Twitch progress in about ${unlinkedProgressProbe.checkWindowLabel}.",
                            )
                        }
                    }
                }
                val pendingInventoryRefreshRequestId = inventoryRefreshRequests.value
                if (pendingInventoryRefreshRequestId != handledInventoryRefreshRequestId) {
                    handledInventoryRefreshRequestId = pendingInventoryRefreshRequestId
                    updateSnapshot(RuntimePhase.LoadingInventory, "Refreshing inventory now") {
                        it.copy(error = null)
                    }
                    break
                }
                val resumedAfterNetworkLoss = awaitUsableNetwork()
                if (!now().isBefore(refreshAt)) {
                    break
                }
                if (resumedAfterNetworkLoss && unlinkedProgressProbe != null) {
                    unlinkedProgressProbe = currentCampaign.startUnlinkedProgressProbe(settings)
                    appendActivity(
                        RuntimePhase.Watching,
                        "Restarting unlinked progress check",
                        "The network interruption is excluded from the progress-check window.",
                    )
                }
                if (resumedAfterNetworkLoss && linkedProgressProbe != null) {
                    linkedProgressProbe = currentCampaign.startLinkedProgressProbe(settings)
                }
                val pendingChannelControlRequest = channelControlRequests.value
                if (pendingChannelControlRequest.id != handledChannelControlRequestId) {
                    handledChannelControlRequestId = pendingChannelControlRequest.id
                    val requestedChannelId = pendingChannelControlRequest.selectedChannelId
                    if (requestedChannelId == null) {
                        val search = findCompatibleChannels(
                            session = session,
                            campaign = currentCampaign,
                            originalChannel = currentChannel,
                        )
                        channels = search.channels
                        updateSnapshot(RuntimePhase.Watching, search.task) {
                            it.copy(
                                channels = channels.markWatching(currentChannel.id),
                                currentChannel = currentChannel.copy(watching = true),
                                activeCampaign = currentCampaign,
                                activeDrop = currentCampaign.activeDrop(currentDropId),
                                channelSearchInProgress = false,
                                error = null,
                            )
                        }
                        appendActivity(
                            RuntimePhase.Watching,
                            "Compatible channel list updated",
                            search.detail,
                        )
                    } else {
                        val selected = ChannelPickerSelection.findCompatibleChannel(
                            channels = channels,
                            channelId = requestedChannelId,
                        )
                        if (selected == null || selected.id == currentChannel.id) {
                            updateSnapshot(
                                RuntimePhase.Watching,
                                "Selected channel unavailable; keeping ${currentChannel.name}",
                            ) {
                                it.copy(
                                    channels = channels.markWatching(currentChannel.id),
                                    currentChannel = currentChannel.copy(watching = true),
                                    channelSearchInProgress = false,
                                    error = null,
                                )
                            }
                            appendActivity(
                                RuntimePhase.Watching,
                                "Channel selection ignored",
                                "The selected streamer is no longer compatible or live; ${currentChannel.name} remains active.",
                            )
                        } else {
                            val previousChannel = currentChannel
                            currentChannel = selected.copy(watching = true)
                            consecutiveRejectedWatchEvents = 0
                            transientWatchFailures = 0
                            unlinkedProgressProbe = currentCampaign.startUnlinkedProgressProbe(settings)
                            linkedProgressProbe = currentCampaign.startLinkedProgressProbe(settings)
                            watchConfigurationRenewals = 0
                            nextWatchAt = now()
                            updateSnapshot(
                                RuntimePhase.Watching,
                                "Switched to ${currentChannel.name}",
                            ) {
                                it.copy(
                                    channels = channels.markWatching(currentChannel.id),
                                    currentChannel = currentChannel,
                                    activeCampaign = currentCampaign,
                                    activeDrop = currentCampaign.watchableDrop(currentDropId, now()),
                                    channelSearchInProgress = false,
                                    error = null,
                                )
                            }
                            appendActivity(
                                RuntimePhase.Watching,
                                "Switched to selected channel",
                                "${previousChannel.name} → ${currentChannel.name} for ${currentCampaign.gameName}.",
                            )
                        }
                    }
                }
                val latestSettings = settingsRepository.settings.first()
                if (latestSettings != settings) {
                    val settingsChangedAt = now()
                    if (latestSettings.inventoryRefreshMinutes != settings.inventoryRefreshMinutes) {
                        refreshAt = RuntimeTemporalSchedule.earliest(
                            inventoryLoadedAt.plus(
                                Duration.ofMinutes(latestSettings.inventoryRefreshMinutes.toLong()),
                            ),
                            dropClaimHandler.nextRetryAt(),
                        )
                    }
                    if (
                        latestSettings.watchIntervalSeconds != settings.watchIntervalSeconds &&
                        settingsChangedAt.isBefore(nextWatchAt)
                    ) {
                        nextWatchAt = settingsChangedAt.plusSeconds(
                            latestSettings.watchIntervalSeconds.toLong(),
                        )
                        unlinkedProgressProbe = currentCampaign.startUnlinkedProgressProbe(latestSettings)
                        linkedProgressProbe = currentCampaign.startLinkedProgressProbe(latestSettings)
                    }
                    nextHigherPriorityCheckAt = settingsChangedAt
                }
                settings = latestSettings
                if (ActiveWatchGuard.shouldStopForExcludedCampaign(latestSettings, currentCampaign)) {
                    updateSnapshot(RuntimePhase.Idle, "Campaign excluded; stopping current watch") {
                        it.copy(
                            campaigns = markSelected(campaignSnapshot, settings),
                            channels = channels.map { channel -> channel.copy(watching = false) },
                            currentChannel = null,
                            activeCampaign = null,
                            activeDrop = null,
                            progressSummary = campaignSnapshot.progressSummary(),
                            error = null,
                        )
                    }
                    appendActivity(
                        RuntimePhase.Idle,
                        "Campaign excluded while active",
                        "${currentCampaign.gameName}; stopping current watch and reselecting.",
                    )
                    break
                }
                val configuredMode = CampaignPrioritySelector.modeForCampaign(settings, currentCampaign)
                if (configuredMode == null) {
                    updateSnapshot(RuntimePhase.Idle, "Settings changed; reselecting campaign") {
                        it.copy(
                            campaigns = markSelected(campaignSnapshot, settings),
                            channels = channels.map { channel -> channel.copy(watching = false) },
                            currentChannel = null,
                            activeCampaign = null,
                            activeDrop = null,
                            progressSummary = campaignSnapshot.progressSummary(),
                            error = null,
                        )
                    }
                    appendActivity(
                        RuntimePhase.Idle,
                        "Current campaign no longer matches mining settings",
                        "${currentCampaign.gameName}; stopping current watch and reselecting.",
                    )
                    break
                }
                currentMode = configuredMode

                val schedulingNow = now()
                if (higherPriorityCheck == null && !schedulingNow.isBefore(nextHigherPriorityCheckAt)) {
                    pruneExpiredChannelSkips(schedulingNow)
                    val higherPriorityDecisions = CampaignPrioritySelector.higherPriorityDecisions(
                        settings = settings,
                        campaigns = campaignSnapshot,
                        currentMode = currentMode,
                        currentCampaign = currentCampaign,
                    )
                    if (higherPriorityDecisions.isNotEmpty()) {
                        val skippedChannelIds = failedChannelSkips.keys.toSet()
                        higherPriorityCheck = CoroutineScope(currentCoroutineContext()).async {
                            runCatchingCancellable {
                                findHigherPriorityWork(
                                    session = session,
                                    decisions = higherPriorityDecisions,
                                    skippedChannelIds = skippedChannelIds,
                                )
                            }
                        }
                    }
                    nextHigherPriorityCheckAt = schedulingNow.plus(higherPriorityCheckInterval)
                }
                val activeDrop = currentCampaign.watchableDrop(currentDropId, schedulingNow)
                if (activeDrop == null) {
                    val idleStatus = CampaignTemporalPolicy.idleStatus(currentCampaign, schedulingNow)
                    updateSnapshot(RuntimePhase.Idle, idleStatus.task) {
                        it.copy(
                            campaigns = markSelected(campaignSnapshot, settings),
                            channels = channels.map { channel -> channel.copy(watching = false) },
                            currentChannel = null,
                            activeCampaign = currentCampaign,
                            activeDrop = null,
                            progressSummary = listOf(currentCampaign).progressSummary(),
                            error = null,
                        )
                    }
                    appendActivity(RuntimePhase.Idle, idleStatus.activityTitle, idleStatus.detail)
                    break
                }
                if (schedulingNow.isBefore(nextWatchAt)) {
                    awaitActiveWakeup(
                        currentSettings = settings,
                        handledChannelControlRequestId = handledChannelControlRequestId,
                        handledInventoryRefreshRequestId = handledInventoryRefreshRequestId,
                        higherPriorityCheck = higherPriorityCheck,
                        deadline = RuntimeTemporalSchedule.nextActiveDeadline(
                            nextWatchAt,
                            nextHigherPriorityCheckAt,
                            refreshAt,
                            currentCampaign.endsAt,
                            activeDrop.endsAt,
                        ),
                    )
                    continue
                }
                val watchingTask = currentCampaign.watchingTask(currentChannel, unlinkedProgressProbe)
                if (!_snapshot.value.matchesActiveWatch(currentCampaign, currentChannel, activeDrop, watchingTask)) {
                    updateSnapshot(RuntimePhase.Watching, watchingTask) {
                        it.copy(
                            campaigns = markSelected(campaignSnapshot, settings),
                            channels = channels.markWatching(currentChannel.id),
                            currentChannel = currentChannel,
                            activeCampaign = currentCampaign,
                            activeDrop = activeDrop,
                            progressSummary = listOf(currentCampaign).progressSummary(),
                            error = null,
                        )
                    }
                }

                val watchAttempt = sendWatch(session, currentChannel)
                when (watchAttempt) {
                    WatchAttemptResult.Accepted -> {
                        consecutiveRejectedWatchEvents = 0
                        transientWatchFailures = 0
                    }

                    WatchAttemptResult.Rejected -> {
                        transientWatchFailures = 0
                        consecutiveRejectedWatchEvents += 1
                        if (consecutiveRejectedWatchEvents >= RejectedWatchFailureThreshold) {
                            ensureCurrentOperation()
                            failedChannelSkips[currentChannel.id] = now()
                            updateSnapshot(
                                RuntimePhase.Idle,
                                "Switching away from an unhealthy channel",
                            ) {
                                it.copy(
                                    channels = channels.map { channel -> channel.copy(watching = false) },
                                    currentChannel = null,
                                    activeCampaign = currentCampaign,
                                    activeDrop = activeDrop,
                                    error = null,
                                )
                            }
                            appendActivity(
                                RuntimePhase.Idle,
                                "Channel watch events repeatedly rejected",
                                "${currentChannel.name} rejected $consecutiveRejectedWatchEvents consecutive watch events; trying another channel.",
                            )
                            break
                        }
                    }

                    is WatchAttemptResult.Failed -> {
                        transientWatchFailures += 1
                        consecutiveRejectedWatchEvents = 0
                        val retryDelay = RuntimeRetryBackoff.delayFor(transientWatchFailures)
                        updateSnapshot(RuntimePhase.Error, "Watch request failed; retrying") {
                            it.copy(
                                error = "${watchAttempt.message} Retrying in ${retryDelay.runtimeLabel()}.",
                            )
                        }
                        delay(retryDelay.toMillis())
                        continue
                    }
                }
                val progressRefresh = updateProgress(
                    session = session,
                    campaign = currentCampaign,
                    campaigns = campaignSnapshot,
                    channel = currentChannel,
                )
                campaignSnapshot = progressRefresh.campaigns
                if (progressRefresh.campaign.id != currentCampaign.id) {
                    val reportedMode = CampaignPrioritySelector.modeForCampaign(
                        settings,
                        progressRefresh.campaign,
                    )
                    if (
                        reportedMode == null ||
                        settings.isCampaignExcluded(progressRefresh.campaign)
                    ) {
                        ensureCurrentOperation()
                        failedChannelSkips[currentChannel.id] = now()
                        appendActivity(
                            RuntimePhase.Idle,
                            "Channel is progressing a campaign outside current settings",
                            "${currentChannel.name} reported ${progressRefresh.campaign.gameName}; trying another channel.",
                        )
                        break
                    }
                    val previousCampaign = currentCampaign
                    currentCampaign = progressRefresh.campaign
                    currentMode = reportedMode
                    currentDropId = progressRefresh.reportedDropId
                    channels = listOf(currentChannel)
                    unlinkedProgressProbe = currentCampaign.startUnlinkedProgressProbe(settings)
                    linkedProgressProbe = currentCampaign.startLinkedProgressProbe(settings)
                    watchConfigurationRenewals = 0
                    appendActivity(
                        RuntimePhase.Watching,
                        "Following Twitch-reported campaign",
                        "${previousCampaign.gameName} → ${currentCampaign.gameName} on ${currentChannel.name}.",
                    )
                } else {
                    currentCampaign = progressRefresh.campaign
                    currentDropId = progressRefresh.reportedDropId ?: currentDropId
                }
                val refreshedActiveDrop = currentCampaign.activeDrop(currentDropId)
                currentDropId = refreshedActiveDrop?.id
                currentMode = CampaignPrioritySelector.modeForCampaign(settings, currentCampaign) ?: currentMode
                val progressError = when (val observation = progressRefresh.observation) {
                    is ProgressObservation.Unavailable -> {
                        consecutiveProgressFailures += 1
                        if (consecutiveProgressFailures == 1) {
                            appendActivity(
                                RuntimePhase.Watching,
                                "Twitch progress check unavailable",
                                "Keeping ${currentCampaign.gameName} active until progress can be confirmed.",
                            )
                        }
                        observation.message
                    }

                    else -> {
                        if (consecutiveProgressFailures > 0) {
                            appendActivity(RuntimePhase.Watching, "Twitch progress checks recovered")
                        }
                        consecutiveProgressFailures = 0
                        null
                    }
                }
                updateSnapshot(
                    RuntimePhase.Watching,
                    currentCampaign.watchingTask(currentChannel, unlinkedProgressProbe),
                ) {
                    it.copy(
                        campaigns = markSelected(campaignSnapshot, settings),
                        channels = channels.markWatching(currentChannel.id),
                        currentChannel = currentChannel,
                        activeCampaign = currentCampaign,
                        activeDrop = refreshedActiveDrop,
                        progressSummary = listOf(currentCampaign).progressSummary(),
                        error = progressError,
                    )
                }

                val probe = unlinkedProgressProbe
                var watchAgainImmediately = false
                var abandonStalledChannel = false
                if (probe != null && progressRefresh.observation.isConfirmed) {
                    when (val result = probe.observe(currentCampaign, settings, now())) {
                        is UnlinkedProgressProbeResult.Continue -> {
                            unlinkedProgressProbe = result.probe
                            if (result.progressDetectedNow) {
                                watchConfigurationRenewals = 0
                                appendActivity(
                                    RuntimePhase.Watching,
                                    "Unlinked progress detected",
                                    "${currentCampaign.gameName} progress increased; continuous supervision remains active.",
                                )
                            }
                        }

                        is UnlinkedProgressProbeResult.Stalled -> {
                            when (ProgressRecoveryPolicy.afterConfirmedStall(watchConfigurationRenewals)) {
                                ProgressRecoveryAction.RenewWatchConfiguration -> {
                                    ensureCurrentOperation()
                                    watchConfigurationRenewals += 1
                                    twitchApiClient.invalidateWatchConfiguration(currentChannel.id)
                                    unlinkedProgressProbe = UnlinkedProgressProbe.start(
                                        currentCampaign,
                                        settings,
                                        now(),
                                        progressDetected = result.progressHadBeenDetected,
                                    )
                                    watchAgainImmediately = true
                                    appendActivity(
                                        RuntimePhase.Watching,
                                        "Refreshing stale watch configuration",
                                        "No confirmed unlinked progress for ${result.elapsedLabel}; retrying ${currentChannel.name} with fresh Twitch configuration.",
                                    )
                                }

                                ProgressRecoveryAction.AbandonChannel -> {
                                    ensureCurrentOperation()
                                    failedChannelSkips[currentChannel.id] = result.checkedAt
                                    abandonStalledChannel = true
                                    updateSnapshot(RuntimePhase.Idle, "Trying another channel after stalled progress") {
                                        it.copy(
                                            channels = channels.map { channel -> channel.copy(watching = false) },
                                            currentChannel = null,
                                            error = null,
                                        )
                                    }
                                    appendActivity(
                                        RuntimePhase.Idle,
                                        "Unlinked progress remained stalled",
                                        "${currentChannel.name} made no confirmed progress after a fresh watch configuration; trying another channel or campaign.",
                                    )
                                }
                            }
                        }
                    }
                }
                if (abandonStalledChannel) {
                    break
                }

                val linkedProbe = linkedProgressProbe
                if (
                    probe == null &&
                    linkedProbe != null &&
                    progressRefresh.observation.isConfirmed
                ) {
                    when (val result = linkedProbe.observe(currentCampaign, settings, now())) {
                        is LinkedProgressProbeResult.Continue -> {
                            linkedProgressProbe = result.probe
                            if (result.progressDetectedNow) {
                                watchConfigurationRenewals = 0
                                appendActivity(
                                    RuntimePhase.Watching,
                                    "Linked drop progress confirmed",
                                    "${currentCampaign.gameName} is continuing to earn progress.",
                                )
                            }
                        }

                        is LinkedProgressProbeResult.Stalled -> {
                            when (ProgressRecoveryPolicy.afterConfirmedStall(watchConfigurationRenewals)) {
                                ProgressRecoveryAction.RenewWatchConfiguration -> {
                                    ensureCurrentOperation()
                                    watchConfigurationRenewals += 1
                                    twitchApiClient.invalidateWatchConfiguration(currentChannel.id)
                                    linkedProgressProbe = currentCampaign.startLinkedProgressProbe(settings)
                                    watchAgainImmediately = true
                                    appendActivity(
                                        RuntimePhase.Watching,
                                        "Refreshing stale watch configuration",
                                        "No confirmed progress for ${result.elapsedLabel}; retrying ${currentChannel.name} with fresh Twitch configuration.",
                                    )
                                }

                                ProgressRecoveryAction.AbandonChannel -> {
                                    ensureCurrentOperation()
                                    failedChannelSkips[currentChannel.id] = result.checkedAt
                                    updateSnapshot(RuntimePhase.Idle, "Trying another channel after stalled progress") {
                                        it.copy(
                                            channels = channels.map { channel -> channel.copy(watching = false) },
                                            currentChannel = null,
                                            error = null,
                                        )
                                    }
                                    appendActivity(
                                        RuntimePhase.Idle,
                                        "Linked progress remained stalled",
                                        "${currentChannel.name} made no confirmed progress after a fresh watch configuration; trying another channel.",
                                    )
                                    abandonStalledChannel = true
                                }
                            }
                            if (abandonStalledChannel) {
                                break
                            }
                        }
                    }
                }

                val claimable = firstClaimableDrop(session, currentCampaign)
                if (claimable != null) {
                    updateSnapshot(RuntimePhase.Claiming, "Claiming ${claimable.name}") {
                        it.copy(
                            campaigns = markSelected(campaignSnapshot, settings),
                            activeCampaign = currentCampaign,
                            activeDrop = claimable,
                        )
                    }
                    val claimApplication = claimDrop(session, currentCampaign, claimable)
                    currentCampaign = claimApplication.campaign
                    campaignSnapshot = campaignSnapshot.replaceCampaign(currentCampaign)
                    currentMode = CampaignPrioritySelector.modeForCampaign(settings, currentCampaign) ?: currentMode
                    val claimedSuccessfully = currentCampaign.drops
                        .firstOrNull { drop -> drop.id == claimable.id }
                        ?.isClaimed == true
                    if (claimedSuccessfully) {
                        currentDropId = null
                        val nextActiveDrop = currentCampaign.activeDrop()
                        currentDropId = nextActiveDrop?.id
                        if (nextActiveDrop == null) {
                            val idleStatus = CampaignTemporalPolicy.idleStatus(currentCampaign, now())
                            updateSnapshot(RuntimePhase.Idle, idleStatus.task) {
                                it.copy(
                                    campaigns = markSelected(campaignSnapshot, settings),
                                    channels = channels.map { channel -> channel.copy(watching = false) },
                                    currentChannel = null,
                                    activeCampaign = currentCampaign,
                                    activeDrop = null,
                                    progressSummary = listOf(currentCampaign).progressSummary(),
                                    error = null,
                                )
                            }
                            appendActivity(RuntimePhase.Idle, idleStatus.activityTitle, idleStatus.detail)
                            break
                        }
                        updateSnapshot(
                            RuntimePhase.Watching,
                            currentCampaign.watchingTask(currentChannel, unlinkedProgressProbe),
                        ) {
                            it.copy(
                                campaigns = markSelected(campaignSnapshot, settings),
                                channels = channels.markWatching(currentChannel.id),
                                currentChannel = currentChannel,
                                activeCampaign = currentCampaign,
                                activeDrop = nextActiveDrop,
                                progressSummary = listOf(currentCampaign).progressSummary(),
                                error = null,
                            )
                        }
                    } else if (currentCoroutineContext().isActive) {
                        val retryAt = claimApplication.result.retryAt
                        updateSnapshot(RuntimePhase.Idle, "Reselecting work after claim failure") {
                            it.copy(
                                campaigns = markSelected(campaignSnapshot, settings),
                                channels = channels.map { channel -> channel.copy(watching = false) },
                                currentChannel = null,
                                activeCampaign = currentCampaign,
                                activeDrop = claimable,
                                progressSummary = listOf(currentCampaign).progressSummary(),
                                error = claimApplication.result.message,
                            )
                        }
                        appendActivity(
                            RuntimePhase.Idle,
                            "Reselecting after drop claim failure",
                            retryAt?.let { "Claim retry is eligible at $it; looking for other useful watch work now." }
                                ?: "The failed claim is terminal for this run; looking for other useful watch work now.",
                        )
                        break
                    }
                }

                nextWatchAt = if (watchAgainImmediately) {
                    now()
                } else {
                    now().plusSeconds(settings.watchIntervalSeconds.toLong())
                }
                awaitActiveWakeup(
                    currentSettings = settings,
                    handledChannelControlRequestId = handledChannelControlRequestId,
                    handledInventoryRefreshRequestId = handledInventoryRefreshRequestId,
                    higherPriorityCheck = higherPriorityCheck,
                    deadline = RuntimeTemporalSchedule.nextActiveDeadline(
                        nextWatchAt,
                        nextHigherPriorityCheckAt,
                        refreshAt,
                        currentCampaign.endsAt,
                        currentCampaign.watchableDrop(currentDropId, now())?.endsAt,
                    ),
                )
            }
            higherPriorityCheck?.cancelAndJoin()
        }
    }

    private suspend fun findCompatibleChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
        originalChannel: Channel,
    ): CompatibleChannelSearch {
        updateSnapshot(RuntimePhase.FindingChannel, "Checking compatible live channels") {
            it.copy(error = null)
        }
        val discovered = try {
            loadChannels(session, campaign)
        } catch (error: ChannelDiscoveryUnavailableException) {
            return CompatibleChannelSearch(
                channels = listOf(originalChannel),
                task = "Channel search failed; keeping ${originalChannel.name}",
                detail = "Channel search failed, so ${originalChannel.name} remains active: ${error.message}",
            )
        }
        val alternatives = EligibleChannelSelector.candidates(
            channels = discovered,
            skippedChannelIds = failedChannelSkips.keys + originalChannel.id,
        )
        val availableChannels = listOf(originalChannel) + alternatives
        return CompatibleChannelSearch(
            channels = availableChannels,
            task = if (alternatives.isEmpty()) {
                "No alternate channels found; keeping ${originalChannel.name}"
            } else {
                "Choose from ${alternatives.size} compatible alternate channels"
            },
            detail = "Found ${alternatives.size} alternate streamer${if (alternatives.size == 1) "" else "s"} for ${campaign.gameName}; ${originalChannel.name} remains active until a selection is made.",
        )
    }

    private suspend fun claimCompletedDrops(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaigns: List<Campaign>,
    ): List<Campaign> {
        var updatedCampaigns = campaigns
        for (campaign in campaigns) {
            if (campaign.startsAt?.let { now().isBefore(it) } == true) {
                continue
            }
            var currentCampaign = updatedCampaigns.firstOrNull { it.id == campaign.id } ?: campaign
            for (orderedDrop in currentCampaign.drops.inEarningOrder()) {
                val drop = currentCampaign.drops.firstOrNull { candidate ->
                    candidate.id == orderedDrop.id
                } ?: continue
                if (currentCampaign.claimableDropsInEarningOrder().none { candidate -> candidate.id == drop.id }) {
                    continue
                }
                if (dropClaimHandler.suppressionFor(session, currentCampaign, drop) != null) {
                    continue
                }
                updateSnapshot(RuntimePhase.Claiming, "Claiming ${drop.name}") {
                    it.copy(
                        campaigns = markSelected(updatedCampaigns, settings),
                        activeCampaign = currentCampaign,
                        activeDrop = drop,
                        progressSummary = updatedCampaigns.progressSummary(),
                        error = null,
                    )
                }
                currentCampaign = claimDrop(session, currentCampaign, drop).campaign
                updatedCampaigns = updatedCampaigns.replaceCampaign(currentCampaign)
            }
        }
        return updatedCampaigns
    }

    private suspend fun selectCampaignWork(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaignSnapshot: List<Campaign>,
    ): CampaignWorkSelection {
        val excludedCampaigns = campaignSnapshot.filter { settings.isCampaignExcluded(it) }
        val excludedCampaignIds = excludedCampaigns.normalizedCampaignIds()
        if (excludedCampaignIds.isNotEmpty() && excludedCampaignIds != lastLoggedExcludedCampaignIds) {
            appendExcludedCampaignSkips(excludedCampaigns)
            lastLoggedExcludedCampaignIds = excludedCampaignIds
        } else if (excludedCampaignIds.isEmpty()) {
            lastLoggedExcludedCampaignIds = emptySet()
        }
        val decision = CampaignPrioritySelector.initialDecision(settings, campaignSnapshot, now())
        return selectFromCandidateDecision(
            settings = settings,
            session = session,
            campaignSnapshot = campaignSnapshot,
            selectionCampaigns = campaignSnapshot,
            decision = decision,
        )
    }

    private fun pruneExpiredChannelSkips(now: Instant) {
        val expiredChannelIds = failedChannelSkips
            .filterValues { skippedAt ->
                Duration.between(skippedAt, now) >= FailedChannelRetryDelay
            }
            .keys
        expiredChannelIds.forEach(failedChannelSkips::remove)
    }

    private suspend fun appendExcludedCampaignSkips(campaigns: List<Campaign>) {
        val skipped = campaigns.distinctBy { it.id }
        val title = if (skipped.size == 1) {
            "Skipped excluded campaign"
        } else {
            "Skipped excluded campaigns"
        }
        appendActivity(
            RuntimePhase.SelectingCampaign,
            title,
            skipped.selectionLabel(limit = 4),
        )
    }

    private suspend fun selectFromCandidateDecision(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaignSnapshot: List<Campaign>,
        selectionCampaigns: List<Campaign>,
        decision: CampaignCandidateDecision,
    ): CampaignWorkSelection =
        when (decision) {
            is CampaignCandidateDecision.Idle -> CampaignWorkSelection.Idle(decision)
            is CampaignCandidateDecision.Try -> {
                announceCandidateDecision(settings, campaignSnapshot, decision)
                val work = selectCampaignWithChannel(
                    settings = settings,
                    session = session,
                    campaignSnapshot = campaignSnapshot,
                    decision = decision,
                )
                if (work != null) {
                    CampaignWorkSelection.Selected(work)
                } else {
                    selectFromCandidateDecision(
                        settings = settings,
                        session = session,
                        campaignSnapshot = campaignSnapshot,
                        selectionCampaigns = selectionCampaigns,
                        decision = CampaignPrioritySelector.afterNoChannelDecision(
                            settings,
                            selectionCampaigns,
                            decision.mode,
                        ),
                    )
                }
            }
        }

    private suspend fun announceCandidateDecision(
        settings: AppSettings,
        campaignSnapshot: List<Campaign>,
        decision: CampaignCandidateDecision.Try,
    ) {
        val activityTitle = when {
            decision.mode.isLinkedFallback -> "Falling back to linked games"
            decision.mode.isUnlinked -> "Trying unlinked games"
            else -> return
        }
        updateSnapshot(RuntimePhase.SelectingCampaign, decision.task) {
            it.copy(
                campaigns = markSelected(campaignSnapshot, settings),
                currentChannel = null,
                activeCampaign = null,
                activeDrop = null,
                progressSummary = campaignSnapshot.progressSummary(),
                error = null,
            )
        }
        appendActivity(RuntimePhase.SelectingCampaign, activityTitle, decision.detail)
    }

    private suspend fun selectCampaignWithChannel(
        settings: AppSettings,
        session: StoredTwitchSession,
        campaignSnapshot: List<Campaign>,
        decision: CampaignCandidateDecision.Try,
    ): SelectedCampaignWork? {
        pruneExpiredChannelSkips(now())
        for (candidate in decision.candidates) {
            appendDebug(
                "Selection candidate ${candidate.gameName} (${decision.mode.name}); checking eligible channels.",
            )
            updateSnapshot(RuntimePhase.SelectingCampaign, decision.mode.selectionTask(candidate)) {
                it.copy(
                    campaigns = markSelected(campaignSnapshot, settings),
                    activeCampaign = candidate,
                    activeDrop = candidate.watchableDrop(now = now()),
                    progressSummary = campaignSnapshot.progressSummary(),
                    selectedCampaignIds = settings.selectedCampaignIds,
                    error = null,
                )
            }

            updateSnapshot(RuntimePhase.FindingChannel, "Finding eligible live channels")
            val channels = try {
                loadChannels(session, candidate)
            } catch (_: ChannelDiscoveryUnavailableException) {
                appendDebug("Selection skipped ${candidate.gameName}: channel discovery failed; trying the next candidate.")
                continue
            }
            val selectedChannel = EligibleChannelSelector.select(
                channels = channels,
                skippedChannelIds = failedChannelSkips.keys,
            )
            if (selectedChannel != null) {
                return SelectedCampaignWork(
                    campaign = candidate,
                    channel = selectedChannel,
                    channels = channels,
                    mode = decision.mode,
                )
            }

            updateSnapshot(RuntimePhase.Idle, "No eligible live channel for ${candidate.gameName}") {
                it.copy(
                    campaigns = markSelected(campaignSnapshot, settings),
                    channels = channels,
                    currentChannel = null,
                )
            }
            appendActivity(
                RuntimePhase.Idle,
                "No eligible live channel",
                decision.mode.noChannelDetail(candidate),
            )
        }
        return null
    }

    private suspend fun findHigherPriorityWork(
        session: StoredTwitchSession,
        decisions: List<CampaignCandidateDecision.Try>,
        skippedChannelIds: Set<Long>,
    ): SelectedCampaignWork? {
        for (decision in decisions) {
            for (candidate in decision.candidates) {
                val channels = try {
                    twitchApiClient.fetchEligibleChannels(session, candidate).also {
                        ensureCurrentOperation()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    ensureCurrentOperation()
                    error.throwIfInvalidToken()
                    appendDebug("Background promotion check skipped ${candidate.gameName}: candidate lookup failed.")
                    continue
                }
                val selectedChannel = EligibleChannelSelector.select(
                    channels = channels,
                    skippedChannelIds = skippedChannelIds,
                ) ?: continue
                return SelectedCampaignWork(
                    campaign = candidate,
                    channel = selectedChannel,
                    channels = channels,
                    mode = decision.mode,
                )
            }
        }
        return null
    }

    private suspend fun loadCampaigns(
        settings: AppSettings,
        session: StoredTwitchSession,
        previousCampaigns: List<Campaign>,
        isCurrent: () -> Boolean = { true },
    ): CampaignLoadResult {
        val loaded = try {
            twitchApiClient.fetchCampaignInventory(session).also {
                if (!isCurrent()) {
                    throw CancellationException("Inventory result was superseded by a newer runtime operation.")
                }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            if (!isCurrent()) {
                throw CancellationException("Inventory failure was superseded by a newer runtime operation.")
            }
            error.throwIfInvalidToken()
            val message = error.message ?: "Unable to load Twitch inventory."
            appendActivity(RuntimePhase.Error, "Inventory fetch failed", message)
            return CampaignLoadResult(
                campaigns = previousCampaigns,
                settings = settings,
                failure = message,
            )
        }
        if (!isCurrent()) {
            throw CancellationException("Inventory result was superseded by a newer runtime operation.")
        }
        val campaigns = if (loaded.isPartial) {
            mergePartialInventory(previousCampaigns, loaded.campaigns)
        } else {
            loaded.campaigns
        }
        val warning = loaded.diagnostics.takeIf(List<String>::isNotEmpty)?.let { diagnostics ->
            "Twitch inventory was only partially parsed; retained safe prior data. " +
                diagnostics.joinToString("; ").take(768)
        }
        if (warning != null) {
            appendActivity(RuntimePhase.Error, "Inventory partially parsed", warning)
        }
        return CampaignLoadResult(campaigns, settings, warning = warning)
    }

    private suspend fun loadChannels(
        session: StoredTwitchSession,
        campaign: Campaign,
    ): List<Channel> {
        return try {
            twitchApiClient.fetchEligibleChannels(session, campaign).also {
                ensureCurrentOperation()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ensureCurrentOperation()
            error.throwIfInvalidToken()
            val message = error.message ?: "Unable to discover Twitch channels."
            appendActivity(RuntimePhase.Error, "Channel discovery failed", message)
            throw ChannelDiscoveryUnavailableException(message, error)
        }
    }

    fun findNewChannel() {
        val current = _snapshot.value
        if (
            !current.isRunning ||
            current.phase != RuntimePhase.Watching ||
            current.activeCampaign == null ||
            current.watchingChannel == null
        ) {
            return
        }
        _snapshot.update {
            it.copy(
                phase = RuntimePhase.FindingChannel,
                currentTask = "Loading compatible channels",
                channelSearchInProgress = true,
                lastUpdate = now(),
                error = null,
            )
        }
        channelControlRequests.update { request ->
            ChannelControlRequest(id = request.id + 1L)
        }
        scope.launch {
            appendActivity(
                RuntimePhase.FindingChannel,
                "Compatible channel list requested",
                "Searching for streamers compatible with ${current.activeCampaign.gameName}.",
            )
        }
    }

    private suspend fun sendWatch(
        session: StoredTwitchSession,
        channel: Channel,
    ): WatchAttemptResult {
        if (channel.broadcastId == null) {
            return WatchAttemptResult.Rejected
        }
        return try {
            val accepted = twitchApiClient.sendWatchMinute(session, channel)
            ensureCurrentOperation()
            if (accepted) {
                appendDebug("Watch heartbeat accepted for ${channel.name}.")
                WatchAttemptResult.Accepted
            } else {
                appendDebug("Watch heartbeat rejected for ${channel.name}; configuration may be stale.")
                WatchAttemptResult.Rejected
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            ensureCurrentOperation()
            error.throwIfInvalidToken()
            val message = error.message ?: "Twitch watch request failed."
            appendActivity(RuntimePhase.Error, "Watch event failed", message)
            WatchAttemptResult.Failed(message)
        }
    }

    private suspend fun updateProgress(
        session: StoredTwitchSession,
        campaign: Campaign,
        campaigns: List<Campaign>,
        channel: Channel,
    ): CampaignProgressRefresh {
        val progressResult = runCatchingCancellable {
            twitchApiClient.currentDrop(session, channel.id)
        }.onFailure { it.throwIfInvalidToken() }
        ensureCurrentOperation()
        val failure = progressResult.exceptionOrNull()
        if (failure != null) {
            appendDebug("Progress observation unavailable for ${channel.name}; current watch retained.")
            return CampaignProgressRefresh(
                campaigns = campaigns,
                campaign = campaign,
                observation = ProgressObservation.Unavailable(
                    failure.message ?: "Twitch progress could not be checked.",
                ),
            )
        }
        val progress = progressResult.getOrNull()
        if (progress == null) {
            appendDebug("Progress observation reported no active drop for ${channel.name}.")
            return CampaignProgressRefresh(
                campaigns = campaigns,
                campaign = campaign,
                observation = ProgressObservation.NoActiveDrop,
            )
        }
        appendDebug(
            "Progress observation for ${channel.name}: drop ${progress.dropId.take(80)} at ${progress.currentMinutes} minutes.",
        )
        val reportedCampaign = campaigns.firstOrNull { candidate ->
            candidate.drops.any { drop -> drop.id == progress.dropId }
        }
        if (reportedCampaign == null) {
            appendActivity(
                RuntimePhase.Watching,
                "Progress belongs to an unknown drop",
                "Twitch reported drop ${progress.dropId} (${progress.currentMinutes}m) on ${channel.name}; retaining current work until inventory refresh.",
            )
            return CampaignProgressRefresh(
                campaigns = campaigns,
                campaign = campaign,
                reportedDropId = progress.dropId,
                observation = ProgressObservation.UnexpectedDrop,
            )
        }
        val applied = reportedCampaign.applyTwitchProgress(progress)
        if (applied !is TwitchProgressUpdate.Updated) {
            return CampaignProgressRefresh(
                campaigns = campaigns,
                campaign = campaign,
                reportedDropId = progress.dropId,
                observation = ProgressObservation.UnexpectedDrop,
            )
        }
        return CampaignProgressRefresh(
            campaigns = campaigns.replaceCampaign(applied.campaign),
            campaign = applied.campaign,
            reportedDropId = progress.dropId,
            observation = ProgressObservation.Confirmed,
        )
    }

    private suspend fun claimDrop(
        session: StoredTwitchSession,
        campaign: Campaign,
        drop: CampaignDrop,
    ): ClaimApplication {
        val operationGuard = currentCoroutineContext()[RuntimeOperationGuard]
        val result = dropClaimHandler.claim(session, campaign, drop) {
            operationGuard?.ensureCurrent()
        }
        ensureCurrentOperation()
        val updatedCampaign = if (result.isTerminalSuccess) {
            if (result.shouldCountAsNewClaim) {
                operationGuard?.ensureCurrent()
                dropsClaimedThisSession += 1
            }
            val title = when (result.outcome) {
                RuntimeClaimOutcome.AlreadyClaimed -> "Drop already claimed"
                else -> "Drop claimed"
            }
            val generatedNote = if (result.resolved?.generatedClaimId == true) {
                "generated drop instance ID"
            } else {
                result.twitchStatus
            }
            appendActivity(RuntimePhase.Claiming, title, listOfNotNull(drop.name, generatedNote).joinToString(" - "))
            campaign.updateDrop(drop.id) {
                it.copy(
                    currentMinutes = it.requiredMinutes,
                    progress = 1f,
                    isClaimed = true,
                    canClaim = false,
                )
            }
        } else {
            val detail = listOfNotNull(drop.name, result.message, result.retryAt?.let { "retry after $it" })
                .joinToString(" - ")
            appendActivity(RuntimePhase.Error, result.failureTitle(), detail)
            result.retryAt?.let { retryAt ->
                appendDebug("Claim retry scheduled for ${drop.name} at $retryAt after ${result.outcome.name}.")
            }
            if (result.outcome == RuntimeClaimOutcome.InvalidToken) {
                throw TwitchApiException(
                    TwitchApiErrorType.InvalidToken,
                    result.message ?: "Twitch session expired while claiming a drop.",
                )
            }
            campaign
        }
        val activeDrop = updatedCampaign.drops.firstOrNull { it.id == drop.id } ?: drop
        updateSnapshot(
            phase = if (result.outcome == RuntimeClaimOutcome.InvalidToken) {
                RuntimePhase.Authenticating
            } else {
                RuntimePhase.Claiming
            },
            task = result.statusTask(drop),
        ) {
            it.copy(
                activeCampaign = updatedCampaign,
                activeDrop = activeDrop,
                progressSummary = listOf(updatedCampaign).progressSummary(),
                error = if (result.isTerminalSuccess) null else result.message,
            )
        }
        return ClaimApplication(updatedCampaign, result)
    }

    private fun firstClaimableDrop(
        session: StoredTwitchSession,
        campaign: Campaign,
    ): CampaignDrop? {
        return campaign.claimableDropsInEarningOrder().firstOrNull { drop ->
            dropClaimHandler.suppressionFor(session, campaign, drop) == null
        }
    }

    private fun markSelected(campaigns: List<Campaign>, settings: AppSettings): List<Campaign> =
        campaigns.map { campaign ->
            val selected = settings.isCampaignSelected(campaign)
            if (campaign.selected == selected) campaign else campaign.copy(selected = selected)
        }

    private fun isCurrentAuthentication(authGeneration: Long): Boolean =
        authGeneration == authRunGeneration

    private fun ensureCurrentAuthentication(authGeneration: Long) {
        if (!isCurrentAuthentication(authGeneration)) {
            throw CancellationException("Twitch authentication was replaced by a newer request.")
        }
    }

    private fun isCurrentMiningRun(
        expectedSessionGeneration: Long,
        runGeneration: Long,
    ): Boolean =
        expectedSessionGeneration == sessionGeneration &&
            runGeneration == miningRunGeneration

    private fun ensureCurrentMiningRun(
        expectedSessionGeneration: Long,
        runGeneration: Long,
    ) {
        if (!isCurrentMiningRun(expectedSessionGeneration, runGeneration)) {
            throw CancellationException("Mining work was replaced by a newer runtime operation.")
        }
    }

    private fun isCurrentInventoryRefresh(
        expectedSessionGeneration: Long,
        refreshGeneration: Long,
    ): Boolean =
        expectedSessionGeneration == sessionGeneration &&
            refreshGeneration == inventoryRefreshRunGeneration

    private fun ensureCurrentInventoryRefresh(
        expectedSessionGeneration: Long,
        refreshGeneration: Long,
    ) {
        if (!isCurrentInventoryRefresh(expectedSessionGeneration, refreshGeneration)) {
            throw CancellationException("Inventory refresh was replaced by a newer runtime operation.")
        }
    }

    private suspend fun ensureCurrentOperation() {
        currentCoroutineContext()[RuntimeOperationGuard]?.ensureCurrent()
    }

    private fun enqueueCoalesced(command: RuntimeCommand): Boolean {
        val key = command.coalescingKey ?: return runtimeCommands.trySend(command).isSuccess
        synchronized(pendingCommandLock) {
            if (!pendingCommandKeys.add(key)) return true
            if (runtimeCommands.trySend(command).isSuccess) return true
            pendingCommandKeys.remove(key)
            return false
        }
    }

    private suspend fun awaitIdleWakeup(
        currentSettings: AppSettings,
        handledInventoryRefreshRequestId: Long,
        timeoutMillis: Long,
    ): RuntimeWakeup = coroutineScope {
        val settingsChange = async {
            settingsRepository.settings.first { candidate -> candidate != currentSettings }
        }
        val inventoryRefresh = async {
            inventoryRefreshRequests.first { requestId ->
                requestId != handledInventoryRefreshRequestId
            }
        }
        val timeout = async {
            delay(timeoutMillis.coerceAtLeast(0L))
        }
        try {
            select {
                settingsChange.onAwait { RuntimeWakeup.Settings }
                inventoryRefresh.onAwait { RuntimeWakeup.InventoryRefresh }
                timeout.onAwait { RuntimeWakeup.Timeout }
            }
        } finally {
            settingsChange.cancel()
            inventoryRefresh.cancel()
            timeout.cancel()
        }
    }

    private suspend fun awaitActiveWakeup(
        currentSettings: AppSettings,
        handledChannelControlRequestId: Long,
        handledInventoryRefreshRequestId: Long,
        higherPriorityCheck: Deferred<Result<SelectedCampaignWork?>>?,
        deadline: Instant,
    ): RuntimeWakeup = coroutineScope {
        val settingsChange = async {
            settingsRepository.settings.first { candidate -> candidate != currentSettings }
        }
        val channelControl = async {
            channelControlRequests.first { request -> request.id != handledChannelControlRequestId }
        }
        val inventoryRefresh = async {
            inventoryRefreshRequests.first { requestId ->
                requestId != handledInventoryRefreshRequestId
            }
        }
        val timeout = async {
            delay(Duration.between(now(), deadline).toMillis().coerceAtLeast(0L))
        }
        try {
            select {
                settingsChange.onAwait { RuntimeWakeup.Settings }
                channelControl.onAwait { RuntimeWakeup.ChannelControl }
                inventoryRefresh.onAwait { RuntimeWakeup.InventoryRefresh }
                higherPriorityCheck?.onAwait { RuntimeWakeup.HigherPriority }
                timeout.onAwait { RuntimeWakeup.Timeout }
            }
        } finally {
            settingsChange.cancel()
            channelControl.cancel()
            inventoryRefresh.cancel()
            timeout.cancel()
        }
    }

    private suspend fun awaitUsableNetwork(): Boolean {
        if (networkStatusProvider.advisoryOnly) {
            waitingForNetwork = false
            return false
        }
        if (networkStatusProvider.isOnline.value) {
            waitingForNetwork = false
            return false
        }
        if (!waitingForNetwork) {
            waitingForNetwork = true
            updateSnapshot(RuntimePhase.Idle, "Waiting for internet connection") {
                it.copy(
                    channels = it.channels.map { channel -> channel.copy(watching = false) },
                    currentChannel = null,
                    error = null,
                )
            }
            appendActivity(
                RuntimePhase.Idle,
                "Internet connection unavailable",
                "Network work is paused until the local host can reach Twitch again.",
            )
        }
        networkStatusProvider.awaitOnline()
        waitingForNetwork = false
        appendActivity(RuntimePhase.Connecting, "Internet connection restored")
        return true
    }

    private suspend fun awaitUsableNetworkBefore(deadline: Instant): Boolean {
        val remainingMillis = Duration.between(now(), deadline).toMillis()
        if (remainingMillis <= 0L) {
            return false
        }
        val available = withTimeoutOrNull(remainingMillis) {
            awaitUsableNetwork()
            true
        } ?: false
        if (!available) {
            waitingForNetwork = false
        }
        return available
    }

    private suspend fun reportUnexpectedMinerFailure(error: Throwable) {
        updateSnapshot(RuntimePhase.Error, "Local miner stopped after an unexpected error") {
            it.copy(
                channels = it.channels.map { channel -> channel.copy(watching = false) },
                currentChannel = null,
                channelSearchInProgress = false,
                error = error.message ?: "Unexpected local miner failure.",
            )
        }
        appendActivity(
            RuntimePhase.Error,
            "Local miner stopped after unexpected error",
            error.message,
        )
    }

    private suspend fun updateSnapshot(
        phase: RuntimePhase,
        task: String,
        transform: (RuntimeSnapshot) -> RuntimeSnapshot = { it },
    ) {
        val guard = currentCoroutineContext()[RuntimeOperationGuard]
        guard?.ensureCurrent()
        val now = now()
        _snapshot.update { current ->
            guard?.ensureCurrent()
            transform(current).copy(
                phase = phase,
                currentTask = task,
                lastUpdate = now,
                dropsClaimedThisSession = dropsClaimedThisSession,
            )
        }
    }

    private suspend fun appendActivity(
        phase: RuntimePhase,
        title: String,
        detail: String? = null,
    ) {
        val guard = currentCoroutineContext()[RuntimeOperationGuard]
        guard?.ensureCurrent()
        val entry = RuntimeActivity(now(), phase, title, detail)
        logRepository.append(if (phase == RuntimePhase.Error) "ERROR" else "INFO", entry.toLine())
        guard?.ensureCurrent()
        _snapshot.update {
            guard?.ensureCurrent()
            it.copy(
                activity = (it.activity + entry).takeLast(MaxRuntimeActivityEntries),
                lastUpdate = entry.timestamp,
            )
        }
    }

    private suspend fun appendDebug(message: String) {
        if (settingsRepository.settings.value.debugLogging) {
            logRepository.append("DEBUG", message)
        }
    }
}

private class RuntimeOperationGuard(
    private val isCurrent: () -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    fun ensureCurrent() {
        if (!isCurrent()) {
            throw CancellationException("Runtime operation was superseded before committing local state.")
        }
    }

    companion object Key : CoroutineContext.Key<RuntimeOperationGuard>
}

private fun Campaign.startUnlinkedProgressProbe(settings: AppSettings): UnlinkedProgressProbe? =
    if (canTryUnlinkedLocally) {
        UnlinkedProgressProbe.start(this, settings, Instant.now())
    } else {
        null
    }

private fun Campaign.startLinkedProgressProbe(settings: AppSettings): LinkedProgressProbe? =
    if (canEarnLocally) {
        LinkedProgressProbe.start(this, settings, Instant.now())
    } else {
        null
    }

private fun Campaign.watchingTask(
    channel: Channel,
    unlinkedProgressProbe: UnlinkedProgressProbe?,
): String =
    when {
        unlinkedProgressProbe != null && !unlinkedProgressProbe.progressDetected ->
            "Checking unlinked progress on ${channel.name}"

        canTryUnlinkedLocally -> "Watching unlinked game on ${channel.name}"
        else -> "Watching ${channel.name}"
    }

private fun RuntimeSnapshot.matchesActiveWatch(
    campaign: Campaign,
    channel: Channel,
    drop: CampaignDrop,
    task: String,
): Boolean =
    phase == RuntimePhase.Watching &&
        currentTask == task &&
        currentChannel == channel &&
        activeCampaign == campaign &&
        activeDrop == drop &&
        !channelSearchInProgress &&
        error == null

internal sealed class TwitchProgressUpdate {
    data class Updated(val campaign: Campaign) : TwitchProgressUpdate()
    object UnexpectedDrop : TwitchProgressUpdate()
}

/**
 * Applies Twitch-reported progress to the matching drop. If Twitch reports progress for a drop
 * that is not part of this campaign, this is a safe no-op signalled by [TwitchProgressUpdate.UnexpectedDrop]
 * so the caller can log enough detail to diagnose the mismatch.
 */
internal fun Campaign.applyTwitchProgress(progress: CurrentDropProgress): TwitchProgressUpdate {
    if (drops.none { it.id == progress.dropId }) {
        return TwitchProgressUpdate.UnexpectedDrop
    }
    return TwitchProgressUpdate.Updated(
        updateDrop(progress.dropId) { drop ->
            val required = drop.requiredMinutes.coerceAtLeast(0)
            val reported = progress.currentMinutes.coerceIn(0, required)
            drop.copy(
                currentMinutes = reported,
                progress = if (required == 0) 0f else reported.toFloat() / required,
                canClaim = required > 0 && reported >= required && !drop.isClaimed,
            )
        },
    )
}

internal data class UnlinkedProgressProbe(
    val campaignId: String,
    val startedAt: Instant,
    val checkAt: Instant,
    val baselineMinutes: Int,
    val progressDetected: Boolean = false,
    val confirmedObservations: Int = 0,
) {
    val checkWindowLabel: String
        get() = Duration.between(startedAt, checkAt).runtimeLabel()

    fun observe(
        campaign: Campaign,
        settings: AppSettings,
        now: Instant,
    ): UnlinkedProgressProbeResult {
        val currentMinutes = campaign.unlinkedProbeMinutes
        val progressDetectedNow = currentMinutes > baselineMinutes
        if (progressDetectedNow) {
            return UnlinkedProgressProbeResult.Continue(
                probe = start(
                    campaign = campaign,
                    settings = settings,
                    now = now,
                    progressDetected = true,
                ),
                progressDetectedNow = true,
            )
        }
        val updated = copy(
            confirmedObservations = confirmedObservations + 1,
        )
        return if (
            updated.confirmedObservations >= MinimumConfirmedProgressObservations &&
            !now.isBefore(checkAt)
        ) {
            UnlinkedProgressProbeResult.Stalled(
                checkedAt = now,
                elapsedLabel = Duration.between(startedAt, now).runtimeLabel(),
                progressHadBeenDetected = progressDetected,
            )
        } else {
            UnlinkedProgressProbeResult.Continue(
                probe = updated,
                progressDetectedNow = progressDetectedNow,
            )
        }
    }

    companion object {
        fun start(
            campaign: Campaign,
            settings: AppSettings,
            now: Instant,
            progressDetected: Boolean = false,
        ): UnlinkedProgressProbe {
            val configuredDelay = Duration.ofSeconds(
                settings.watchIntervalSeconds.toLong() * UnlinkedProgressCheckIntervals,
            )
            val checkDelay = if (progressDetected) {
                configuredDelay
                    .coerceAtLeast(MinLinkedProgressCheckDelay)
                    .coerceAtMost(MaxLinkedProgressCheckDelay)
            } else {
                configuredDelay
                    .coerceAtLeast(MinUnlinkedProgressCheckDelay)
                    .coerceAtMost(MaxUnlinkedProgressCheckDelay)
            }
            return UnlinkedProgressProbe(
                campaignId = campaign.id,
                startedAt = now,
                checkAt = now.plus(checkDelay),
                baselineMinutes = campaign.unlinkedProbeMinutes,
                progressDetected = progressDetected,
            )
        }
    }
}

internal data class LinkedProgressProbe(
    val campaignId: String,
    val startedAt: Instant,
    val checkAt: Instant,
    val baselineMinutes: Int,
    val confirmedObservations: Int = 0,
) {
    fun observe(
        campaign: Campaign,
        settings: AppSettings,
        now: Instant,
    ): LinkedProgressProbeResult {
        val currentMinutes = campaign.unlinkedProbeMinutes
        if (currentMinutes > baselineMinutes) {
            return LinkedProgressProbeResult.Continue(
                probe = start(campaign, settings, now),
                progressDetectedNow = true,
            )
        }
        val updated = copy(confirmedObservations = confirmedObservations + 1)
        return if (
            updated.confirmedObservations >= MinimumConfirmedProgressObservations &&
            !now.isBefore(checkAt)
        ) {
            LinkedProgressProbeResult.Stalled(
                checkedAt = now,
                elapsedLabel = Duration.between(startedAt, now).runtimeLabel(),
            )
        } else {
            LinkedProgressProbeResult.Continue(updated, progressDetectedNow = false)
        }
    }

    companion object {
        fun start(campaign: Campaign, settings: AppSettings, now: Instant): LinkedProgressProbe {
            val configuredDelay = Duration.ofSeconds(
                settings.watchIntervalSeconds.toLong() * UnlinkedProgressCheckIntervals,
            )
            val checkDelay = configuredDelay
                .coerceAtLeast(MinLinkedProgressCheckDelay)
                .coerceAtMost(MaxLinkedProgressCheckDelay)
            return LinkedProgressProbe(
                campaignId = campaign.id,
                startedAt = now,
                checkAt = now.plus(checkDelay),
                baselineMinutes = campaign.unlinkedProbeMinutes,
            )
        }
    }
}

internal sealed class LinkedProgressProbeResult {
    data class Continue(
        val probe: LinkedProgressProbe,
        val progressDetectedNow: Boolean,
    ) : LinkedProgressProbeResult()

    data class Stalled(
        val checkedAt: Instant,
        val elapsedLabel: String,
    ) : LinkedProgressProbeResult()
}

internal sealed class UnlinkedProgressProbeResult {
    data class Continue(
        val probe: UnlinkedProgressProbe,
        val progressDetectedNow: Boolean,
    ) : UnlinkedProgressProbeResult()

    data class Stalled(
        val checkedAt: Instant,
        val elapsedLabel: String,
        val progressHadBeenDetected: Boolean,
    ) : UnlinkedProgressProbeResult()
}

internal enum class ProgressRecoveryAction {
    RenewWatchConfiguration,
    AbandonChannel,
}

internal object ProgressRecoveryPolicy {
    fun afterConfirmedStall(watchConfigurationRenewals: Int): ProgressRecoveryAction =
        if (watchConfigurationRenewals <= 0) {
            ProgressRecoveryAction.RenewWatchConfiguration
        } else {
            ProgressRecoveryAction.AbandonChannel
        }
}

internal object ActiveWatchGuard {
    fun shouldStopForExcludedCampaign(settings: AppSettings, campaign: Campaign): Boolean =
        settings.isCampaignExcluded(campaign)
}

internal data class CampaignIdleStatus(
    val task: String,
    val activityTitle: String,
    val detail: String,
)

internal object CampaignTemporalPolicy {
    fun nextEligibilityBoundary(campaigns: List<Campaign>, now: Instant): Instant? = campaigns
        .asSequence()
        .filter { campaign -> campaign.drops.any { drop -> !drop.isClaimed } }
        .flatMap { campaign ->
            sequence {
                campaign.startsAt?.takeIf { it.isAfter(now) }?.let { yield(it) }
                campaign.drops.forEach { drop ->
                    if (!drop.isClaimed && !drop.hasCompletedProgress) {
                        drop.startsAt?.takeIf { it.isAfter(now) }?.let { yield(it) }
                    }
                }
            }
        }
        .minOrNull()

    fun idleStatus(campaign: Campaign, now: Instant): CampaignIdleStatus {
        val nextBoundary = nextEligibilityBoundary(listOf(campaign), now)
        return when {
            nextBoundary != null -> CampaignIdleStatus(
                task = "Waiting for next scheduled drop",
                activityTitle = "Campaign has later scheduled drops",
                detail = "${campaign.name}; next eligibility boundary is $nextBoundary.",
            )

            campaign.drops.any { drop -> !drop.isClaimed && (drop.canClaim || drop.hasCompletedProgress) } ->
                CampaignIdleStatus(
                    task = "Completed drop awaiting claim",
                    activityTitle = "Completed drop awaiting claim",
                    detail = campaign.name,
                )

            campaign.drops.any { drop -> !drop.isClaimed } -> CampaignIdleStatus(
                task = "No drop is currently open",
                activityTitle = "Campaign has no open drop",
                detail = campaign.name,
            )

            else -> CampaignIdleStatus(
                task = "Campaign completed",
                activityTitle = "Campaign completed",
                detail = campaign.name,
            )
        }
    }
}

internal object RuntimeTemporalSchedule {
    fun earliest(required: Instant, optional: Instant?): Instant =
        optional?.let { minOf(required, it) } ?: required

    fun nextIdleDeadline(
        campaigns: List<Campaign>,
        now: Instant,
        regularDeadline: Instant,
        inventoryRefreshAt: Instant,
        claimRetryAt: Instant?,
    ): Instant = listOfNotNull(
        regularDeadline,
        inventoryRefreshAt,
        claimRetryAt,
        CampaignTemporalPolicy.nextEligibilityBoundary(campaigns, now),
    ).minOrNull() ?: regularDeadline

    fun nextActiveDeadline(
        nextWatchAt: Instant,
        nextPromotionCheckAt: Instant,
        refreshAt: Instant,
        activeCampaignEndsAt: Instant?,
        activeDropEndsAt: Instant?,
    ): Instant = listOfNotNull(
        nextWatchAt,
        nextPromotionCheckAt,
        refreshAt,
        activeCampaignEndsAt,
        activeDropEndsAt,
    ).minOrNull() ?: refreshAt
}

internal object RuntimeIdleWait {
    suspend fun awaitSettingsChangeOrTimeout(
        settings: Flow<AppSettings>,
        currentSettings: AppSettings,
        timeoutMillis: Long,
    ): AppSettings? = withTimeoutOrNull(timeoutMillis) {
        settings.first { candidate -> candidate != currentSettings }
    }
}

internal object CampaignPrioritySelector {
    fun select(
        settings: AppSettings,
        campaigns: List<Campaign>,
        now: Instant = Instant.now(),
    ): Campaign? {
        return candidates(settings, campaigns, now).firstOrNull()
    }

    fun candidates(
        settings: AppSettings,
        campaigns: List<Campaign>,
        now: Instant = Instant.now(),
    ): List<Campaign> {
        return when (val decision = initialDecision(settings, campaigns, now)) {
            is CampaignCandidateDecision.Try -> decision.candidates
            is CampaignCandidateDecision.Idle -> emptyList()
        }
    }

    fun initialDecision(
        settings: AppSettings,
        campaigns: List<Campaign>,
        now: Instant = Instant.now(),
    ): CampaignCandidateDecision {
        val selectableCampaigns = campaigns.withoutExcludedCampaigns(settings)
        orderedDecisions(settings, selectableCampaigns, now).firstOrNull()?.let { return it }

        if (!settings.hasGamePriority) {
            return CampaignCandidateDecision.Idle(
                task = "No available campaign can be mined",
                detail = if (settings.fallbackToOtherGames) {
                    "No linked or unlinked campaign has usable work for this session."
                } else {
                    "Auto Mode found no linked active campaign with remaining or claimable drops."
                },
            )
        }

        val prioritiesComplete = prioritizedGamesComplete(settings, selectableCampaigns)
        val prioritiesExcluded = prioritizedGamesCompleteOrExcluded(settings, campaigns)
        if (!settings.fallbackToOtherGames) {
            return when {
                prioritiesComplete -> CampaignCandidateDecision.Idle(
                    task = "All prioritized games are complete",
                    detail = "Fallback to other games is disabled.",
                    activityTitle = "Priority mining idle",
                )

                prioritiesExcluded -> CampaignCandidateDecision.Idle(
                    task = "Prioritized campaigns are excluded",
                    detail = "Fallback to other games is disabled.",
                    activityTitle = "Priority mining idle",
                )

                else -> CampaignCandidateDecision.Idle(
                    task = "No prioritized game can be mined",
                    detail = "Prioritized games have no usable linked campaign for this session, and fallback is disabled.",
                )
            }
        }

        return CampaignCandidateDecision.Idle(
            task = "No fallback campaign can be mined",
            detail = "Priority and all linked or unlinked fallback stages have no usable work for this session.",
            activityTitle = "Fallback idle",
        )
    }

    fun afterNoPrioritizedChannelDecision(
        settings: AppSettings,
        campaigns: List<Campaign>,
    ): CampaignCandidateDecision =
        afterNoChannelDecision(settings, campaigns, CampaignSelectionMode.Prioritized)

    fun afterNoChannelDecision(
        settings: AppSettings,
        campaigns: List<Campaign>,
        mode: CampaignSelectionMode,
        now: Instant = Instant.now(),
    ): CampaignCandidateDecision {
        val selectableCampaigns = campaigns.withoutExcludedCampaigns(settings)
        if (!settings.fallbackToOtherGames) {
            return noChannelDecision(mode)
        }

        return orderedDecisions(settings, selectableCampaigns, now)
            .firstOrNull { decision ->
                decision.mode.priorityRank(settings) > mode.priorityRank(settings)
            }
            ?: CampaignCandidateDecision.Idle(
                task = "No fallback game has an eligible live channel",
                detail = "Priority and all linked or unlinked fallback stages currently have no eligible live channel.",
                activityTitle = "Fallback idle",
                retry = CampaignIdleRetry.WatchInterval,
            )
    }

    fun noChannelDecision(mode: CampaignSelectionMode): CampaignCandidateDecision.Idle =
        when (mode) {
            CampaignSelectionMode.Auto -> CampaignCandidateDecision.Idle(
                task = "No eligible live channel found",
                detail = "Auto Mode found eligible campaigns, but no eligible live channel is currently available.",
                activityTitle = "No eligible live channel",
                retry = CampaignIdleRetry.WatchInterval,
            )

            CampaignSelectionMode.Prioritized -> CampaignCandidateDecision.Idle(
                task = "No prioritized games have eligible live channels",
                detail = "Fallback to other games is disabled.",
                activityTitle = "Priority mining idle",
                retry = CampaignIdleRetry.WatchInterval,
            )

            CampaignSelectionMode.LinkedClaimedProgress,
            CampaignSelectionMode.UnlinkedClaimedProgress,
            CampaignSelectionMode.LinkedViewingProgress,
            CampaignSelectionMode.UnlinkedViewingProgress,
            CampaignSelectionMode.LinkedFallback,
            CampaignSelectionMode.Unlinked -> CampaignCandidateDecision.Idle(
                task = "No fallback game has an eligible live channel",
                detail = "Priority and all linked or unlinked fallback stages currently have no eligible live channel.",
                activityTitle = "Fallback idle",
                retry = CampaignIdleRetry.WatchInterval,
            )
        }

    fun prioritizedCandidates(
        settings: AppSettings,
        campaigns: List<Campaign>,
        now: Instant = Instant.now(),
    ): List<Campaign> {
        val earnableCampaigns = campaigns
            .withoutExcludedCampaigns(settings)
            .filter { campaign ->
                !campaign.isLocallyComplete &&
                    campaign.watchableDrop(now = now) != null &&
                    (
                        campaign.canEarnLocallyAt(now) ||
                            (settings.fallbackToOtherGames && campaign.canTryUnlinkedLocallyAt(now))
                        )
            }
        return settings.selectedGamePriority.flatMap { gameName ->
            earnableCampaigns.filter { campaign ->
                campaign.gameName.equals(gameName, ignoreCase = true)
            }.sortedWith(campaignProgressComparator)
        }
    }

    fun autoFallbackCandidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> =
        fallbackCandidates(settings, campaigns)
            .filter { campaign -> campaign.linked }

    fun unlinkedCandidates(settings: AppSettings, campaigns: List<Campaign>): List<Campaign> =
        if (settings.fallbackToOtherGames) {
            fallbackCandidates(settings, campaigns)
                .filter { campaign -> campaign.canTryUnlinkedLocally }
                .sortedWith(campaignProgressComparator)
        } else {
            emptyList()
        }

    fun higherPriorityDecisions(
        settings: AppSettings,
        campaigns: List<Campaign>,
        currentMode: CampaignSelectionMode,
        currentCampaign: Campaign? = null,
        now: Instant = Instant.now(),
    ): List<CampaignCandidateDecision.Try> {
        if (currentMode == CampaignSelectionMode.Prioritized && currentCampaign != null) {
            val currentIndex = settings.gamePriorityIndex(currentCampaign.gameName) ?: return emptyList()
            val earlierCandidates = prioritizedCandidates(settings, campaigns, now).filter { campaign ->
                val candidateIndex = settings.gamePriorityIndex(campaign.gameName)
                candidateIndex != null && candidateIndex < currentIndex
            }
            return if (earlierCandidates.isEmpty()) {
                emptyList()
            } else {
                listOf(
                    CampaignCandidateDecision.Try(
                        mode = CampaignSelectionMode.Prioritized,
                        candidates = earlierCandidates,
                        task = "Checking earlier prioritized games",
                        detail = "Only games earlier than ${currentCampaign.gameName} are valid promotions.",
                    ),
                )
            }
        }
        return if (settings.fallbackToOtherGames) {
            orderedDecisions(settings, campaigns, now)
                .filter { decision ->
                    decision.mode.priorityRank(settings) < currentMode.priorityRank(settings)
                }
        } else {
            emptyList()
        }
    }

    fun modeForCampaign(
        settings: AppSettings,
        campaign: Campaign,
        now: Instant = Instant.now(),
    ): CampaignSelectionMode? {
        val canEarn = campaign.canEarnLocallyAt(now) && campaign.watchableDrop(now = now) != null
        val canTryUnlinked = campaign.canTryUnlinkedLocallyAt(now) && campaign.watchableDrop(now = now) != null
        if (settings.isGamePrioritized(campaign.gameName)) {
            return if (
                canEarn ||
                (settings.fallbackToOtherGames && canTryUnlinked)
            ) {
                CampaignSelectionMode.Prioritized
            } else {
                null
            }
        }
        if (!settings.fallbackToOtherGames) {
            return if (!settings.hasGamePriority && canEarn) {
                CampaignSelectionMode.Auto
            } else {
                null
            }
        }
        return when {
            canEarn && campaign.hasClaimedDropProgress ->
                CampaignSelectionMode.LinkedClaimedProgress
            canTryUnlinked && campaign.hasClaimedDropProgress ->
                CampaignSelectionMode.UnlinkedClaimedProgress
            canEarn && campaign.hasViewingProgress ->
                CampaignSelectionMode.LinkedViewingProgress
            canTryUnlinked && campaign.hasViewingProgress ->
                CampaignSelectionMode.UnlinkedViewingProgress
            canEarn && settings.hasGamePriority -> CampaignSelectionMode.LinkedFallback
            canEarn -> CampaignSelectionMode.Auto
            canTryUnlinked -> CampaignSelectionMode.Unlinked
            else -> null
        }
    }

    fun prioritizedGamesComplete(settings: AppSettings, campaigns: List<Campaign>): Boolean {
        if (!settings.hasGamePriority) {
            return false
        }
        val selectableCampaigns = campaigns
            .withoutExcludedCampaigns(settings)
            .filterNot { it.expired }
        return settings.selectedGamePriority.all { gameName ->
            val gameCampaigns = selectableCampaigns.filter { campaign ->
                campaign.gameName.equals(gameName, ignoreCase = true)
            }
            gameCampaigns.isNotEmpty() && gameCampaigns.all { it.isLocallyComplete }
        }
    }

    private fun prioritizedGamesCompleteOrExcluded(settings: AppSettings, campaigns: List<Campaign>): Boolean {
        if (!settings.hasGamePriority) {
            return false
        }
        val availableCampaigns = campaigns.filterNot { it.expired }
        val hasExcludedPrioritizedCampaign = availableCampaigns.any { campaign ->
            settings.isGamePrioritized(campaign.gameName) && settings.isCampaignExcluded(campaign)
        }
        if (!hasExcludedPrioritizedCampaign) {
            return false
        }
        return settings.selectedGamePriority.all { gameName ->
            val gameCampaigns = availableCampaigns.filter { campaign ->
                campaign.gameName.equals(gameName, ignoreCase = true)
            }
            gameCampaigns.isNotEmpty() &&
                gameCampaigns.all { campaign ->
                    settings.isCampaignExcluded(campaign) || campaign.isLocallyComplete
                }
        }
    }

    private fun orderedDecisions(
        settings: AppSettings,
        campaigns: List<Campaign>,
        now: Instant,
    ): List<CampaignCandidateDecision.Try> {
        val selectableCampaigns = campaigns.withoutExcludedCampaigns(settings)
        val decisions = mutableListOf<CampaignCandidateDecision.Try>()
        if (settings.hasGamePriority) {
            prioritizedCandidates(settings, selectableCampaigns, now)
                .takeIf { candidates -> candidates.isNotEmpty() }
                ?.let { candidates ->
                    decisions += CampaignCandidateDecision.Try(
                        mode = CampaignSelectionMode.Prioritized,
                        candidates = candidates,
                        task = "Selecting prioritized campaign",
                        detail = "Trying all prioritized games in saved order.",
                    )
                }
        }

        if (!settings.fallbackToOtherGames) {
            if (!settings.hasGamePriority) {
                autoCandidates(settings, selectableCampaigns, now)
                    .takeIf { candidates -> candidates.isNotEmpty() }
                    ?.let { candidates ->
                        decisions += CampaignCandidateDecision.Try(
                            mode = CampaignSelectionMode.Auto,
                            candidates = candidates,
                            task = "Auto Mode selecting campaign",
                            detail = "No game priority is set.",
                        )
                    }
            }
            return decisions
        }

        val fallbackCandidates = fallbackCandidates(settings, selectableCampaigns, now)
        val linkedClaimed = fallbackCandidates.filter { campaign ->
            campaign.linked && campaign.hasClaimedDropProgress
        }
        val unlinkedClaimed = fallbackCandidates.filter { campaign ->
            campaign.canTryUnlinkedLocallyAt(now) && campaign.hasClaimedDropProgress
        }
        val linkedViewing = fallbackCandidates.filter { campaign ->
            campaign.linked && !campaign.hasClaimedDropProgress && campaign.hasViewingProgress
        }
        val unlinkedViewing = fallbackCandidates.filter { campaign ->
            campaign.canTryUnlinkedLocallyAt(now) && !campaign.hasClaimedDropProgress && campaign.hasViewingProgress
        }
        val linkedFresh = fallbackCandidates.filter { campaign ->
            campaign.linked && !campaign.hasClaimedDropProgress && !campaign.hasViewingProgress
        }
        val unlinkedFresh = fallbackCandidates.filter { campaign ->
            campaign.canTryUnlinkedLocallyAt(now) && !campaign.hasClaimedDropProgress && !campaign.hasViewingProgress
        }

        val candidatesByPriority = mapOf(
            AutoModePriority.LinkedClaimedProgress to linkedClaimed,
            AutoModePriority.UnlinkedClaimedProgress to unlinkedClaimed,
            AutoModePriority.LinkedViewingProgress to linkedViewing,
            AutoModePriority.UnlinkedViewingProgress to unlinkedViewing,
            AutoModePriority.LinkedFresh to linkedFresh,
            AutoModePriority.UnlinkedFresh to unlinkedFresh,
        )
        settings.autoModePriorityOrder.forEach { priority ->
            decisions.addStage(
                mode = priority.selectionMode(settings),
                candidates = candidatesByPriority.getValue(priority),
                task = priority.selectionTask(settings),
                detail = "No higher-priority stream is currently available.",
            )
        }
        return decisions
    }

    private fun autoCandidates(
        settings: AppSettings,
        campaigns: List<Campaign>,
        now: Instant,
    ): List<Campaign> =
        campaigns
            .withoutExcludedCampaigns(settings)
            .filter { campaign ->
                campaign.canEarnLocallyAt(now) &&
                    !campaign.isLocallyComplete &&
                    campaign.watchableDrop(now = now) != null
            }

    private fun fallbackCandidates(
        settings: AppSettings,
        campaigns: List<Campaign>,
        now: Instant = Instant.now(),
    ): List<Campaign> =
        campaigns
            .withoutExcludedCampaigns(settings)
            .filter { campaign ->
                !campaign.isLocallyComplete &&
                    campaign.watchableDrop(now = now) != null &&
                    (campaign.canEarnLocallyAt(now) || campaign.canTryUnlinkedLocallyAt(now)) &&
                    (!settings.hasGamePriority || !settings.isGamePrioritized(campaign.gameName))
            }

    private fun MutableList<CampaignCandidateDecision.Try>.addStage(
        mode: CampaignSelectionMode,
        candidates: List<Campaign>,
        task: String,
        detail: String,
    ) {
        if (candidates.isNotEmpty()) {
            this += CampaignCandidateDecision.Try(
                mode = mode,
                candidates = candidates.sortedWith(campaignProgressComparator),
                task = task,
                detail = detail,
            )
        }
    }

}

internal sealed class CampaignCandidateDecision {
    data class Try(
        val mode: CampaignSelectionMode,
        val candidates: List<Campaign>,
        val task: String,
        val detail: String,
    ) : CampaignCandidateDecision()

    data class Idle(
        val task: String,
        val detail: String,
        val activityTitle: String = "No available work",
        val retry: CampaignIdleRetry = CampaignIdleRetry.InventoryRefresh,
    ) : CampaignCandidateDecision()
}

internal enum class CampaignSelectionMode {
    Auto,
    Prioritized,
    LinkedClaimedProgress,
    UnlinkedClaimedProgress,
    LinkedViewingProgress,
    UnlinkedViewingProgress,
    LinkedFallback,
    Unlinked,
}

internal enum class CampaignIdleRetry {
    InventoryRefresh,
    WatchInterval,
}

private val CampaignSelectionMode.isLinkedFallback: Boolean
    get() = when (this) {
        CampaignSelectionMode.LinkedClaimedProgress,
        CampaignSelectionMode.LinkedViewingProgress,
        CampaignSelectionMode.LinkedFallback -> true

        else -> false
    }

private val CampaignSelectionMode.isUnlinked: Boolean
    get() = when (this) {
        CampaignSelectionMode.UnlinkedClaimedProgress,
        CampaignSelectionMode.UnlinkedViewingProgress,
        CampaignSelectionMode.Unlinked -> true

        else -> false
    }

private fun CampaignSelectionMode.priorityRank(settings: AppSettings): Int =
    if (this == CampaignSelectionMode.Prioritized) {
        0
    } else {
        settings.autoModePriorityOrder.indexOf(autoModePriority()) + 1
    }

private fun CampaignSelectionMode.autoModePriority(): AutoModePriority =
    when (this) {
        CampaignSelectionMode.Prioritized -> error("Prioritized games are outside Auto Mode ordering")
        CampaignSelectionMode.LinkedClaimedProgress -> AutoModePriority.LinkedClaimedProgress
        CampaignSelectionMode.UnlinkedClaimedProgress -> AutoModePriority.UnlinkedClaimedProgress
        CampaignSelectionMode.LinkedViewingProgress -> AutoModePriority.LinkedViewingProgress
        CampaignSelectionMode.UnlinkedViewingProgress -> AutoModePriority.UnlinkedViewingProgress
        CampaignSelectionMode.Auto,
        CampaignSelectionMode.LinkedFallback -> AutoModePriority.LinkedFresh
        CampaignSelectionMode.Unlinked -> AutoModePriority.UnlinkedFresh
    }

private fun AutoModePriority.selectionMode(settings: AppSettings): CampaignSelectionMode =
    when (this) {
        AutoModePriority.LinkedClaimedProgress -> CampaignSelectionMode.LinkedClaimedProgress
        AutoModePriority.UnlinkedClaimedProgress -> CampaignSelectionMode.UnlinkedClaimedProgress
        AutoModePriority.LinkedViewingProgress -> CampaignSelectionMode.LinkedViewingProgress
        AutoModePriority.UnlinkedViewingProgress -> CampaignSelectionMode.UnlinkedViewingProgress
        AutoModePriority.LinkedFresh -> if (settings.hasGamePriority) {
            CampaignSelectionMode.LinkedFallback
        } else {
            CampaignSelectionMode.Auto
        }
        AutoModePriority.UnlinkedFresh -> CampaignSelectionMode.Unlinked
    }

private fun AutoModePriority.selectionTask(settings: AppSettings): String =
    when (this) {
        AutoModePriority.LinkedClaimedProgress ->
            "Trying linked campaigns with claimed-drop progress"
        AutoModePriority.UnlinkedClaimedProgress ->
            "Trying unlinked campaigns with claimed-drop progress"
        AutoModePriority.LinkedViewingProgress ->
            "Trying linked campaigns with viewing progress"
        AutoModePriority.UnlinkedViewingProgress ->
            "Trying unlinked campaigns with viewing progress"
        AutoModePriority.LinkedFresh -> if (settings.hasGamePriority) {
            "Trying linked games outside priority"
        } else {
            "Auto Mode selecting linked game"
        }
        AutoModePriority.UnlinkedFresh -> "Trying unlinked games"
    }

private fun CampaignSelectionMode.selectionTask(candidate: Campaign): String =
    when (this) {
        CampaignSelectionMode.Auto -> "Auto Mode selected ${candidate.gameName}"
        CampaignSelectionMode.Prioritized -> "Selected ${candidate.gameName}"
        CampaignSelectionMode.LinkedClaimedProgress -> "Selected linked claimed-progress game ${candidate.gameName}"
        CampaignSelectionMode.UnlinkedClaimedProgress -> "Trying unlinked claimed-progress game ${candidate.gameName}"
        CampaignSelectionMode.LinkedViewingProgress -> "Selected linked viewing-progress game ${candidate.gameName}"
        CampaignSelectionMode.UnlinkedViewingProgress -> "Trying unlinked viewing-progress game ${candidate.gameName}"
        CampaignSelectionMode.LinkedFallback -> "Linked fallback selected ${candidate.gameName}"
        CampaignSelectionMode.Unlinked -> "Trying unlinked game ${candidate.gameName}"
    }

private fun CampaignSelectionMode.noChannelDetail(candidate: Campaign): String =
    when (this) {
        CampaignSelectionMode.Auto -> "${candidate.gameName}; trying next Auto Mode campaign."
        CampaignSelectionMode.Prioritized -> "${candidate.gameName}; trying next prioritized campaign."
        CampaignSelectionMode.LinkedClaimedProgress ->
            "${candidate.gameName}; trying the next linked campaign with claimed-drop progress."
        CampaignSelectionMode.UnlinkedClaimedProgress ->
            "${candidate.gameName}; trying the next unlinked campaign with claimed-drop progress."
        CampaignSelectionMode.LinkedViewingProgress ->
            "${candidate.gameName}; trying the next linked campaign with viewing progress."
        CampaignSelectionMode.UnlinkedViewingProgress ->
            "${candidate.gameName}; trying the next unlinked campaign with viewing progress."
        CampaignSelectionMode.LinkedFallback ->
            "${candidate.gameName}; trying next linked fallback campaign."
        CampaignSelectionMode.Unlinked -> "${candidate.gameName}; trying next unlinked game."
    }

private fun CampaignCandidateDecision.Idle.retryDelayMillis(settings: AppSettings): Long =
    when (retry) {
        CampaignIdleRetry.InventoryRefresh -> settings.inventoryRefreshMinutes * 60_000L
        CampaignIdleRetry.WatchInterval -> settings.watchIntervalSeconds * 1000L
    }

private fun Duration.runtimeLabel(): String {
    val totalSeconds = seconds.coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val secondsPart = totalSeconds % 60
    return when {
        minutes > 0L && secondsPart == 0L -> "$minutes min"
        minutes > 0L -> "$minutes min ${secondsPart}s"
        else -> "${totalSeconds}s"
    }
}

private sealed interface RuntimeCommand {
    data object StartAuthentication : RuntimeCommand
    data object ReplaceAuthentication : RuntimeCommand
    data class AuthenticationSucceeded(
        val authGeneration: Long,
        val session: StoredTwitchSession,
    ) : RuntimeCommand
    data object StartMining : RuntimeCommand
    data class StopMining(
        val awaitCompletion: Boolean = false,
        val completed: CompletableDeferred<Unit>? = null,
    ) : RuntimeCommand
    data object RefreshInventory : RuntimeCommand
    data class ResetSession(
        val completed: CompletableDeferred<Unit>? = null,
    ) : RuntimeCommand
    data class ExpireSession(
        val expectedSessionGeneration: Long,
        val message: String?,
    ) : RuntimeCommand
}

private val RuntimeCommand.label: String
    get() = when (this) {
        RuntimeCommand.StartAuthentication -> "start authentication"
        RuntimeCommand.ReplaceAuthentication -> "replace authentication"
        is RuntimeCommand.AuthenticationSucceeded -> "complete authentication"
        RuntimeCommand.StartMining -> "start mining"
        is RuntimeCommand.StopMining -> "stop mining"
        RuntimeCommand.RefreshInventory -> "refresh inventory"
        is RuntimeCommand.ResetSession -> "reset session"
        is RuntimeCommand.ExpireSession -> "expire session"
    }

private val RuntimeCommand.coalescingKey: String?
    get() = when (this) {
        RuntimeCommand.StartAuthentication -> "start-authentication"
        RuntimeCommand.ReplaceAuthentication -> "replace-authentication"
        RuntimeCommand.StartMining -> "start-mining"
        is RuntimeCommand.StopMining -> if (completed == null) "stop-mining" else null
        RuntimeCommand.RefreshInventory -> "refresh-inventory"
        is RuntimeCommand.ResetSession -> if (completed == null) "reset-session" else null
        is RuntimeCommand.ExpireSession -> "expire-session:$expectedSessionGeneration"
        is RuntimeCommand.AuthenticationSucceeded -> null
    }

private fun RuntimeCommand.completeExceptionally(error: Throwable) {
    when (this) {
        is RuntimeCommand.StopMining -> completed?.completeExceptionally(error)
        is RuntimeCommand.ResetSession -> completed?.completeExceptionally(error)
        else -> Unit
    }
}

private enum class RuntimeWakeup {
    Settings,
    ChannelControl,
    InventoryRefresh,
    HigherPriority,
    Timeout,
}

private sealed class CampaignWorkSelection {
    data class Selected(val work: SelectedCampaignWork) : CampaignWorkSelection()

    data class Idle(private val decision: CampaignCandidateDecision.Idle) : CampaignWorkSelection() {
        val task: String
            get() = decision.task
        val detail: String
            get() = decision.detail
        val activityTitle: String
            get() = decision.activityTitle

        fun retryDelayMillis(settings: AppSettings): Long = decision.retryDelayMillis(settings)
    }
}

private data class SelectedCampaignWork(
    val campaign: Campaign,
    val channel: Channel,
    val channels: List<Channel>,
    val mode: CampaignSelectionMode,
)

private data class CompatibleChannelSearch(
    val channels: List<Channel>,
    val task: String,
    val detail: String,
)

private data class ChannelControlRequest(
    val id: Long = 0L,
    val selectedChannelId: Long? = null,
)

private data class CampaignLoadResult(
    val campaigns: List<Campaign>,
    val settings: AppSettings,
    val failure: String? = null,
    val warning: String? = null,
)

private data class CampaignProgressRefresh(
    val campaigns: List<Campaign>,
    val campaign: Campaign,
    val reportedDropId: String? = null,
    val observation: ProgressObservation,
)

private data class ClaimApplication(
    val campaign: Campaign,
    val result: RuntimeClaimResult,
)

internal sealed interface ProgressObservation {
    val isConfirmed: Boolean

    data object Confirmed : ProgressObservation {
        override val isConfirmed: Boolean = true
    }

    data object NoActiveDrop : ProgressObservation {
        override val isConfirmed: Boolean = true
    }

    data object UnexpectedDrop : ProgressObservation {
        override val isConfirmed: Boolean = false
    }

    data class Unavailable(val message: String) : ProgressObservation {
        override val isConfirmed: Boolean = false
    }
}

private sealed interface WatchAttemptResult {
    data object Accepted : WatchAttemptResult
    data object Rejected : WatchAttemptResult
    data class Failed(val message: String) : WatchAttemptResult
}

private class ChannelDiscoveryUnavailableException(
    message: String,
    cause: Throwable,
) : IllegalStateException(message, cause)

internal object EligibleChannelSelector {
    fun select(channels: List<Channel>, skippedChannelIds: Set<Long>): Channel? =
        candidates(channels, skippedChannelIds).firstOrNull()

    fun candidates(channels: List<Channel>, skippedChannelIds: Set<Long>): List<Channel> =
        channels
            .asSequence()
            .filter { channel ->
                channel.online &&
                    channel.dropsEnabled &&
                    channel.id > 0L &&
                    !channel.broadcastId.isNullOrBlank() &&
                    channel.id !in skippedChannelIds
            }
            .sortedWith(
                compareByDescending<Channel> { it.aclBased }
                    .thenByDescending { it.viewers ?: -1 }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
            .toList()
}

internal object ChannelPickerSelection {
    fun findCompatibleChannel(
        channels: List<Channel>,
        channelId: Long,
    ): Channel? = EligibleChannelSelector.candidates(
        channels = channels,
        skippedChannelIds = emptySet(),
    ).firstOrNull { channel -> channel.id == channelId }
}

internal object RuntimeRetryBackoff {
    private val MaxDelay: Duration = Duration.ofMinutes(5)

    fun delayFor(consecutiveFailures: Int): Duration {
        val exponent = (consecutiveFailures.coerceAtLeast(1) - 1).coerceAtMost(5)
        val seconds = 15L shl exponent
        return Duration.ofSeconds(seconds).coerceAtMost(MaxDelay)
    }
}

private val Campaign.isLocallyComplete: Boolean
    get() = drops.isNotEmpty() && drops.all { drop ->
        drop.isClaimed || drop.hasCompletedProgress
    }

private val Campaign.hasClaimedDropProgress: Boolean
    get() = claimedDrops > 0 || drops.any { drop -> drop.isClaimed }

private val Campaign.hasViewingProgress: Boolean
    get() = drops.any { drop ->
        !drop.isClaimed &&
            drop.currentMinutes > 0 &&
            !drop.hasCompletedProgress
    }

private val Campaign.progressPriorityRank: Int
    get() = when {
        hasClaimedDropProgress -> 0
        hasViewingProgress -> 1
        else -> 2
    }

private val campaignProgressComparator =
    compareBy<Campaign> { campaign -> campaign.progressPriorityRank }
        .thenBy { campaign -> campaign.remainingMinutes }

private val Campaign.unlinkedProbeMinutes: Int
    get() = drops.sumOf { it.currentMinutes.coerceAtLeast(0) }

private fun List<Campaign>.withoutExcludedCampaigns(settings: AppSettings): List<Campaign> =
    filterNot { settings.isCampaignExcluded(it) }

private fun List<Campaign>.normalizedCampaignIds(): Set<String> =
    map { it.id.trim().lowercase() }
        .filter { it.isNotBlank() }
        .toSet()

private fun List<Campaign>.selectionLabel(limit: Int): String {
    val labels = take(limit).map { campaign ->
        "${campaign.gameName}: ${campaign.name.ifBlank { "Unnamed campaign" }}"
    }
    val suffix = if (size > limit) ", +${size - limit} more" else ""
    return labels.joinToString() + suffix
}

private fun Campaign.updateDrop(
    dropId: String,
    transform: (CampaignDrop) -> CampaignDrop,
): Campaign {
    val updatedDrops = drops.map { drop -> if (drop.id == dropId) transform(drop) else drop }
    return copy(
        drops = updatedDrops,
        claimedDrops = updatedDrops.count { it.isClaimed },
        totalDrops = updatedDrops.size,
    )
}

private fun List<Campaign>.replaceCampaign(campaign: Campaign): List<Campaign> =
    map { existing -> if (existing.id == campaign.id) campaign else existing }

private fun mergePartialInventory(
    previous: List<Campaign>,
    partial: List<Campaign>,
): List<Campaign> {
    val previousById = previous.associateBy(Campaign::id)
    val merged = partial.map { campaign ->
        val known = previousById[campaign.id]
        if (known != null && campaign.drops.isEmpty() && known.drops.isNotEmpty()) known else campaign
    }.toMutableList()
    val loadedIds = partial.mapTo(mutableSetOf(), Campaign::id)
    merged += previous.filterNot { it.id in loadedIds }
    return merged
}

private fun List<Channel>.markWatching(channelId: Long): List<Channel> =
    map { channel -> channel.copy(watching = channel.id == channelId) }

private fun List<Campaign>.progressSummary(): String {
    if (isEmpty()) {
        return "No campaign data"
    }
    val active = count { it.active }
    val claimed = sumOf { it.claimedDrops }
    val total = sumOf { it.totalDrops }.coerceAtLeast(1)
    val percent = ((claimed.toFloat() / total) * 100).toInt()
    return "$active active campaigns, $claimed/$total drops claimed ($percent%)"
}

private fun RuntimeClaimResult.failureTitle(): String =
    when (outcome) {
        RuntimeClaimOutcome.MissingIdentifier -> "Drop claim missing required ID"
        RuntimeClaimOutcome.NotClaimable -> "No claimable drops"
        RuntimeClaimOutcome.InvalidToken -> "Drop claim needs login renewal"
        RuntimeClaimOutcome.NetworkFailure -> "Drop claim network failure"
        RuntimeClaimOutcome.UnexpectedResponse -> "Drop claim response unexpected"
        RuntimeClaimOutcome.TerminalRejection -> "Drop claim eligibility rejected"
        RuntimeClaimOutcome.Suppressed -> "Drop claim retry delayed"
        RuntimeClaimOutcome.Failed -> "Drop claim failed"
        RuntimeClaimOutcome.Claimed,
        RuntimeClaimOutcome.AlreadyClaimed -> "Drop claimed"
    }

private fun RuntimeClaimResult.statusTask(drop: CampaignDrop): String =
    when (outcome) {
        RuntimeClaimOutcome.Claimed -> "Claimed ${drop.name}"
        RuntimeClaimOutcome.AlreadyClaimed -> "${drop.name} was already claimed"
        RuntimeClaimOutcome.Suppressed -> "Claim retry delayed for ${drop.name}"
        RuntimeClaimOutcome.MissingIdentifier -> "Cannot claim ${drop.name}"
        RuntimeClaimOutcome.NotClaimable -> "No claimable drops"
        RuntimeClaimOutcome.InvalidToken -> "Twitch session needs renewal"
        RuntimeClaimOutcome.NetworkFailure -> "Claim network failure for ${drop.name}"
        RuntimeClaimOutcome.UnexpectedResponse -> "Unexpected claim response for ${drop.name}"
        RuntimeClaimOutcome.TerminalRejection -> "Twitch rejected eligibility for ${drop.name}"
        RuntimeClaimOutcome.Failed -> "Claim failed for ${drop.name}"
    }

private suspend inline fun <T> runCatchingCancellable(
    crossinline block: suspend () -> T,
): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

private fun Throwable.throwIfInvalidToken() {
    if (this is TwitchApiException && type == TwitchApiErrorType.InvalidToken) {
        throw this
    }
}
