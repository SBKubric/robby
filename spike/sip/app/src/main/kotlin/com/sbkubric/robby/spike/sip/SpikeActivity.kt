package com.sbkubric.robby.spike.sip

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** THROWAWAY SPIKE UI: plain Views, no AndroidX. */
class SpikeActivity : Activity() {

    private lateinit var username: EditText
    private lateinit var password: EditText
    private lateinit var domain: EditText
    private lateinit var target: EditText
    private lateinit var stun: EditText
    private lateinit var expires: EditText
    private lateinit var transport: RadioGroup
    private lateinit var srtp: RadioGroup
    private lateinit var tone: RadioGroup
    private lateinit var ice: CheckBox
    private lateinit var verifyTls: CheckBox
    private lateinit var avpf: CheckBox
    private lateinit var wakeLock: CheckBox
    private lateinit var wifiLock: CheckBox
    private lateinit var wav: CheckBox
    private lateinit var status: TextView
    private lateinit var checks: TextView
    private lateinit var logView: TextView

    private val refresh: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        root.addView(TextView(this).apply {
            text = "Robby · SIP-спайк (#17)\nОдноразовая сборка для замеров, не Robby."
            textSize = 18f
        })
        status = TextView(this).apply { setPadding(0, pad / 2, 0, pad / 2); setTypeface(typeface, Typeface.BOLD) }
        root.addView(status)
        checks = TextView(this)
        root.addView(checks)

        root.addView(row(
            button("Исключить из оптимизации батареи") { requestBatteryExemption() },
            button("Точные будильники") { requestExactAlarms() },
        ))

        username = field(root, "SIP-логин (аккаунт Robby на sip.linphone.org)")
        password = field(root, "Пароль", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        domain = field(root, "Домен")
        target = field(root, "Кому звонить (sip:office@sip.linphone.org или просто логин офиса)")
        stun = field(root, "STUN-сервер (пусто = без STUN)")
        expires = field(root, "Expires регистрации, с", InputType.TYPE_CLASS_NUMBER)

        transport = radios(root, "Транспорт SIP", SpikeTransport.entries.map { it.label })
        srtp = radios(root, "SRTP (SDES)", SpikeSrtp.entries.map { it.label })
        ice = check(root, "ICE")
        verifyTls = check(root, "Проверять TLS-сертификат сервера")
        avpf = check(root, "Предлагать AVPF (RTP/SAVPF)")
        wakeLock = check(root, "WakeLock на время звонка")
        wifiLock = check(root, "Wi-Fi lock HIGH_PERF на время звонка")
        wav = check(root, "Сохранять входящий звук в WAV (Загрузки, до 120 с)")
        tone = radios(root, "Что отправлять в офис", SpikeTone.entries.map { it.label })

        root.addView(row(
            button("Зарегистрироваться") { act(SpikeSipService.ACTION_REGISTER) },
            button("Позвонить сейчас") { act(SpikeSipService.ACTION_CALL) },
        ))
        root.addView(row(
            button("Позвонить через 60 с") { act(SpikeSipService.ACTION_CALL_LATER) },
            button("Положить трубку") { act(SpikeSipService.ACTION_HANGUP) },
        ))
        root.addView(row(
            button("Поделиться логом замеров") { share(false) },
            button("Поделиться логом PJSIP") { share(true) },
        ))
        root.addView(row(
            button("Сохранить логи в «Загрузки»") { saveLogs() },
            button("Очистить лог") { SpikeLog.clear() },
        ))
        root.addView(row(button("Остановить сервис и PJSIP") { act(SpikeSipService.ACTION_STOP, save = false) }))

        logView = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        }
        root.addView(logView)

        setContentView(ScrollView(this).apply { addView(root) })
        fill(SpikeSettings.load(this))

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        if (!SpikeSipService.running) SpikeSipService.send(this, SpikeSipService.ACTION_START)
    }

    override fun onResume() {
        super.onResume()
        SpikeLog.addListener(refresh)
        render()
    }

    override fun onPause() {
        SpikeLog.removeListener(refresh)
        save()
        super.onPause()
    }

    private fun render() {
        status.text = SpikeSipService.currentStatus
        val pm = getSystemService(PowerManager::class.java)
        val am = getSystemService(AlarmManager::class.java)
        checks.text = "Исключение из оптимизации батареи: ${if (pm.isIgnoringBatteryOptimizations(packageName)) "да" else "НЕТ"} · " +
            "точные будильники: ${if (am.canScheduleExactAlarms()) "да" else "НЕТ"}"
        logView.text = SpikeLog.tail(200)
    }

    // ---- actions -----------------------------------------------------------------------------------------------

    private fun act(action: String, save: Boolean = true) {
        if (save) save()
        SpikeSipService.send(this, action)
    }

    private fun share(pjsip: Boolean) {
        val body = if (pjsip) SpikeLog.pjsipText() else SpikeLog.measurementText()
        // A share Intent goes through Binder (about 1 MB); the full text is in «Сохранить логи».
        val capped = if (body.length > SHARE_CAP) "…(начало обрезано, полный лог — кнопкой «Сохранить логи»)\n" + body.takeLast(SHARE_CAP) else body
        val text = header() + capped
        val send = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, if (pjsip) "Robby SIP-спайк: лог PJSIP" else "Robby SIP-спайк: замеры")
            .putExtra(Intent.EXTRA_TEXT, text)
        startActivity(Intent.createChooser(send, "Поделиться"))
    }

    private fun saveLogs() {
        val stamp = System.currentTimeMillis() / 1000
        val saved = listOf(
            "robby-sip-spike-$stamp-zamery.txt" to header() + SpikeLog.measurementText(),
            "robby-sip-spike-$stamp-pjsip.txt" to header() + SpikeLog.pjsipText(),
        ).mapNotNull { (name, text) ->
            try {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
                name
            } catch (e: Exception) {
                SpikeLog.event("Не удалось сохранить $name: ${e.message}")
                null
            }
        }
        Toast.makeText(this, "Загрузки: ${saved.joinToString()}", Toast.LENGTH_LONG).show()
    }

    private fun header(): String = buildString {
        appendLine("Robby SIP-спайк (#17) ${BuildInfo.VERSION} · ${BuildInfo.NATIVE}")
        appendLine("Телефон: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), сборка ${Build.DISPLAY}")
        appendLine("Сейчас: ${DeviceState.describe(this@SpikeActivity)}")
        appendLine("Настройки:")
        appendLine(read().describeForLog())
        appendLine("----")
    }

    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, "Уже исключён", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("BatteryLife")
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun requestExactAlarms() {
        startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
    }

    // ---- form --------------------------------------------------------------------------------------------------

    private fun fill(s: SpikeSettings) {
        username.setText(s.username)
        password.setText(s.password)
        domain.setText(s.domain)
        target.setText(s.target)
        stun.setText(s.stunServer)
        expires.setText(s.regExpiresSec.toString())
        transport.check(transport.getChildAt(s.transport.ordinal + 1).id)
        srtp.check(srtp.getChildAt(s.srtp.ordinal + 1).id)
        tone.check(tone.getChildAt(s.tone.ordinal + 1).id)
        ice.isChecked = s.iceEnabled
        verifyTls.isChecked = s.verifyTls
        avpf.isChecked = s.offerAvpf
        wakeLock.isChecked = s.wakeLockDuringCall
        wifiLock.isChecked = s.wifiLockDuringCall
        wav.isChecked = s.saveIncomingWav
    }

    private fun read(): SpikeSettings = SpikeSettings(
        username = username.text.toString().trim(),
        password = password.text.toString(),
        domain = domain.text.toString().trim().ifEmpty { SpikeSettings.DEFAULT_DOMAIN },
        transport = SpikeTransport.entries[selected(transport)],
        srtp = SpikeSrtp.entries[selected(srtp)],
        stunServer = stun.text.toString().trim(),
        iceEnabled = ice.isChecked,
        target = target.text.toString().trim(),
        verifyTls = verifyTls.isChecked,
        offerAvpf = avpf.isChecked,
        regExpiresSec = expires.text.toString().toIntOrNull()?.coerceIn(60, 86_400) ?: 300,
        wakeLockDuringCall = wakeLock.isChecked,
        wifiLockDuringCall = wifiLock.isChecked,
        tone = SpikeTone.entries[selected(tone)],
        saveIncomingWav = wav.isChecked,
    )

    private fun save() = SpikeSettings.save(this, read())

    private fun selected(g: RadioGroup): Int {
        for (i in 1 until g.childCount) if ((g.getChildAt(i) as RadioButton).isChecked) return i - 1
        return 0
    }

    private fun field(root: LinearLayout, hint: String, type: Int = InputType.TYPE_CLASS_TEXT): EditText {
        root.addView(TextView(this).apply { text = hint; setPadding(0, 12, 0, 0) })
        return EditText(this).apply {
            inputType = type
            isSingleLine = true
            root.addView(this, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
    }

    private fun radios(root: LinearLayout, title: String, labels: List<String>): RadioGroup {
        val g = RadioGroup(this)
        g.addView(TextView(this).apply { text = title; setPadding(0, 12, 0, 0) })
        labels.forEach { l -> g.addView(RadioButton(this).apply { text = l; id = View.generateViewId() }) }
        root.addView(g)
        return g
    }

    private fun check(root: LinearLayout, label: String): CheckBox =
        CheckBox(this).apply { text = label; root.addView(this) }

    private fun button(label: String, onClick: () -> Unit): Button =
        Button(this).apply { text = label; isAllCaps = false; setOnClickListener { onClick() } }

    private fun row(vararg buttons: Button): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        buttons.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
    }

    companion object {
        private const val SHARE_CAP = 200_000
    }
}

object BuildInfo {
    const val VERSION = "0.0.1-spike"
    const val NATIVE = "PJSIP 2.18 + OpenSSL 3.5.9 (NDK r29, arm64-v8a)"
}
