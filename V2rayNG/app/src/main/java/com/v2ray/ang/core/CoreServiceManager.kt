package com.v2ray.ang.core

import android.app.Activity
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.content.ContextCompat
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.contracts.IDialerService
import com.v2ray.ang.contracts.ServiceControl
import com.v2ray.ang.dto.ConnectionTestResult
import com.v2ray.ang.dto.OutboundTrafficStat
import com.v2ray.ang.dto.SubscriptionUpdateMessage
import com.v2ray.ang.dto.TestServiceMessage
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.BrowserDialerMode
import com.v2ray.ang.extension.delay
import com.v2ray.ang.extension.isNotNullEmpty
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.NotificationManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.SpeedtestManager
import com.v2ray.ang.helper.MessageHelper
import com.v2ray.ang.service.DialerNativeService
import com.v2ray.ang.service.DialerWebviewService
import com.v2ray.ang.service.NetworkMonitor
import com.v2ray.ang.util.CountryFlag
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.ProcessFinder
import java.lang.ref.SoftReference
import java.net.InetSocketAddress

object CoreServiceManager {

    private val coreController: CoreController = CoreNativeManager.newCoreController(CoreCallback())
    private val mMsgReceive = ReceiveMessageHandler()
    private var currentConfig: ProfileItem? = null
    private var processFinder: XrayProcessFinder? = null
    private var browserDialer: IDialerService? = null

    /** Written on the main thread and read by the reload thread, which tells a stop by it. */
    @Volatile
    private var networkMonitor: NetworkMonitor? = null
    private val connectionTestScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Griffin: how the connection test the service ran itself, see [scheduleAutoConnectionTest], waits and retries. */
    private const val AUTO_TEST_START_DELAY_MS = 1_200L
    private const val AUTO_TEST_RETRY_MS = 1_500L
    private const val AUTO_TEST_ATTEMPTS = 3

    /** Griffin: the last successful connection test of this session, for a main screen that registers after it. */
    @Volatile
    private var lastConnectionTest: ConnectionTestResult? = null

    @Volatile
    private var isReloading = false

    /** Tun descriptor the core was started with, null in the proxy only and root run modes. */
    private var currentVpnInterface: ParcelFileDescriptor? = null

    var serviceControl: SoftReference<ServiceControl>? = null
        set(value) {
            field = value
            val service = value?.get()?.getService()
            CoreNativeManager.initCoreEnv(service)
            if (service != null && processFinder == null) {
                processFinder = XrayProcessFinder(service)
                coreController.registerProcessFinder(processFinder)
            }
        }

    /**
     * Checks if the V2Ray service is running.
     * @return True if the service is running, false otherwise.
     */
    fun isRunning() = coreController.isRunning

    /**
     * Whether the service is up; see [ReloadOutcome.serviceRuns]. [isRunning] is Xray alone, which a
     * reload has stopped for a moment. What answers a state query, or decides between a start and a
     * stop, goes by this one.
     */
    fun isServiceRunning() = ReloadOutcome.serviceRuns(
        coreRunning = isRunning(),
        reloading = isReloading,
        stoppedMeanwhile = networkMonitor == null,
    )

    /**
     * Gets the name of the currently running server.
     * @return The name of the running server.
     */
    fun getRunningServerName() = currentConfig?.remarks.orEmpty()

    /**
     * Refer to the official documentation for [registerReceiver](https://developer.android.com/reference/androidx/core/content/ContextCompat#registerReceiver(android.content.Context,android.content.BroadcastReceiver,android.content.IntentFilter,int):
     * `registerReceiver(Context, BroadcastReceiver, IntentFilter, int)`.
     * Starts the V2Ray core service.
     */
    fun startCoreLoop(vpnInterface: ParcelFileDescriptor?): Boolean {
        if (isRunning()) {
            LogUtil.w(AppConfig.TAG, "StartCore-Manager: Core already running")
            return false
        }

        val service = getService()
        if (service == null) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Service is null")
            return false
        }

        try {
            doStartCoreLoop(service, vpnInterface)
            return true
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: $message", e)
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, userFacingReason(e))
            NotificationManager.cancelNotification()
            return false
        }
    }

    /**
     * A start or reload failure whose message is a localized resource string, meant for the main
     * screen. Every other failure reaches the UI without a reason: its message is technical and
     * belongs in the log, and the UI shows only resource text.
     */
    private class StartFailure(message: String) : RuntimeException(message)

    private fun userFacingReason(e: Exception): String = if (e is StartFailure) e.message.orEmpty() else ""

    @Throws(Exception::class)
    private fun doStartCoreLoop(service: Service, vpnInterface: ParcelFileDescriptor?) {
        val mFilter = IntentFilter(AppConfig.BROADCAST_ACTION_SERVICE)
        mFilter.addAction(Intent.ACTION_SCREEN_ON)
        mFilter.addAction(Intent.ACTION_SCREEN_OFF)
        mFilter.addAction(Intent.ACTION_USER_PRESENT)
        ContextCompat.registerReceiver(service, mMsgReceive, mFilter, Utils.receiverFlags())

        currentVpnInterface = vpnInterface
        launchCore(service, vpnInterface)
        startNetworkMonitor(service)
    }

    /**
     * Builds the configuration and starts Xray on it. A reload passes [goesOn], which says whether it still goes
     * on; it is asked once the configuration is built, the step that can take seconds.
     *
     * @return false when [goesOn] said no; nothing of this attempt was started then.
     */
    @Throws(Exception::class)
    private fun launchCore(
        service: Service,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean = false,
        goesOn: () -> Boolean = { true },
    ): Boolean {
        val guid = MmkvManager.getSelectServer() ?: error("No server selected")
        val config = MmkvManager.decodeServerConfig(guid) ?: error("Failed to decode server config")

        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Starting core loop for ${config.remarks}")
        val result = CoreConfigManager.getV2rayConfig(service, guid)
        LogUtil.d(AppConfig.TAG, result.content)
        if (!result.status) {
            if (result.localizedError) throw StartFailure(result.errorMessage)
            error(result.errorMessage.ifBlank { "Failed to get V2Ray config" })
        }
        // The build can take seconds, resolving server names: a stop, or a stop and a new start, that came
        // meanwhile owns the cores, which a reload must leave alone from here on.
        if (!goesOn()) return false

        // Starting a configuration ends the config tests, whatever the configuration: that is what a start
        // means to the user. The same goes for the test phase of subscription updates, whose downloads go on.
        // A reload keeps the session's place and leaves the tests alone.
        if (!isReload) {
            MessageHelper.sendMsg2TestService(service, TestServiceMessage(key = AppConfig.MSG_MEASURE_CONFIG_CANCEL))
            MessageHelper.sendMsg2SubscriptionService(
                service,
                SubscriptionUpdateMessage(AppConfig.MSG_SUB_UPDATE_CANCEL_TEST, forcedUpdate = false)
            )
        }
        launchNativeCore(service, config, result.content, vpnInterface, isReload)
        return true
    }

    @Throws(Exception::class)
    private fun launchNativeCore(
        service: Service,
        config: ProfileItem,
        content: String,
        vpnInterface: ParcelFileDescriptor?,
        isReload: Boolean,
    ) {
        currentConfig = config
        var tunFd = vpnInterface?.fd ?: 0
        val dialerMode = BrowserDialerMode.from(config.browserDialerMode)
        val dialerAddr = if (dialerMode != null) {
            "127.0.0.1:${Utils.findRandomFreePort()}"
        } else {
            ""
        }
        if (SettingsManager.isUsingHevTun()) {
            tunFd = 0
        }

        NotificationManager.showNotification(currentConfig)
        if (dialerAddr.isNotNullEmpty()) {
            CoreNativeManager.reconcileBrowserDialer(dialerAddr)
        }
        coreController.startLoop(content, tunFd)

        if (!isRunning()) {
            error("Core failed to start")
        }

        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }
        when (dialerMode) {
            BrowserDialerMode.OKHTTP -> {
                browserDialer = DialerNativeService()
                browserDialer!!.start(service, dialerAddr)
            }

            BrowserDialerMode.WEBVIEW -> {
                browserDialer = DialerWebviewService()
                browserDialer!!.start(service, dialerAddr)
            }

            else -> {}
        }

        if (!isReload) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_SUCCESS, "")
        }
        NotificationManager.startSpeedNotification()
        scheduleAutoConnectionTest()
        LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core started successfully")
    }

    /**
     * Stops the V2Ray core service.
     * Unregisters broadcast receivers, stops notifications, and shuts down plugins.
     * @return True if the core was stopped successfully, false otherwise.
     */
    fun stopCoreLoop(): Boolean {
        connectionTestScope.coroutineContext.cancelChildren()
        lastConnectionTest = null
        val service = getService() ?: return false

        networkMonitor?.unregister()
        networkMonitor = null
        currentVpnInterface = null
        // First, so that nothing further down posts the notification again: the proxy-only and the
        // root service tear down in onDestroy, out of the foreground already, where a post outlives them.
        NotificationManager.cancelNotification()

        if (isRunning()) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    coreController.stopLoop()
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop V2Ray loop", e)
                }
            }
        }

        // Close existing browser dialer
        CoreNativeManager.reconcileBrowserDialer("")
        if (browserDialer != null) {
            browserDialer!!.stop()
            browserDialer = null
        }

        MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")

        try {
            service.unregisterReceiver(mMsgReceive)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to unregister receiver", e)
        }

        return true
    }

    /**
     * Subscribes to upstream network changes for whichever run mode is active.
     * All three services share this manager, so the tunnel recovers from a handover in proxy only
     * and root mode as well, not just behind the VPN interface.
     */
    private fun startNetworkMonitor(service: Service) {
        if (networkMonitor != null) return

        val connectivity = service.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        // PattNG: the name alone, so that a monitor left registered does not keep the service alive.
        val runMode = service.javaClass.simpleName
        networkMonitor = NetworkMonitor(
            connectivity = connectivity,
            onUnderlyingNetworksChanged = { networks -> serviceControl?.get()?.setUnderlyingNetworks(networks) },
            claimHandover = { monitor -> claimReload(askedBy = monitor) },
            onHandover = { monitor -> reloadCore(askedBy = monitor) },
            describe = { "$runMode, guid=${MmkvManager.getSelectServer()}" },
        ).also { it.register() }
    }

    /**
     * Claims the reload a handover seen by [askedBy] asks for, and marks it under way, so that a second
     * handover does not begin one as well: only while [askedBy] is the service's monitor still, Xray runs
     * and no reload is under way. The handover rule of NetworkMonitor: it calls this under the gate its
     * unregister() closes first, so no reload is claimed once that has returned, and it has to be short. A
     * stop unregisters and clears the monitor and a new start brings its own, so a handover of an earlier
     * monitor reloads nothing; a stop that comes during the reload is told by goesOn and afterwards.
     */
    private fun claimReload(askedBy: NetworkMonitor): Boolean =
        ReloadOutcome.handoverReloads(askedBy, current = networkMonitor, coreRunning = isRunning(), reloading = isReloading)
            .also { if (it) isReloading = true }

    /**
     * Restarts the core in place after the upstream network changed: the service, the notification
     * and the VPN interface all stay up, so nothing of this is visible. PattNG: it runs once
     * [claimReload] has claimed the reload, and ends it.
     *
     * The config is rebuilt on purpose, outbound server domains are resolved while building it and
     * an address resolved on a network that is gone can be unusable on the new one.
     *
     * @return True if the core is running again.
     */
    private fun reloadCore(askedBy: NetworkMonitor): Boolean {
        val service = getService() ?: run {
            isReloading = false
            return false
        }

        try {
            val tunFd = currentVpnInterface

            connectionTestScope.coroutineContext.cancelChildren()
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload start...")

            coreController.stopLoop()
            if (launchCore(service, tunFd, isReload = true, goesOn = { ReloadOutcome.goesOn(askedBy = askedBy, current = networkMonitor) })) {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload finished")
            } else {
                LogUtil.i(AppConfig.TAG, "StartCore-Manager: Core reload given up, the service was stopped while the configuration was built")
            }
        } catch (e: Exception) {
            val message = e.message?.takeUnless { it.isBlank() } ?: e.javaClass.simpleName
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-Manager: ${service.javaClass.simpleName} reload failed: $message, guid=${MmkvManager.getSelectServer()}",
                e
            )
            // After a stop the screen has been told of it, and after a new start its state is that start's to report.
            if (networkMonitor === askedBy) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_START_FAILURE, userFacingReason(e))
            }
        } finally {
            isReloading = false
        }

        // No monitor at all means stopped and not started again; a new one belongs to a new start, which owns the cores.
        return when (ReloadOutcome.of(coreRunning = isRunning(), stoppedMeanwhile = networkMonitor == null)) {
            ReloadOutcome.KEEP_RUNNING -> true
            ReloadOutcome.STOP_SERVICE -> {
                stopServiceAfterFailedReload(askedBy)
                false
            }

            ReloadOutcome.RELEASE_CORES -> {
                releaseAfterStoppedReload(service)
                false
            }
        }
    }

    /**
     * Stops the service once a reload has left Xray stopped; see [ReloadOutcome.STOP_SERVICE]. The
     * reload runs on a background thread, the stop goes through the service on the main thread.
     */
    private fun stopServiceAfterFailedReload(monitor: NetworkMonitor) {
        val control = serviceControl?.get() ?: return
        val service = control.getService()
        ContextCompat.getMainExecutor(service).execute {
            // A stop or a new start that arrived meanwhile has taken over.
            if (isRunning() || networkMonitor !== monitor || serviceControl?.get() !== control) return@execute
            LogUtil.e(
                AppConfig.TAG,
                "StartCore-Manager: reload left no core running, stopping ${service.javaClass.simpleName}, guid=${MmkvManager.getSelectServer()}"
            )
            control.stopService()
        }
    }

    /**
     * Releases what a reload started for a service that was stopped while it ran; see
     * [ReloadOutcome.RELEASE_CORES]. The teardown of that service has done the rest already.
     *
     * It runs on the main thread, where a service starts as well: a start that followed the stop,
     * such as the second half of a restart, is then either over, and what runs is its own, or has
     * not begun, and finds nothing left running. For the same reason Xray is stopped in place here
     * rather than in the background: a start that begins next must not meet the core of the reload.
     */
    private fun releaseAfterStoppedReload(service: Service) {
        ContextCompat.getMainExecutor(service).execute {
            if (networkMonitor != null) return@execute
            LogUtil.w(
                AppConfig.TAG,
                "StartCore-Manager: ${service.javaClass.simpleName} was stopped during a reload, releasing the cores the reload started, " +
                    "guid=${MmkvManager.getSelectServer()}"
            )
            NotificationManager.cancelNotification()
            try {
                coreController.stopLoop()
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to stop the core a stopped reload started", e)
            }
            CoreNativeManager.reconcileBrowserDialer("")
            browserDialer?.stop()
            browserDialer = null
            // The reload may have announced a running or connecting service after the stop was reported.
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_STATE_STOP_SUCCESS, "")
        }
    }

    /**
     * Queries and resets all outbound traffic counters in one core call.
     * Go side format: tag,direction,value;tag,direction,value;
     */
    fun queryAllOutboundTrafficStats(): List<OutboundTrafficStat> {
        // The stats manager is gone once the core stops, querying it then reaches into freed state.
        if (!isRunning()) return emptyList()

        val payload = coreController.queryAllOutboundTrafficStats()

        val result = ArrayList<OutboundTrafficStat>()

        payload.split(';').forEach { entry ->
            if (entry.isBlank()) return@forEach

            val parts = entry.split(',', limit = 3)
            if (parts.size != 3) return@forEach

            val value = parts[2].toLongOrNull() ?: return@forEach

            result.add(
                OutboundTrafficStat(
                    tag = parts[0],
                    direction = parts[1],
                    value = value,
                )
            )
        }
//        LogUtil.d(AppConfig.TAG, "Queried outbound traffic stats: $result")
        return result
    }

    /**
     * Measures the connection delay for the current V2Ray configuration.
     * Tests with primary URL first, then falls back to alternative URL if needed.
     * Also fetches remote IP information if the delay test was successful.
     *
     * Griffin: [auto] is the test the service runs by itself once it has connected, see [scheduleAutoConnectionTest]:
     * it waits [startDelayMs] for the core and the DNS to warm up, then tries up to [attempts] times before it reports
     * a failure, since the first request after a start is often slow. A successful result is kept for a screen that
     * registers later and is shown in the notification: the exit's flag, country and IP beside the delay.
     */
    private fun measureV2rayDelay(requestId: String, auto: Boolean = false, startDelayMs: Long = 0L, attempts: Int = 1) {
        val service = getService() ?: return
        // A reload restarts Xray for a moment; the test the reload asks for waits it out.
        if (!isRunning() || (isReloading && !auto)) {
            MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            return
        }

        connectionTestScope.coroutineContext.cancelChildren()
        connectionTestScope.launch {
            if (startDelayMs > 0) delay(startDelayMs)
            var time = -1L
            var errorStr = ""

            for (attempt in 1..attempts.coerceAtLeast(1)) {
                ensureActive()
                if (!isRunning()) break
                try {
                    time = coreController.measureDelay(SettingsManager.getDelayTestUrl())
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay, attempt $attempt", e)
                    errorStr = e.message?.substringAfter("\":").orEmpty()
                }
                if (time == -1L) {
                    ensureActive()
                    try {
                        time = coreController.measureDelay(SettingsManager.getDelayTestUrl(true))
                    } catch (e: Exception) {
                        LogUtil.e(AppConfig.TAG, "StartCore-Manager: Failed to measure delay, attempt $attempt", e)
                        errorStr = e.message?.substringAfter("\":").orEmpty()
                    }
                }
                if (time >= 0) break
                if (attempt < attempts) delay(AUTO_TEST_RETRY_MS)
            }

            ensureActive()
            val endpoint = if (time >= 0) SpeedtestManager.getRemoteIPInfo() else null
            val result = ConnectionTestResult(
                delayMillis = time,
                errorMessage = errorStr,
                country = endpoint?.country,
                ipAddress = endpoint?.ipAddress,
            )
            withContext(Dispatchers.Main.immediate) {
                if (isRunning()) {
                    if (time >= 0) {
                        lastConnectionTest = result
                        NotificationManager.setStatusLine(connectionStatusLine(service, result))
                    }
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_RESULT, result, requestId)
                } else {
                    MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
                }
            }
        }.invokeOnCompletion { cause ->
            if (cause is CancellationException) {
                MessageHelper.sendMsg2UI(service, AppConfig.MSG_MEASURE_DELAY_CANCEL, "", requestId)
            }
        }
    }

    /**
     * Griffin: once the service has connected, or reconnected after a network handover, measures the delay and looks
     * the exit IP and country up by itself, as a tap on the main screen's status does; see [measureV2rayDelay].
     */
    private fun scheduleAutoConnectionTest() {
        lastConnectionTest = null
        measureV2rayDelay(AppConfig.AUTO_CONNECTION_TEST_ID, auto = true, startDelayMs = AUTO_TEST_START_DELAY_MS, attempts = AUTO_TEST_ATTEMPTS)
    }

    /** Griffin: the notification line of a successful connection test: the exit's flag, country and IP, and the delay. */
    private fun connectionStatusLine(service: Service, result: ConnectionTestResult): String {
        val delay = service.getString(R.string.server_test_delay_value, result.delayMillis)
        return listOfNotNull(CountryFlag.exitLabel(result.country, result.ipAddress), delay).joinToString("  •  ")
    }

    /**
     * Gets the current service instance.
     * @return The current service instance, or null if not available.
     */
    private fun getService(): Service? {
        return serviceControl?.get()?.getService()
    }

    /**
     * Core callback handler implementation for handling V2Ray core events.
     * Handles startup, shutdown, socket protection, and status emission.
     */
    private class CoreCallback : CoreCallbackHandler {
        /**
         * Called when V2Ray core starts up.
         * @return 0 for success, any other value for failure.
         */
        override fun startup(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback startup")
            return 0
        }

        /**
         * Called when V2Ray core shuts down.
         * @return 0 for success, any other value for failure.
         */
        override fun shutdown(): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback shutdown")
            return 0
        }

        /**
         * Called when V2Ray core emits status information.
         * @param l Status code.
         * @param s Status message.
         * @return Always returns 0.
         */
        override fun onEmitStatus(l: Long, s: String?): Long {
            LogUtil.i(AppConfig.TAG, "StartCore-Manager: CoreCallback onEmitStatus $s")
            return 0
        }
    }

    /**
     * Process finder implementation for Xray core.
     * Uses ConnectivityManager to find the owning UID of a connection based on network parameters.
     */
    private class XrayProcessFinder(context: Context) : ProcessFinder {
        private val cm: ConnectivityManager? = context.getSystemService(ConnectivityManager::class.java)

        override fun findProcessByConnection(network: String, srcIP: String, srcPort: Long, destIP: String, destPort: Long): Long {
            if (cm == null) return -1L
            val proto = when (network) {
                "tcp" -> OsConstants.IPPROTO_TCP
                "udp" -> OsConstants.IPPROTO_UDP
                else -> return -1L
            }

            if (destIP.isBlank() || destPort == 0L) {
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to :$destPort, (no dest)")
                return -1L
            }

            return try {
                val uid = cm.getConnectionOwnerUid(
                    proto,
                    InetSocketAddress(srcIP, srcPort.toInt()),
                    InetSocketAddress(destIP, destPort.toInt())
                ).toLong()
                LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid")
                //LogUtil.d(AppConfig.TAG, "ProcessFinder: Find $network connection from $srcIP:$srcPort to $destIP:$destPort, uid=$uid,${PackageUidResolver.uidToPackageName(uid.toString())}")

                uid
            } catch (_: Exception) {
                -1L
            }
        }
    }

    /**
     * Broadcast receiver for handling messages sent to the service.
     * Handles registration, service control, and screen events.
     */
    private class ReceiveMessageHandler : BroadcastReceiver() {
        /**
         * Handles received broadcast messages.
         * Processes service control messages and screen state changes.
         * @param ctx The context in which the receiver is running.
         * @param intent The intent being received.
         */
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val serviceControl = serviceControl?.get() ?: return
            when (intent?.getIntExtra("key", 0)) {
                AppConfig.MSG_REGISTER_CLIENT -> {
                    // A client that gets no acknowledgement takes the service for gone: the daemon
                    // cannot report its own death, so silence is the only sign of it.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    if (isServiceRunning()) {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_RUNNING, "")
                        // Griffin: a screen opened after the connection was tested is shown the exit it found.
                        lastConnectionTest?.let {
                            MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_MEASURE_DELAY_RESULT, it, AppConfig.AUTO_CONNECTION_TEST_ID)
                        }
                    } else {
                        MessageHelper.sendMsg2UI(serviceControl.getService(), AppConfig.MSG_STATE_NOT_RUNNING, "")
                    }
                }

                AppConfig.MSG_UNREGISTER_CLIENT -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_START -> {
                    // nothing to do
                }

                AppConfig.MSG_STATE_STOP -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Stop service")
                    serviceControl.stopService()
                }

                AppConfig.MSG_STATE_RESTART -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Restart service")
                    // The UI and daemon run in separate processes, so acknowledge the active
                    // daemon before stopping it instead of relying on possibly stale UI state.
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK

                    val pendingResult = goAsync()
                    CoroutineScope(Dispatchers.Default).launch {
                        try {
                            serviceControl.stopService()
                            delay(500L)
                            LauncherManager.startService(serviceControl.getService())
                        } finally {
                            pendingResult.finish()
                        }
                    }
                }

                AppConfig.MSG_MEASURE_DELAY -> {
                    if (isOrderedBroadcast) resultCode = Activity.RESULT_OK
                    measureV2rayDelay(intent.getStringExtra("content").orEmpty())
                }
            }

            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen off")
                    NotificationManager.stopSpeedNotification()
                }

                Intent.ACTION_SCREEN_ON -> {
                    LogUtil.i(AppConfig.TAG, "StartCore-Manager: Screen on")
                    NotificationManager.startSpeedNotification()
                }
            }
        }
    }
}
