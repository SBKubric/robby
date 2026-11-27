package com.sbkubric.robby.spike.sip

import android.content.Context

/** SIP transport the spike account uses. */
enum class SpikeTransport(val label: String, val uriParam: String) {
    TLS("TLS", ";transport=tls"),
    TCP("TCP", ";transport=tcp"),
    UDP("UDP", ""),
}

/** How the spike offers SRTP (SDES only). */
enum class SpikeSrtp(val label: String) {
    MANDATORY("обязательный"),
    OPTIONAL("необязательный"),
    DISABLED("выключен"),
}

/** What the spike sends instead of the module's PCM. */
enum class SpikeTone(val label: String) {
    BEEPS("бипы 1 кГц (3 коротких, пауза)"),
    CONTINUOUS("непрерывный тон 1 кГц"),
}

/**
 * Everything the owner types into the form. Kept in SharedPreferences (app-private storage), never in code.
 * The password is never written to any log: use [describeForLog].
 */
data class SpikeSettings(
    val username: String = "",
    val password: String = "",
    val domain: String = DEFAULT_DOMAIN,
    val transport: SpikeTransport = SpikeTransport.TLS,
    val srtp: SpikeSrtp = SpikeSrtp.MANDATORY,
    val stunServer: String = DEFAULT_STUN,
    val iceEnabled: Boolean = true,
    val target: String = "",
    val verifyTls: Boolean = true,
    val offerAvpf: Boolean = false,
    val regExpiresSec: Int = 300,
    val wakeLockDuringCall: Boolean = true,
    val wifiLockDuringCall: Boolean = true,
    val tone: SpikeTone = SpikeTone.BEEPS,
    val saveIncomingWav: Boolean = true,
) {
    /** The target as a SIP URI: `office` becomes `sip:office@<domain>`. */
    fun targetUri(): String {
        val t = target.trim()
        return when {
            t.isEmpty() -> ""
            t.startsWith("sip:") || t.startsWith("sips:") -> t
            '@' in t -> "sip:$t"
            else -> "sip:$t@${domain.trim()}"
        }
    }

    fun idUri(): String = "sip:${username.trim()}@${domain.trim()}"

    /** Settings for the shared log: everything except the password. */
    fun describeForLog(): String = buildString {
        appendLine("  аккаунт: ${idUri()} (пароль ${if (password.isEmpty()) "НЕ задан" else "задан, ${password.length} симв."})")
        appendLine("  транспорт: ${transport.label}, SRTP: ${srtp.label}, проверка TLS-сертификата: ${onOff(verifyTls)}")
        appendLine("  ICE: ${onOff(iceEnabled)}, STUN: ${stunServer.ifBlank { "нет" }}, AVPF: ${onOff(offerAvpf)}")
        appendLine("  цель: ${targetUri().ifBlank { "не задана" }}, expires регистрации: $regExpiresSec с")
        appendLine("  WakeLock в звонке: ${onOff(wakeLockDuringCall)}, Wi-Fi lock HIGH_PERF: ${onOff(wifiLockDuringCall)}")
        append("  звук: ${tone.label}, запись входящего звука в WAV: ${onOff(saveIncomingWav)}")
    }

    companion object {
        const val DEFAULT_DOMAIN = "sip.linphone.org"
        const val DEFAULT_STUN = "stun.linphone.org"
        private const val PREFS = "robby_sip_spike"

        fun load(context: Context): SpikeSettings {
            val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val d = SpikeSettings()
            return SpikeSettings(
                username = p.getString("username", d.username)!!,
                password = p.getString("password", d.password)!!,
                domain = p.getString("domain", d.domain)!!,
                transport = enumOr(p.getString("transport", null), d.transport),
                srtp = enumOr(p.getString("srtp", null), d.srtp),
                stunServer = p.getString("stun", d.stunServer)!!,
                iceEnabled = p.getBoolean("ice", d.iceEnabled),
                target = p.getString("target", d.target)!!,
                verifyTls = p.getBoolean("verifyTls", d.verifyTls),
                offerAvpf = p.getBoolean("avpf", d.offerAvpf),
                regExpiresSec = p.getInt("regExpires", d.regExpiresSec),
                wakeLockDuringCall = p.getBoolean("wakeLock", d.wakeLockDuringCall),
                wifiLockDuringCall = p.getBoolean("wifiLock", d.wifiLockDuringCall),
                tone = enumOr(p.getString("tone", null), d.tone),
                saveIncomingWav = p.getBoolean("wav", d.saveIncomingWav),
            )
        }

        fun save(context: Context, s: SpikeSettings) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString("username", s.username)
                .putString("password", s.password)
                .putString("domain", s.domain)
                .putString("transport", s.transport.name)
                .putString("srtp", s.srtp.name)
                .putString("stun", s.stunServer)
                .putBoolean("ice", s.iceEnabled)
                .putString("target", s.target)
                .putBoolean("verifyTls", s.verifyTls)
                .putBoolean("avpf", s.offerAvpf)
                .putInt("regExpires", s.regExpiresSec)
                .putBoolean("wakeLock", s.wakeLockDuringCall)
                .putBoolean("wifiLock", s.wifiLockDuringCall)
                .putString("tone", s.tone.name)
                .putBoolean("wav", s.saveIncomingWav)
                .apply()
        }

        private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: default

        private fun onOff(b: Boolean) = if (b) "вкл" else "выкл"
    }
}
