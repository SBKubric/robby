package com.sbkubric.robby.spike.sip

import android.content.ContentValues
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.MediaStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The system's root CAs as one PEM buffer for PJSIP/OpenSSL. Android's cacerts files are named by the old OpenSSL
 * subject hash, so a CA *path* would not work with OpenSSL 3; a buffer does.
 */
object SystemCa {
    class Bundle(val pem: String, val count: Int, val source: String)

    private val block = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")

    fun pem(): Bundle {
        val dirs = listOf("/apex/com.android.conscrypt/cacerts", "/system/etc/security/cacerts")
        for (d in dirs) {
            val files = File(d).listFiles()?.filter { it.isFile } ?: continue
            if (files.isEmpty()) continue
            val sb = StringBuilder()
            var n = 0
            for (f in files) {
                val text = try { f.readText() } catch (_: Exception) { continue }
                block.findAll(text).forEach { sb.append(it.value).append('\n'); n++ }
            }
            if (n > 0) return Bundle(sb.toString(), n, d)
        }
        return Bundle("", 0, "нигде (сертификаты не найдены)")
    }
}

/** Writes the office's audio as a WAV into the public Downloads folder (no permission needed for own files). */
object WavSaver {
    fun save(context: Context, pcm: ByteArray, baseName: String): String? = try {
        val name = "$baseName-${System.currentTimeMillis() / 1000}.wav"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert вернул null")
        resolver.openOutputStream(uri)!!.use { out ->
            out.write(header(pcm.size))
            out.write(pcm)
        }
        name
    } catch (e: Exception) {
        SpikeLog.event("WAV: ${e.message}")
        null
    }

    private fun header(dataLen: Int): ByteArray = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataLen); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataLen)
    }.array()
}

/** What the phone is doing right now: the context every measurement needs. */
object DeviceState {
    fun describe(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val net = when {
            caps == null -> "нет сети"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi${wifiRssi(context)}"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "мобильная сеть"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "другая сеть"
        }
        val battery = context.getSystemService(BatteryManager::class.java)
        val charging = battery.isCharging
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return "экран ${if (pm.isInteractive) "ВКЛ" else "выкл"}, Doze ${if (pm.isDeviceIdleMode) "ДА" else "нет"}, " +
            "энергосбережение ${if (pm.isPowerSaveMode) "да" else "нет"}, " +
            "исключение из оптимизации батареи ${if (pm.isIgnoringBatteryOptimizations(context.packageName)) "да" else "НЕТ"}, " +
            "сеть: $net, батарея $level%${if (charging) " (заряжается)" else ""}"
    }

    @Suppress("DEPRECATION")
    private fun wifiRssi(context: Context): String = try {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java)
        val info = wm.connectionInfo
        if (info != null && info.rssi > -127) " (RSSI ${info.rssi} dBm, ${info.linkSpeed} Мбит/с)" else ""
    } catch (_: Exception) {
        ""
    }
}
