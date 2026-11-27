package com.sbkubric.robby.spike.sip

import org.pjsip.pjsua2.AudioMediaPort
import org.pjsip.pjsua2.ByteVector
import org.pjsip.pjsua2.MediaFrame
import org.pjsip.pjsua2.pjmedia_frame_type
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stand-in for the external module: a conference-bridge port that
 *  - gives the bridge 20 ms frames of PCM16 LE mono 8 kHz (160 samples, 320 bytes): the frame shape the module will send;
 *  - counts and measures the frames that come back from the office (what will go to the module later),
 *    and optionally keeps the first [MAX_WAV_SECONDS] of them for a WAV file.
 *
 * Callbacks run on PJSIP's media clock thread (driven by the null sound device), never on the UI thread.
 */
class TonePort(@Volatile var tone: SpikeTone) : AudioMediaPort() {

    // Counters are read by the stats timer on another thread.
    @Volatile var framesSent = 0L; private set
    @Volatile var framesReceived = 0L; private set
    @Volatile var framesReceivedEmpty = 0L; private set
    @Volatile var framesReceivedVoiced = 0L; private set
    @Volatile var firstVoicedAt = 0L; private set   // elapsedRealtime of the first non-silent frame from the office
    @Volatile var lastRxRmsDbfs = -99.0; private set
    @Volatile var peakRxRmsDbfs = -99.0; private set

    private val out = ByteVector(FRAME_BYTES, 0.toShort())
    private val pcm = ShortArray(SAMPLES)
    private var rxBytes = ByteArray(FRAME_BYTES)
    private var sampleIndex = 0L

    private val wavLock = Any()
    private var wav: ByteArray? = null
    private var wavLen = 0

    /** Called at the start of each call. */
    fun resetCounters(captureWav: Boolean) {
        framesSent = 0; framesReceived = 0; framesReceivedEmpty = 0; framesReceivedVoiced = 0
        firstVoicedAt = 0; lastRxRmsDbfs = -99.0; peakRxRmsDbfs = -99.0
        synchronized(wavLock) {
            wav = if (captureWav) ByteArray(MAX_WAV_SECONDS * 8000 * 2) else null
            wavLen = 0
        }
    }

    /** The captured incoming PCM (16 bit LE, 8 kHz mono), or null; stops capturing. */
    fun takeWav(): ByteArray? = synchronized(wavLock) {
        val w = wav ?: return null
        wav = null
        if (wavLen == 0) null else w.copyOf(wavLen)
    }

    override fun onFrameRequested(frame: MediaFrame) {
        fillTone()
        for (i in 0 until SAMPLES) {
            val s = pcm[i].toInt()
            out.set(2 * i, (s and 0xFF).toShort())
            out.set(2 * i + 1, ((s shr 8) and 0xFF).toShort())
        }
        frame.buf = out
        frame.size = FRAME_BYTES.toLong()
        frame.type = pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO
        framesSent++
    }

    override fun onFrameReceived(frame: MediaFrame) {
        if (frame.type != pjmedia_frame_type.PJMEDIA_FRAME_TYPE_AUDIO || frame.size == 0L) {
            framesReceivedEmpty++
            return
        }
        val buf = frame.buf
        val n: Int = buf.size and 1.inv()
        if (rxBytes.size < n) rxBytes = ByteArray(n)
        var sumSq = 0.0
        for (i in 0 until n step 2) {
            val lo = buf.get(i).toInt() and 0xFF
            val hi = buf.get(i + 1).toInt() and 0xFF
            val s = ((hi shl 8) or lo).toShort().toInt()
            sumSq += (s * s).toDouble()
            rxBytes[i] = lo.toByte(); rxBytes[i + 1] = hi.toByte()
        }
        synchronized(wavLock) {
            val w = wav
            if (w != null) {
                val len = minOf(n, w.size - wavLen)
                if (len > 0) { System.arraycopy(rxBytes, 0, w, wavLen, len); wavLen += len }
            }
        }
        framesReceived++
        val samples = n / 2
        val rms = if (samples > 0) sqrt(sumSq / samples) else 0.0
        val dbfs = if (rms < 1.0) -99.0 else 20 * kotlin.math.log10(rms / 32768.0)
        lastRxRmsDbfs = dbfs
        if (dbfs > peakRxRmsDbfs) peakRxRmsDbfs = dbfs
        if (dbfs > VOICED_DBFS) {
            framesReceivedVoiced++
            if (firstVoicedAt == 0L) firstVoicedAt = android.os.SystemClock.elapsedRealtime()
        }
    }

    private fun fillTone() {
        for (i in 0 until SAMPLES) {
            val n = sampleIndex + i
            val on = when (tone) {
                SpikeTone.CONTINUOUS -> true
                // 2 s cycle: three 150 ms beeps with 100 ms gaps, then silence.
                SpikeTone.BEEPS -> {
                    val ms = (n % 16000) / 8
                    ms < 750 && (ms % 250) < 150
                }
            }
            pcm[i] = if (on) SINE[(n % SINE.size).toInt()] else 0
        }
        sampleIndex += SAMPLES
    }

    companion object {
        const val SAMPLES = 160          // 20 ms at 8 kHz
        const val FRAME_BYTES = SAMPLES * 2
        const val MAX_WAV_SECONDS = 120
        private const val VOICED_DBFS = -50.0
        private const val AMPLITUDE = 8000.0  // about -12 dBFS
        /** One period of 1 kHz at 8 kHz. */
        private val SINE = ShortArray(8) { (AMPLITUDE * sin(2 * PI * it / 8)).roundToInt().toShort() }
    }
}
