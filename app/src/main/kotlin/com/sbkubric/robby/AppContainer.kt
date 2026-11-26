package com.sbkubric.robby

import android.content.Context
import android.util.Log
import com.sbkubric.robby.applets.AndroidDeviceName
import com.sbkubric.robby.applets.battery.BatteryWatchPrefs
import com.sbkubric.robby.applets.battery.StickyBatteryState
import com.sbkubric.robby.applets.call.AndroidContactNames
import com.sbkubric.robby.applets.call.CallReadings
import com.sbkubric.robby.applets.call.CallWatchPrefs
import com.sbkubric.robby.applets.call.TelephonyCallState
import com.sbkubric.robby.applets.system.ScenarioNotifier
import com.sbkubric.robby.applets.system.ScheduleAlarms
import com.sbkubric.robby.applets.system.ServerPhoneName
import com.sbkubric.robby.applets.telegram.TelegramApi
import com.sbkubric.robby.applets.telegram.TelegramConnector
import com.sbkubric.robby.applets.telegram.TelegramSettingsStore
import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.applets.BatteryWatch
import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.applets.CallWatch
import com.sbkubric.robby.core.applets.SystemApplet
import com.sbkubric.robby.core.applets.TelegramApplet
import com.sbkubric.robby.core.engine.ConnectorHealth
import com.sbkubric.robby.core.engine.DeliverRuns
import com.sbkubric.robby.core.engine.HandleEvent
import com.sbkubric.robby.core.log.ActivityLog
import com.sbkubric.robby.core.scenario.SaveScenario
import com.sbkubric.robby.core.scenario.ScenarioRepository
import com.sbkubric.robby.core.scenario.ScenarioValidator
import com.sbkubric.robby.data.RobbyDatabase
import com.sbkubric.robby.data.RoomActivityLog
import com.sbkubric.robby.data.RoomScenarioRepository
import com.sbkubric.robby.service.DeliveryLoop
import com.sbkubric.robby.service.ServiceLauncher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

/** Manual DI: the single place where the app's objects are built and wired. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val serviceLauncher = ServiceLauncher(appContext)

    /** Work that must outlive a screen or a broadcast, such as running scenarios. A failure is logged, not fatal. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Robby", "Background work failed", e) },
    )

    val clock: Clock = SystemZoneClock
    val catalog: AppletCatalog = BuiltInApplets.catalog

    private val database = RobbyDatabase.create(appContext)
    val scenarios: ScenarioRepository = RoomScenarioRepository(database.scenarios())
    val activityLog: ActivityLog = RoomActivityLog(database.runs())

    val telegramApi = TelegramApi()
    val telegram = TelegramConnector(telegramApi, TelegramSettingsStore(appContext), appContext)

    val serverPhoneName = ServerPhoneName(appContext, fallback = AndroidDeviceName(appContext))
    val scheduleAlarms = ScheduleAlarms(appContext, scenarios, clock)
    private val notifier = ScenarioNotifier(appContext)

    /** Fed by the service's battery receiver; one per process, its memory in preferences. */
    val batteryWatch = BatteryWatch(scenarios, BatteryWatchPrefs(appContext))

    /**
     * Fed by the call state receiver, in order; the ringing call is kept in preferences, as each broadcast may start
     * a new process.
     */
    val callReadings = CallReadings(CallWatch(CallWatchPrefs(appContext), AndroidContactNames(appContext)), appScope)

    val saveScenario = SaveScenario(scenarios, ScenarioValidator(catalog))

    val handleEvent = HandleEvent(
        scenarios = scenarios,
        catalog = catalog,
        log = activityLog,
        deviceName = serverPhoneName,
        battery = StickyBatteryState(appContext),
        call = TelephonyCallState(appContext),
        clock = clock,
    )

    /** The connectors' «Сломан», by connector (applet) id; Telegram is the only connector so far. */
    private val connectorHealth = object : ConnectorHealth {
        override fun brokenReason(connectorId: String): String? =
            if (connectorId == TelegramApplet.ID) telegram.brokenReason.value else null

        override suspend fun markBroken(connectorId: String, reason: String) {
            if (connectorId == TelegramApplet.ID) telegram.markBroken(reason)
        }
    }

    private val deliverRuns = DeliverRuns(
        log = activityLog,
        scenarios = scenarios,
        catalog = catalog,
        performers = mapOf(
            TelegramApplet.SEND to telegram.sendPerformer,
            SystemApplet.NOTIFY to notifier.performer,
        ),
        connectors = connectorHealth,
        clock = clock,
    )

    /** Run by [com.sbkubric.robby.service.RobbyService]; anyone who records a run kicks it. */
    val delivery = DeliveryLoop(appContext, deliverRuns, scenarios, serviceLauncher, clock)
}

/**
 * The system clock in the phone's current time zone: unlike `Clock.systemDefaultZone()`, it follows a time zone change
 * (Android resets the default zone of running processes), so `{now}`, conditions and schedules use the new zone.
 */
private object SystemZoneClock : Clock() {
    override fun getZone(): ZoneId = ZoneId.systemDefault()
    override fun withZone(zone: ZoneId): Clock = system(zone)
    override fun instant(): Instant = Instant.now()
}
