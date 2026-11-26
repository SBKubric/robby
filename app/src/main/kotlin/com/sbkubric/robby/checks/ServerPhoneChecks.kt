package com.sbkubric.robby.checks

import android.Manifest
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.annotation.StringRes
import com.sbkubric.robby.R
import com.sbkubric.robby.applets.AppletPermissions
import com.sbkubric.robby.core.scenario.Scenario

/**
 * «Проверки телефона-сервера»: what Android may do to an always-on sideloaded app (research, issue #2) and the screen
 * lock (decision #12), each read from the system as it is now; the UI re-reads them when it comes back to the screen.
 * Each check that fails has a [Fix]: the system screen where the user changes it.
 */
class ServerPhoneChecks(context: Context) {

    private val context = context.applicationContext
    private val packageName = this.context.packageName
    private val packageUri = Uri.fromParts("package", packageName, null)

    /** One check: what it is about, whether it is fine, and how to fix it if not. */
    data class Result(val check: Check, val ok: Boolean, val fix: Fix? = null, val missingPermissions: List<String> = emptyList())

    enum class Check(@StringRes val title: Int, @StringRes val ok: Int, @StringRes val problem: Int) {
        PERMISSIONS(R.string.check_permissions, R.string.check_permissions_ok, R.string.check_permissions_problem),
        RESTRICTED_SETTINGS(R.string.check_restricted, R.string.check_restricted_ok, R.string.check_restricted_problem),
        BATTERY_OPTIMIZATION(R.string.check_battery, R.string.check_battery_ok, R.string.check_battery_problem),
        UNUSED_APP_PAUSE(R.string.check_hibernation, R.string.check_hibernation_ok, R.string.check_hibernation_problem),
        NOTIFICATIONS(R.string.check_notifications, R.string.check_notifications_ok, R.string.check_notifications_problem),
        EXACT_ALARMS(R.string.check_exact_alarms, R.string.check_exact_alarms_ok, R.string.check_exact_alarms_problem),
        DATA_SAVER(R.string.check_data_saver, R.string.check_data_saver_ok, R.string.check_data_saver_problem),
        SCREEN_LOCK(R.string.check_screen_lock, R.string.check_screen_lock_ok, R.string.check_screen_lock_problem),
    }

    sealed interface Fix {
        /** Ask for these runtime permissions; the app's own screen is the fallback once Android stops asking. */
        data class Request(val permissions: List<String>) : Fix

        /** Open this system screen. */
        data class Open(val intent: Intent) : Fix
    }

    /** Every check, in the order the user goes through them, for the scenarios that are switched on. */
    fun evaluate(scenarios: List<Scenario>): List<Result> {
        val needed = scenarios.filter { it.enabled }
            .flatMap { listOf(it.trigger.id) + it.conditions.map { condition -> condition.id } }
            .flatMap { AppletPermissions.forElement(it) }
            .distinct()
        val missing = needed.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        return listOf(
            Result(Check.PERMISSIONS, missing.isEmpty(), Fix.Request(missing), missing),
            Result(
                Check.RESTRICTED_SETTINGS,
                !restrictedSettingsInTheWay(missing),
                open(Settings.ACTION_APPLICATION_DETAILS_SETTINGS),
            ),
            Result(
                Check.BATTERY_OPTIMIZATION,
                service<PowerManager>().isIgnoringBatteryOptimizations(packageName),
                // Asks right there; needs REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, fine for an app that is not on Play.
                open(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS),
            ),
            Result(
                Check.UNUSED_APP_PAUSE,
                context.packageManager.isAutoRevokeWhitelisted,
                open(Intent.ACTION_AUTO_REVOKE_PERMISSIONS),
            ),
            Result(
                Check.NOTIFICATIONS,
                service<NotificationManager>().areNotificationsEnabled(),
                Fix.Open(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)),
            ),
            Result(
                Check.EXACT_ALARMS,
                service<AlarmManager>().canScheduleExactAlarms(),
                open(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM),
            ),
            Result(
                Check.DATA_SAVER,
                service<ConnectivityManager>().restrictBackgroundStatus != ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
                open(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS),
            ),
            // Decision #12: no lock on the server phone, or Robby stays silent after a reboot until it is unlocked.
            Result(
                Check.SCREEN_LOCK,
                !service<KeyguardManager>().isDeviceSecure,
                Fix.Open(Intent(Settings.ACTION_SECURITY_SETTINGS)),
            ),
        )
    }

    /** The app's own page in the system settings: the fallback for every fix. */
    fun appDetails(): Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)

    /**
     * Android 15 keeps an APK opened from a file («Загрузки», a file manager) from being granted SMS permissions until
     * «Разрешить настройки с ограниченным доступом» is chosen in its app info (on 13–14 the restriction covers only
     * services Robby does not use). Android does not tell apps whether that was chosen, so this is a best guess:
     * such an install still lacks an SMS permission it needs. An `adb install` or a store is not restricted.
     */
    private fun restrictedSettingsInTheWay(missing: List<String>): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return false
        if (Manifest.permission.RECEIVE_SMS !in missing) return false
        val source = runCatching { context.packageManager.getInstallSourceInfo(packageName).packageSource }.getOrNull()
        return source == PackageInstaller.PACKAGE_SOURCE_LOCAL_FILE || source == PackageInstaller.PACKAGE_SOURCE_DOWNLOADED_FILE
    }

    private fun open(action: String) = Fix.Open(Intent(action, packageUri))

    private inline fun <reified T : Any> service(): T = context.getSystemService(T::class.java)
}
