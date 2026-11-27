package com.sbkubric.robby.spike.sip

import android.content.Context
import android.net.ConnectivityManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import org.pjsip.pjsua2.Account
import org.pjsip.pjsua2.AccountConfig
import org.pjsip.pjsua2.AuthCredInfo
import org.pjsip.pjsua2.AuthCredInfoVector
import org.pjsip.pjsua2.Call
import org.pjsip.pjsua2.CallOpParam
import org.pjsip.pjsua2.Endpoint
import org.pjsip.pjsua2.EpConfig
import org.pjsip.pjsua2.IntVector
import org.pjsip.pjsua2.IpChangeParam
import org.pjsip.pjsua2.LogEntry
import org.pjsip.pjsua2.LogWriter
import org.pjsip.pjsua2.MediaFormatAudio
import org.pjsip.pjsua2.OnCallMediaStateParam
import org.pjsip.pjsua2.OnCallMediaTransportStateParam
import org.pjsip.pjsua2.OnCallStateParam
import org.pjsip.pjsua2.OnCallTsxStateParam
import org.pjsip.pjsua2.OnIncomingCallParam
import org.pjsip.pjsua2.OnNatDetectionCompleteParam
import org.pjsip.pjsua2.OnRegStartedParam
import org.pjsip.pjsua2.OnRegStateParam
import org.pjsip.pjsua2.OnTransportStateParam
import org.pjsip.pjsua2.StringVector
import org.pjsip.pjsua2.TransportConfig
import org.pjsip.pjsua2.pj_constants_
import org.pjsip.pjsua2.pjmedia_format_id
import org.pjsip.pjsua2.pjmedia_srtp_keying_method
import org.pjsip.pjsua2.pjmedia_srtp_use
import org.pjsip.pjsua2.pjmedia_tp_proto
import org.pjsip.pjsua2.pjmedia_type
import org.pjsip.pjsua2.pjsip_event_id_e
import org.pjsip.pjsua2.pjsip_inv_state
import org.pjsip.pjsua2.pjsip_status_code
import org.pjsip.pjsua2.pjsip_transport_state
import org.pjsip.pjsua2.pjsip_transport_type_e
import org.pjsip.pjsua2.pjsua_call_media_status
import org.pjsip.pjsua2.pjsua_med_tp_st
import org.pjsip.pjsua2.pjsua_stun_use
import java.net.Inet4Address

/**
 * THROWAWAY SPIKE: the whole PJSIP side. Every pjsua2 call made by the app goes through [handler]'s thread,
 * which created the library and so is registered with PJLIB. PJSIP's own worker thread delivers the callbacks.
 * pjsua2 objects (Endpoint, Account, Call, the port) are kept strongly and deleted explicitly on that thread,
 * so the Java GC never finalizes them on an unregistered thread.
 */
class SipEngine(private val context: Context, private val hooks: Hooks) {

    interface Hooks {
        /** A call starts or ends: the service takes or releases the wake lock and the Wi-Fi lock. */
        fun onCallActive(active: Boolean, settings: SpikeSettings)
        /** One-line state for the notification and the UI. */
        fun onStatus(status: String)
    }

    private val thread = HandlerThread("robby-spike-pjsua").apply { start() }
    private val handler = Handler(thread.looper)

    private var ep: SpikeEndpoint? = null
    private var logWriter: SpikeLogWriter? = null
    private var account: SpikeAccount? = null
    private var accountSettings: SpikeSettings? = null
    private var epSettings: SpikeSettings? = null
    @Volatile private var port: TonePort? = null
    @Volatile private var call: SpikeCall? = null
    private var callCount = 0

    @Volatile var regStatus = "не зарегистрирован"; private set
    @Volatile var callStatus = "нет звонка"; private set
    @Volatile private var regPressedAt = 0L
    @Volatile private var callStartedAt = 0L   // the button press (or alarm) that led to the current call

    // ---- commands (any thread) ---------------------------------------------------------------------------------

    fun register(s: SpikeSettings, pressedAt: Long) = post("регистрация") {
        regPressedAt = pressedAt
        SpikeLog.event("▶ «Зарегистрироваться»", pressedAt)
        ensureAccount(s, forceRegister = true)
    }

    fun call(s: SpikeSettings, pressedAt: Long, origin: String) = post("звонок") { startCall(s, pressedAt, origin) }

    fun hangup(pressedAt: Long) = post("положить трубку") {
        val c = call
        if (c == null) {
            SpikeLog.event("«Положить трубку»: звонка нет")
            return@post
        }
        SpikeLog.event("▶ «Положить трубку»", pressedAt)
        c.hangupRequestedAt = pressedAt
        c.hangup(CallOpParam(true))
    }

    fun networkChanged(description: String) = post("смена сети") {
        SpikeLog.event("Сеть: $description")
        val e = ep ?: return@post
        e.handleIpChange(IpChangeParam())
        SpikeLog.event("PJSIP: handleIpChange() вызван")
    }

    fun shutdown(done: () -> Unit) = post("остановка") {
        destroy()
        done()
        thread.quitSafely()
    }

    private fun post(what: String, block: () -> Unit) {
        handler.post {
            try {
                block()
            } catch (t: Throwable) {
                SpikeLog.event("ОШИБКА ($what): ${t.javaClass.simpleName}: ${t.message}")
            }
            publishStatus()
        }
    }

    // ---- endpoint and account (engine thread) ------------------------------------------------------------------

    /** Settings that need a PJSIP restart when they change. */
    private fun SpikeSettings.endpointPart() =
        listOf(transport, stunServer.trim(), verifyTls, domain.trim())

    private fun ensureEndpoint(s: SpikeSettings) {
        val current = epSettings
        if (ep != null && current != null && current.endpointPart() == s.endpointPart()) return
        if (ep != null) {
            SpikeLog.event("Транспорт/STUN/TLS изменились: перезапуск PJSIP")
            destroy()
        }
        val t0 = SystemClock.elapsedRealtime()
        val e = SpikeEndpoint()
        try {
            startEndpoint(e, s, t0)
        } catch (ex: Exception) {
            try { e.libDestroy() } catch (_: Exception) {}
            e.delete()
            throw ex
        }
    }

    private fun startEndpoint(e: SpikeEndpoint, s: SpikeSettings, t0: Long) {
        e.libCreate()

        val cfg = EpConfig()
        val ua = cfg.uaConfig
        ua.userAgent = "RobbySipSpike/0.0.1 PJSUA2/${e.libVersion().full}"
        ua.threadCnt = 1
        ua.mainThreadOnly = false
        ua.stunIgnoreFailure = true
        if (s.stunServer.isNotBlank()) ua.stunServer = StringVector().apply { add(s.stunServer.trim()) }
        val dns = dnsServers()
        if (dns.isNotEmpty()) ua.nameserver = StringVector().apply { dns.forEach { add(it) } }

        val writer = SpikeLogWriter()
        val lc = cfg.logConfig
        lc.level = 4
        lc.consoleLevel = 4
        lc.msgLogging = 1
        lc.writer = writer

        val mc = cfg.medConfig
        mc.clockRate = 8000
        mc.sndClockRate = 8000
        mc.channelCount = 1
        mc.audioFramePtime = 20
        mc.ptime = 20
        mc.noVad = true
        mc.ecTailLen = 0

        e.libInit(cfg)

        val tc = TransportConfig()
        tc.port = 0
        val type = when (s.transport) {
            SpikeTransport.TLS -> {
                val tls = tc.tlsConfig
                val ca = SystemCa.pem()
                tls.caBuf = ca.pem
                tls.verifyServer = s.verifyTls
                SpikeLog.event("TLS: ${ca.count} корневых сертификатов из ${ca.source}, проверка сервера ${if (s.verifyTls) "вкл" else "ВЫКЛ"}")
                pjsip_transport_type_e.PJSIP_TRANSPORT_TLS
            }
            SpikeTransport.TCP -> pjsip_transport_type_e.PJSIP_TRANSPORT_TCP
            SpikeTransport.UDP -> pjsip_transport_type_e.PJSIP_TRANSPORT_UDP
        }
        e.transportCreate(type, tc)
        e.libStart()

        // No Android audio at all: the null device only drives the conference bridge clock.
        e.audDevManager().setNullDev()

        // G.711 only (decision in #35).
        val codecs = e.codecEnum2()
        val enabled = mutableListOf<String>()
        for (i in 0 until codecs.size) {
            val id = codecs[i].codecId
            val prio = when {
                id.startsWith("PCMU/") -> 255
                id.startsWith("PCMA/") -> 254
                else -> 0
            }
            e.codecSetPriority(id, prio.toShort())
            if (prio > 0) enabled += id
        }

        val p = TonePort(s.tone)
        val fmt = MediaFormatAudio()
        fmt.init(pjmedia_format_id.PJMEDIA_FORMAT_PCM.toLong(), 8000, 1, 20_000, 16)
        p.createPort("robby-spike-tone", fmt)

        ep = e; logWriter = writer; port = p; epSettings = s
        SpikeLog.event(
            "PJSIP ${e.libVersion().full} запущен: транспорт ${s.transport.label}, null-устройство, " +
                "кодеки ${enabled.joinToString()}, DNS ${dns.ifEmpty { listOf("системный резолвер") }.joinToString()}",
            t0,
        )
    }

    /** Settings that need a new account when they change. */
    private fun SpikeSettings.accountPart() =
        listOf(username.trim(), password, domain.trim(), transport, srtp, stunServer.trim(), iceEnabled, offerAvpf, regExpiresSec)

    private fun ensureAccount(s: SpikeSettings, forceRegister: Boolean) {
        require(s.username.isNotBlank() && s.domain.isNotBlank()) { "не заданы имя пользователя или домен" }
        ensureEndpoint(s)
        val existing = account
        if (existing != null && accountSettings?.accountPart() == s.accountPart()) {
            if (forceRegister) {
                SpikeLog.event("REGISTER повторно (setRegistration)")
                existing.setRegistration(true)
            }
            return
        }
        if (existing != null) {
            SpikeLog.event("Настройки аккаунта изменились: аккаунт пересоздаётся")
            existing.shutdown()
            existing.delete()
            account = null
        }
        val domain = s.domain.trim()
        val tp = s.transport.uriParam
        val ac = AccountConfig()
        ac.idUri = s.idUri()
        ac.regConfig.registrarUri = "sip:$domain$tp"
        ac.regConfig.timeoutSec = s.regExpiresSec.toLong()
        ac.regConfig.registerOnAdd = true
        ac.regConfig.retryIntervalSec = 60
        ac.sipConfig.authCreds = AuthCredInfoVector().apply {
            add(AuthCredInfo("digest", "*", s.username.trim(), 0, s.password))
        }
        ac.sipConfig.proxies = StringVector().apply { add("sip:$domain$tp;lr") }

        val nat = ac.natConfig
        nat.iceEnabled = s.iceEnabled
        if (s.stunServer.isBlank()) {
            nat.mediaStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
            nat.sipStunUse = pjsua_stun_use.PJSUA_STUN_USE_DISABLED
        }

        val mc = ac.mediaConfig
        mc.srtpUse = when (s.srtp) {
            SpikeSrtp.MANDATORY -> pjmedia_srtp_use.PJMEDIA_SRTP_MANDATORY
            SpikeSrtp.OPTIONAL -> pjmedia_srtp_use.PJMEDIA_SRTP_OPTIONAL
            SpikeSrtp.DISABLED -> pjmedia_srtp_use.PJMEDIA_SRTP_DISABLED
        }
        // 1 = SRTP needs a secure next hop (TLS); with TCP/UDP that would refuse every call locally.
        mc.srtpSecureSignaling = if (s.transport == SpikeTransport.TLS) 1 else 0
        mc.srtpOpt.keyings = IntVector().apply { add(pjmedia_srtp_keying_method.PJMEDIA_SRTP_KEYING_SDES) }
        mc.rtcpFbConfig.dontUseAvpf = !s.offerAvpf

        val a = SpikeAccount()
        regStatus = "регистрация…"
        SpikeLog.event("Аккаунт ${s.idUri()}: регистратор sip:$domain$tp, expires ${s.regExpiresSec} с")
        try {
            a.create(ac, true)
        } catch (ex: Exception) {
            a.delete()
            regStatus = "аккаунт не создан"
            throw ex
        }
        account = a
        accountSettings = s
    }

    private fun destroy() {
        val e = ep ?: return
        SpikeLog.event("PJSIP останавливается")
        try { e.hangupAllCalls() } catch (_: Exception) {}
        call?.let { finishCall(it) }
        account?.let { a ->
            try { a.shutdown() } catch (_: Exception) {}
            a.delete()
        }
        port?.let { p ->
            p.detachCallbacks()
            p.delete()
        }
        try { e.libDestroy() } catch (ex: Exception) { SpikeLog.event("libDestroy: ${ex.message}") }
        e.delete()
        ep = null; account = null; accountSettings = null; port = null; epSettings = null; call = null
        logWriter = null
        regStatus = "не зарегистрирован"
        callStatus = "нет звонка"
        SpikeLog.event("PJSIP остановлен")
    }

    // ---- call (engine thread) ----------------------------------------------------------------------------------

    private fun startCall(s: SpikeSettings, pressedAt: Long, origin: String) {
        if (call != null) {
            SpikeLog.event("Звонок уже идёт: новый не начат")
            return
        }
        val uri = s.targetUri()
        try {
            require(uri.isNotEmpty()) { "не задан адрес офиса" }
            SpikeLog.event("▶ Звонок #${callCount + 1} ($origin) → $uri", pressedAt)
            SpikeLog.event("Телефон: ${DeviceState.describe(context)}")
            ensureAccount(s, forceRegister = false)
        } catch (ex: Exception) {
            hooks.onCallActive(false, s) // the service took the wake lock when the call was requested
            throw ex
        }
        callCount++
        callStartedAt = pressedAt
        val p = port!!
        p.tone = s.tone
        p.resetCounters(s.saveIncomingWav)
        hooks.onCallActive(true, s)
        val c = SpikeCall(account!!, callCount, origin, pressedAt, s)
        call = c
        callStatus = "звонок #$callCount: вызов…"
        val prm = CallOpParam(true)
        prm.opt.audioCount = 1
        prm.opt.videoCount = 0
        try {
            c.makeCall(uri, prm)
            SpikeLog.event("INVITE отправляется", pressedAt)
        } catch (ex: Exception) {
            SpikeLog.event("makeCall не удался: ${ex.message}", pressedAt)
            finishCall(c)
            return
        }
        handler.postDelayed(statsTick, STATS_PERIOD_MS)
    }

    private val statsTick = object : Runnable {
        override fun run() {
            val c = call ?: return
            try { c.logStats() } catch (ex: Exception) { SpikeLog.event("статистика: ${ex.message}") }
            publishStatus()
            handler.postDelayed(this, STATS_PERIOD_MS)
        }
    }

    /** Engine thread: the call is over (or never started). */
    private fun finishCall(c: SpikeCall) {
        if (c.finished) return
        c.finished = true
        handler.removeCallbacks(statsTick)
        if (call === c) call = null
        c.printSummary()
        val wav = port?.takeWav()
        if (wav != null) {
            val name = WavSaver.save(context, wav, "robby-sip-spike-call${c.number}")
            SpikeLog.event(if (name != null) "Входящий звук (${wav.size / 16000} с) сохранён: Загрузки/$name" else "WAV не сохранён")
        }
        hooks.onCallActive(false, c.settings)
        callStatus = "нет звонка (последний #${c.number}: ${c.endText})"
        // On the engine thread, outside PJSIP's callback, and before any libDestroy().
        try { c.delete() } catch (_: Exception) {}
    }

    private fun publishStatus() {
        val p = port
        val frames = if (call != null && p != null) " · кадров ↑${p.framesSent} ↓${p.framesReceived}" else ""
        hooks.onStatus("$regStatus · $callStatus$frames")
    }

    private fun dnsServers(): List<String> {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val lp = cm.activeNetwork?.let { cm.getLinkProperties(it) }
        val system = lp?.dnsServers.orEmpty().filterIsInstance<Inet4Address>().mapNotNull { it.hostAddress }
        // PJSIP's resolver needs plain DNS servers for SRV; public ones back up a missing or IPv6-only list.
        return (system + listOf("1.1.1.1", "8.8.8.8")).distinct()
    }

    private fun since(t: Long) = SystemClock.elapsedRealtime() - t

    // ---- pjsua2 subclasses (callbacks run on PJSIP's worker thread) --------------------------------------------

    private inner class SpikeEndpoint : Endpoint() {
        override fun onNatDetectionComplete(prm: OnNatDetectionCompleteParam) {
            SpikeLog.event("NAT телефона-сервера (по STUN): ${prm.natTypeName} (status ${prm.status} ${prm.reason})")
        }

        override fun onTransportState(prm: OnTransportStateParam) {
            val state = when (prm.state) {
                pjsip_transport_state.PJSIP_TP_STATE_CONNECTED -> "подключён"
                pjsip_transport_state.PJSIP_TP_STATE_DISCONNECTED -> "отключён"
                pjsip_transport_state.PJSIP_TP_STATE_SHUTDOWN -> "закрывается"
                pjsip_transport_state.PJSIP_TP_STATE_DESTROY -> "уничтожен"
                else -> "состояние ${prm.state}"
            }
            val tls = prm.tlsInfo
            val tlsText = if (prm.type.startsWith("TLS", ignoreCase = true) && tls.established) {
                val msgs = tls.verifyMsgs
                val verify = (0 until msgs.size).joinToString("; ") { msgs[it] }.ifEmpty { "ok" }
                " · ${tls.cipherName} → ${tls.remoteAddr}, сертификат CN=${tls.remoteCertInfo.subjectCn}, проверка: $verify"
            } else ""
            val err = if (prm.lastError != 0) " · ошибка: ${ep?.utilStrError(prm.lastError)}" else ""
            SpikeLog.event("SIP-транспорт ${prm.type}: $state$tlsText$err", regPressedAt.takeIf { it > 0 })
        }
    }

    private inner class SpikeAccount : Account() {
        override fun onRegStarted(prm: OnRegStartedParam) {
            SpikeLog.event(if (prm.renew) "REGISTER отправлен" else "un-REGISTER отправлен", regPressedAt.takeIf { it > 0 })
        }

        override fun onRegState(prm: OnRegStateParam) {
            val ok = prm.code / 100 == 2 && prm.expiration > 0
            regStatus = if (ok) "зарегистрирован (${prm.expiration} с)" else "регистрация: ${prm.code} ${prm.reason}"
            val from = regPressedAt
            SpikeLog.event(
                "Регистрация: ${prm.code} ${prm.reason}, expires ${prm.expiration} с" +
                    if (from > 0) " (от нажатия кнопки)" else "",
                from.takeIf { it > 0 },
            )
            regPressedAt = 0 // later refreshes are logged without an offset
            publishStatus()
        }

        override fun onIncomingCall(prm: OnIncomingCallParam) {
            SpikeLog.event("Входящий звонок отклонён (спайк звонит только наружу)")
            val c = Call(this, prm.callId)
            val op = CallOpParam()
            op.statusCode = pjsip_status_code.PJSIP_SC_BUSY_HERE
            c.hangup(op)
            handler.post { c.delete() }
        }
    }

    private inner class SpikeLogWriter : LogWriter() {
        override fun write(entry: LogEntry) {
            val msg = entry.msg
            SpikeLog.pjsip(msg)
            val t = callStartedAt
            if (call == null || t == 0L) return
            when {
                "ICE negotiation" in msg -> SpikeLog.event("ICE: ${msg.substringAfter("ICE negotiation").trim().let { "negotiation $it" }}", t)
                "sending from" in msg && "candidate" in msg -> SpikeLog.event("ICE пара: Comp ${msg.substringAfter("Comp ", msg).trimEnd()}", t)
                "SRTP" in msg && ("fail" in msg.lowercase() || "error" in msg.lowercase()) -> SpikeLog.event("SRTP: ${msg.trim()}", t)
            }
        }
    }

    private inner class SpikeCall(
        acc: Account,
        val number: Int,
        val origin: String,
        val pressedAt: Long,
        val settings: SpikeSettings,
    ) : Call(acc) {
        @Volatile var hangupRequestedAt = 0L
        var finished = false
        var endText = "?"
        private var lastDumpKey = ""
        private var tTrying = -1L
        private var t180 = -1L
        private var t183 = -1L
        private var t200 = -1L
        private var tConfirmed = -1L
        private var tMedia = -1L
        private var tDisconnected = -1L
        private var audioIdx = -1
        private var srtpText = "?"
        private var srtpLine = ""
        private var codecText = "?"
        private var iceText = "?"
        private var lastStats = "нет"
        private var lastStatsAt = 0L
        private var lastTx = 0L
        private var lastRx = 0L
        private var maxJitterMs = 0
        private var maxLossPct = 0.0
        private val responses = mutableListOf<String>()

        override fun onCallTsxState(prm: OnCallTsxStateParam) {
            val e = prm.e
            if (e.type != pjsip_event_id_e.PJSIP_EVENT_TSX_STATE) return
            val tsx = e.body.tsxState
            if (tsx.type != pjsip_event_id_e.PJSIP_EVENT_RX_MSG) return
            val t = tsx.tsx
            if (t.method != "INVITE") return
            val code = t.statusCode
            if (code < 100) return
            val dt = since(pressedAt)
            responses += "$code@${dt}мс"
            if (code == 100 && tTrying < 0) tTrying = dt
            SpikeLog.event("← $code ${t.statusText} (INVITE)", pressedAt)
        }

        override fun onCallState(prm: OnCallStateParam) {
            val ci = info
            val dt = since(pressedAt)
            when (ci.state) {
                pjsip_inv_state.PJSIP_INV_STATE_CALLING -> callStatus = "звонок #$number: INVITE отправлен"
                pjsip_inv_state.PJSIP_INV_STATE_EARLY -> {
                    val code = ci.lastStatusCode
                    if (code == 180 && t180 < 0) t180 = dt
                    if (code == 183 && t183 < 0) t183 = dt
                    callStatus = "звонок #$number: $code ${ci.lastReason}"
                }
                pjsip_inv_state.PJSIP_INV_STATE_CONNECTING -> {
                    if (t200 < 0) t200 = dt
                    callStatus = "звонок #$number: отвечен"
                    SpikeLog.event("Ответ (200 OK)", pressedAt)
                }
                pjsip_inv_state.PJSIP_INV_STATE_CONFIRMED -> {
                    if (tConfirmed < 0) tConfirmed = dt
                    callStatus = "звонок #$number: разговор"
                    SpikeLog.event("CONFIRMED (ACK отправлен)", pressedAt)
                }
                pjsip_inv_state.PJSIP_INV_STATE_DISCONNECTED -> {
                    tDisconnected = dt
                    val who = if (hangupRequestedAt > 0) "мы положили трубку" else "завершён удалённой стороной / сетью"
                    endText = "${ci.lastStatusCode} ${ci.lastReason}"
                    SpikeLog.event(
                        "Звонок завершён: $endText ($who), разговор ${ci.connectDuration.sec} с, всего ${ci.totalDuration.sec} с",
                        pressedAt,
                    )
                    handler.post { finishCall(this) }
                }
            }
            publishStatus()
        }

        override fun onCallMediaTransportState(prm: OnCallMediaTransportStateParam) {
            val st = when (prm.state) {
                pjsua_med_tp_st.PJSUA_MED_TP_CREATING -> "создаётся (сбор ICE-кандидатов)"
                pjsua_med_tp_st.PJSUA_MED_TP_IDLE -> "готов"
                pjsua_med_tp_st.PJSUA_MED_TP_INIT -> "инициализирован"
                pjsua_med_tp_st.PJSUA_MED_TP_RUNNING -> "работает"
                pjsua_med_tp_st.PJSUA_MED_TP_DISABLED -> "выключен"
                else -> "состояние ${prm.state}"
            }
            val err = if (prm.status != pj_constants_.PJ_SUCCESS) " · ${ep?.utilStrError(prm.status)} (SIP ${prm.sipErrorCode})" else ""
            SpikeLog.event("Медиатранспорт[${prm.medIdx}]: $st$err", pressedAt)
        }

        override fun onCallMediaState(prm: OnCallMediaStateParam) {
            val ci = info
            val media = ci.media
            for (i in 0 until media.size) {
                val m = media[i]
                if (m.type != pjmedia_type.PJMEDIA_TYPE_AUDIO) continue
                if (m.status != pjsua_call_media_status.PJSUA_CALL_MEDIA_ACTIVE) {
                    SpikeLog.event("Аудио[$i]: статус ${m.status}", pressedAt)
                    continue
                }
                val am = getAudioMedia(i)
                val p = port ?: continue
                p.startTransmit(am)
                am.startTransmit(p)
                if (tMedia < 0) tMedia = since(pressedAt)
                audioIdx = i
                val si = getStreamInfo(i.toLong())
                val srtp = si.proto and pjmedia_tp_proto.PJMEDIA_TP_PROFILE_SRTP != 0
                val avpf = si.proto and pjmedia_tp_proto.PJMEDIA_TP_PROFILE_RTCP_FB != 0
                srtpText = if (srtp) "да" else "НЕТ"
                codecText = "${si.codecName}/${si.codecClockRate} pt=${si.txPt}"
                val ti = getMedTransportInfo(i.toLong())
                SpikeLog.event(
                    "Аудио[$i] активно: кодек $codecText, SRTP $srtpText, AVPF ${if (avpf) "да" else "нет"}, " +
                        "RTP ${ti.localRtpName} ⇄ ${si.remoteRtpAddress} (пакеты приходят с ${ti.srcRtpName.ifEmpty { "—" }})",
                    pressedAt,
                )
                refreshFromDump()
            }
        }

        /** PJSIP's call dump has the ICE state, the chosen candidate pair and the SRTP crypto in its own words. */
        private fun refreshFromDump() {
            val d = dump(true, "    ")
            val ice = d.lines().map { it.trim() }.filter { it.startsWith("ICE role") || ("--> R:" in it) }
            val srtp = d.lines().map { it.trim() }.firstOrNull { it.startsWith("SRTP status") }
            if (ice.isNotEmpty()) iceText = ice.joinToString(" | ")
            else if (!settings.iceEnabled) iceText = "выключен в настройках"
            if (srtp != null) srtpLine = srtp
            val key = ice.joinToString() + srtp
            if (key != lastDumpKey) {
                lastDumpKey = key
                SpikeLog.event("Дамп звонка (ICE/SRTP изменились):\n$d", pressedAt)
            }
        }

        fun logStats() {
            val idx = audioIdx
            val p = port
            val now = SystemClock.elapsedRealtime()
            val period = if (lastStatsAt > 0) now - lastStatsAt else STATS_PERIOD_MS
            val txFrames = p?.framesSent ?: 0
            val rxFrames = p?.framesReceived ?: 0
            val fps = "кадров/с ↑%.1f ↓%.1f".format((txFrames - lastTx) * 1000.0 / period, (rxFrames - lastRx) * 1000.0 / period)
            lastTx = txFrames; lastRx = rxFrames; lastStatsAt = now
            if (idx < 0) {
                SpikeLog.event("Статистика: медиа ещё нет · $fps", pressedAt)
                return
            }
            if (!iceText.contains("Success") && !iceText.contains("Failed") && settings.iceEnabled) refreshFromDump()
            val ss = getStreamStat(idx.toLong())
            val rtcp = ss.rtcp
            val tx = rtcp.txStat
            val rx = rtcp.rxStat
            val expected = rx.pkt + rx.loss
            val lossPct = if (expected > 0) rx.loss * 100.0 / expected else 0.0
            val jitterMs = rx.jitterUsec.last / 1000
            if (jitterMs > maxJitterMs) maxJitterMs = jitterMs
            if (lossPct > maxLossPct) maxLossPct = lossPct
            val rtt = rtcp.rttUsec
            val rttText = if (rtt.n > 0) "${rtt.last / 1000}/${rtt.mean / 1000} мс" else "нет RTCP"
            val jb = ss.jbuf
            lastStats = "RTP ↑${tx.pkt} ↓${rx.pkt} пак., потери ↓${rx.loss} (%.1f%%), у офиса ↑${tx.loss}, ".format(lossPct) +
                "джиттер ↓${jitterMs} мс (ср ${rx.jitterUsec.mean / 1000}, макс ${rx.jitterUsec.max / 1000}), " +
                "RTT посл/ср $rttText, jbuf ${jb.avgDelayMsec} мс (потеряно ${jb.lost}, выброшено ${jb.discard}), " +
                "$fps, ↓со звуком ${p?.framesReceivedVoiced ?: 0}, уровень ↓%.0f dBFS".format(p?.lastRxRmsDbfs ?: -99.0)
            SpikeLog.event("Статистика: $lastStats", pressedAt)
        }

        fun printSummary() {
            fun ms(v: Long) = if (v < 0) "—" else "$v мс"
            val p = port
            val voiceDelay = p?.firstVoicedAt?.takeIf { it > 0 }?.let { it - pressedAt }
            SpikeLog.event(
                buildString {
                    appendLine("=== Итог звонка #$number ($origin) ===")
                    appendLine("  от нажатия: 100 Trying ${ms(tTrying)}, 180 Ringing ${ms(t180)}, 183 ${ms(t183)}, 200 OK ${ms(t200)}, ACK ${ms(tConfirmed)}, медиа ${ms(tMedia)}")
                    appendLine("  ответы на INVITE: ${responses.joinToString().ifEmpty { "нет" }}")
                    appendLine("  ICE: $iceText")
                    appendLine("  SRTP: $srtpText ${srtpLine}, кодек: $codecText")
                    appendLine("  первый звук из офиса: ${voiceDelay?.let { "$it мс от нажатия" } ?: "НЕ получен"}")
                    appendLine("  кадров ↑${p?.framesSent ?: 0}, ↓${p?.framesReceived ?: 0} (со звуком ${p?.framesReceivedVoiced ?: 0}, пустых ${p?.framesReceivedEmpty ?: 0}), пик уровня ↓%.0f dBFS".format(p?.peakRxRmsDbfs ?: -99.0))
                    appendLine("  худшее за звонок: потери ↓%.1f%%, джиттер ↓$maxJitterMs мс".format(maxLossPct))
                    appendLine("  последняя статистика: $lastStats")
                    append("  завершение: $endText через ${ms(tDisconnected)}")
                },
            )
        }
    }

    companion object {
        const val STATS_PERIOD_MS = 5_000L

        init {
            System.loadLibrary("c++_shared")
            System.loadLibrary("pjsua2")
        }
    }
}
