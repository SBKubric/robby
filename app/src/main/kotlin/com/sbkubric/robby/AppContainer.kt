package com.sbkubric.robby

import android.content.Context
import android.util.Log
import com.sbkubric.robby.applets.AndroidDeviceName
import com.sbkubric.robby.applets.telegram.TelegramApi
import com.sbkubric.robby.applets.telegram.TelegramConnector
import com.sbkubric.robby.applets.telegram.TelegramSettingsStore
import com.sbkubric.robby.core.applet.AppletCatalog
import com.sbkubric.robby.core.applets.BuiltInApplets
import com.sbkubric.robby.core.applets.TelegramApplet
import com.sbkubric.robby.core.engine.HandleEvent
import com.sbkubric.robby.core.log.ActivityLog
import com.sbkubric.robby.core.scenario.SaveScenario
import com.sbkubric.robby.core.scenario.ScenarioRepository
import com.sbkubric.robby.core.scenario.ScenarioValidator
import com.sbkubric.robby.data.RobbyDatabase
import com.sbkubric.robby.data.RoomActivityLog
import com.sbkubric.robby.data.RoomScenarioRepository
import com.sbkubric.robby.service.ServiceLauncher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Clock

/** Manual DI: the single place where the app's objects are built and wired. */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val serviceLauncher = ServiceLauncher(appContext)

    /** Work that must outlive a screen or a broadcast, such as running scenarios. A failure is logged, not fatal. */
    val appScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("Robby", "Background work failed", e) },
    )

    val clock: Clock = Clock.systemDefaultZone()
    val catalog: AppletCatalog = BuiltInApplets.catalog

    private val database = RobbyDatabase.create(appContext)
    val scenarios: ScenarioRepository = RoomScenarioRepository(database.scenarios())
    val activityLog: ActivityLog = RoomActivityLog(database.runs())

    val telegramApi = TelegramApi()
    val telegram = TelegramConnector(telegramApi, TelegramSettingsStore(appContext), appContext)

    val saveScenario = SaveScenario(scenarios, ScenarioValidator(catalog))

    val handleEvent = HandleEvent(
        scenarios = scenarios,
        catalog = catalog,
        performers = mapOf(TelegramApplet.SEND to telegram.sendPerformer),
        log = activityLog,
        deviceName = AndroidDeviceName(appContext),
        clock = clock,
    )
}
