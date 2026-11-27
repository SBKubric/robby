package com.sbkubric.robby.spike.sip

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock

/**
 * THROWAWAY SPIKE: a foreground service of type `specialUse`, like Robby's own, that hosts the PJSIP stack.
 * No ConnectionService, no audio focus, no AudioManager mode, no microphone.
 */
class SpikeSipService : Service(), SipEngine.Hooks {

    private lateinit var engine: SipEngine
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastStatus = "запуск…"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        goForeground()
        SpikeLog.event("Сервис запущен (FGS specialUse). ${DeviceState.describe(this)}")
        engine = SipEngine(applicationContext, this)
        watchNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground()
        val pressedAt = intent?.getLongExtra(EXTRA_PRESSED_AT, 0L)?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime()
        val s = SpikeSettings.load(this)
        when (intent?.action) {
            null -> SpikeLog.event("Сервис перезапущен системой (START_STICKY)")
            ACTION_START -> Unit
            ACTION_REGISTER -> engine.register(s, pressedAt)
            ACTION_CALL -> {
                holdCpuForCall(s)
                engine.call(s, pressedAt, "кнопка «Позвонить сейчас»")
            }
            ACTION_CALL_LATER -> scheduleCall(pressedAt)
            ACTION_ALARM_CALL -> {
                val due = intent.getLongExtra(EXTRA_DUE_AT, 0L)
                val now = SystemClock.elapsedRealtime()
                holdCpuForCall(s)
                SpikeLog.event("Будильник сработал: опоздание ${now - due} мс. ${DeviceState.describe(this)}", pressedAt)
                // Offsets of this call count from the alarm, not from the button 60 s earlier.
                engine.call(s, now, "будильник «через 60 с»")
            }
            ACTION_HANGUP -> engine.hangup(pressedAt)
            ACTION_STOP -> {
                SpikeLog.event("▶ «Остановить сервис»")
                engine.shutdown {
                    releaseLocks()
                    stopSelf()
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        networkCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        releaseLocks()
        running = false
        SpikeLog.event("Сервис остановлен")
        super.onDestroy()
    }

    // ---- delayed call ------------------------------------------------------------------------------------------

    private fun scheduleCall(pressedAt: Long) {
        val am = getSystemService(AlarmManager::class.java)
        val due = pressedAt + CALL_DELAY_MS
        val pi = PendingIntent.getForegroundService(
            this, 1,
            Intent(this, SpikeSipService::class.java).setAction(ACTION_ALARM_CALL)
                .putExtra(EXTRA_DUE_AT, due).putExtra(EXTRA_PRESSED_AT, pressedAt),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val exact = am.canScheduleExactAlarms()
        if (exact) {
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, due, pi)
        }
        SpikeLog.event(
            "▶ «Позвонить через 60 с»: будильник ${if (exact) "точный (setExactAndAllowWhileIdle)" else "НЕточный: нет разрешения на точные будильники"}. " +
                "Выключите экран.",
            pressedAt,
        )
    }

    // ---- CPU and Wi-Fi during a call ---------------------------------------------------------------------------

    /** Taken as soon as a call is requested, so the CPU cannot sleep between the alarm and the INVITE. */
    private fun holdCpuForCall(s: SpikeSettings) {
        if (!s.wakeLockDuringCall || wakeLock?.isHeld == true) return
        val wl = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RobbySipSpike:call")
        wl.acquire(MAX_CALL_MS)
        wakeLock = wl
        SpikeLog.event("WakeLock PARTIAL взят")
    }

    @Suppress("DEPRECATION")
    override fun onCallActive(active: Boolean, settings: SpikeSettings) {
        if (active) {
            holdCpuForCall(settings)
            if (settings.wifiLockDuringCall && wifiLock?.isHeld != true) {
                // HIGH_PERF works with the screen off up to Android 13; on 14+ it silently becomes LOW_LATENCY.
                val wl = applicationContext.getSystemService(WifiManager::class.java)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RobbySipSpike:call")
                wl.acquire()
                wifiLock = wl
                SpikeLog.event("Wi-Fi lock HIGH_PERF взят")
            }
        } else {
            releaseLocks()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) { it.release(); SpikeLog.event("WakeLock отпущен") } }
        wakeLock = null
        wifiLock?.let { if (it.isHeld) { it.release(); SpikeLog.event("Wi-Fi lock отпущен") } }
        wifiLock = null
    }

    override fun onStatus(status: String) {
        currentStatus = status
        if (status == lastStatus || !running) return
        lastStatus = status
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    // ---- network changes ---------------------------------------------------------------------------------------

    private fun watchNetwork() {
        val cm = getSystemService(ConnectivityManager::class.java)
        val cb = object : ConnectivityManager.NetworkCallback() {
            private var current: Network? = null
            private var currentAddrs: String = ""

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network == current) return
                val first = current == null
                current = network
                val kind = if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "Wi-Fi"
                else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) "мобильная" else "другая"
                if (first) SpikeLog.event("Сеть по умолчанию: $kind") else engine.networkChanged("сеть по умолчанию сменилась на $kind")
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val addrs = lp.linkAddresses.joinToString { it.address.hostAddress ?: "?" }
                if (network == current && currentAddrs.isNotEmpty() && addrs != currentAddrs) {
                    engine.networkChanged("адреса сменились: $addrs")
                }
                if (network == current) currentAddrs = addrs
            }

            override fun onLost(network: Network) {
                if (network == current) {
                    current = null
                    SpikeLog.event("Сеть по умолчанию потеряна")
                }
            }
        }
        cm.registerDefaultNetworkCallback(cb)
        networkCallback = cb
    }

    // ---- notification ------------------------------------------------------------------------------------------

    private fun createChannel() {
        val ch = NotificationChannel(CHANNEL_ID, "SIP-спайк", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, SpikeActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_call_outgoing)
            .setContentTitle("Robby SIP-спайк")
            .setContentText(lastStatus)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun goForeground() {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
    }

    companion object {
        const val ACTION_START = "com.sbkubric.robby.spike.sip.START"
        const val ACTION_REGISTER = "com.sbkubric.robby.spike.sip.REGISTER"
        const val ACTION_CALL = "com.sbkubric.robby.spike.sip.CALL"
        const val ACTION_CALL_LATER = "com.sbkubric.robby.spike.sip.CALL_LATER"
        const val ACTION_ALARM_CALL = "com.sbkubric.robby.spike.sip.ALARM_CALL"
        const val ACTION_HANGUP = "com.sbkubric.robby.spike.sip.HANGUP"
        const val ACTION_STOP = "com.sbkubric.robby.spike.sip.STOP"
        const val EXTRA_PRESSED_AT = "pressedAt"
        const val EXTRA_DUE_AT = "dueAt"
        const val CALL_DELAY_MS = 60_000L
        private const val MAX_CALL_MS = 30 * 60_000L
        private const val CHANNEL_ID = "sip_spike"
        private const val NOTIFICATION_ID = 17

        @Volatile var running = false; private set
        @Volatile var currentStatus = "сервис не запущен"; private set

        fun send(context: Context, action: String) {
            val i = Intent(context, SpikeSipService::class.java).setAction(action)
                .putExtra(EXTRA_PRESSED_AT, SystemClock.elapsedRealtime())
            context.startForegroundService(i)
        }
    }
}
