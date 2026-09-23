package com.alexis.amplificador

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Process
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Motor de audio: micrófono -> filtros -> ganancia por oído -> limitador -> audífonos.
 * Corre en su propio hilo de alta prioridad con búferes lo más pequeños posible.
 */
object AudioEngine {
    // Ajustes (los cambia la pantalla; el hilo de audio los lee en cada bloque)
    @Volatile var masterDb = 10f
    @Volatile var leftDb = 0f
    @Volatile var rightDb = 0f
    @Volatile var voiceDb = 6f
    @Volatile var lowCutHz = 150f
    @Volatile var noiseReduction = false
    @Volatile var inputDeviceId = 0 // 0 = automático (micrófono del teléfono)

    // Estado para la pantalla
    @Volatile var levelL = 0f
    @Volatile var levelR = 0f
    @Volatile var latencyMs = 0
    @Volatile var lastError: String? = null
    @Volatile var running = false
        private set
    @Volatile var restarting = false
        private set

    private var thread: Thread? = null
    @Volatile private var stopFlag = false

    @Synchronized
    fun start(ctx: Context) {
        if (running) return
        stopFlag = false
        lastError = null
        running = true
        val app = ctx.applicationContext
        thread = Thread({ runLoop(app) }, "amplificador-audio").also { it.start() }
    }

    @Synchronized
    fun stop() {
        stopFlag = true
        thread?.join(1500)
        thread = null
        running = false
        levelL = 0f
        levelR = 0f
    }

    /** Reinicia para aplicar cambios de micrófono o reducción de ruido. */
    @Synchronized
    fun restartIfRunning(ctx: Context) {
        if (!running) return
        restarting = true
        try {
            stop()
            start(ctx)
        } finally {
            restarting = false
        }
    }

    private fun findInput(am: AudioManager): AudioDeviceInfo? {
        val inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
        if (inputDeviceId != 0) inputs.firstOrNull { it.id == inputDeviceId }?.let { return it }
        return inputs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    }

    @SuppressLint("MissingPermission")
    private fun runLoop(ctx: Context) {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val burst = (am.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 192)
            .coerceIn(32, 1024)
        val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"

        val source = when {
            noiseReduction -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            unprocessed -> MediaRecorder.AudioSource.UNPROCESSED
            else -> MediaRecorder.AudioSource.VOICE_RECOGNITION
        }

        var record: AudioRecord? = null
        var track: AudioTrack? = null
        var ns: NoiseSuppressor? = null
        try {
            val minIn = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            val rec = AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minIn, burst * 4 * 2))
                .build()
            record = rec
            if (rec.state != AudioRecord.STATE_INITIALIZED) error("No se pudo abrir el micrófono")
            findInput(am)?.let { rec.setPreferredDevice(it) }

            if (noiseReduction && NoiseSuppressor.isAvailable()) {
                ns = NoiseSuppressor.create(rec.audioSessionId)
                ns?.enabled = true
            }

            val minOut = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
            val trk = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(rate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minOut)
                .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
            track = trk
            if (trk.state != AudioTrack.STATE_INITIALIZED) error("No se pudo abrir la salida de audio")

            // Empezar con el búfer de salida al mínimo y agrandarlo solo si hay cortes.
            var outFrames = min(trk.bufferCapacityInFrames, burst * 2)
            trk.setBufferSizeInFrames(outFrames)
            var lastUnderruns = 0

            val inBuf = FloatArray(burst)
            val outBuf = FloatArray(burst * 2)
            val dsp = Dsp(rate.toFloat())

            rec.startRecording()
            trk.play()
            trk.write(FloatArray(burst * 2), 0, burst * 2, AudioTrack.WRITE_NON_BLOCKING)

            while (!stopFlag) {
                val n = rec.read(inBuf, 0, burst, AudioRecord.READ_BLOCKING)
                if (n < 0) error("Error al leer el micrófono ($n)")
                if (n == 0) continue
                dsp.process(inBuf, outBuf, n)
                trk.write(outBuf, 0, n * 2, AudioTrack.WRITE_BLOCKING)

                val u = trk.underrunCount
                if (u > lastUnderruns) {
                    lastUnderruns = u
                    if (outFrames < trk.bufferCapacityInFrames) {
                        outFrames = min(trk.bufferCapacityInFrames, outFrames + burst)
                        trk.setBufferSizeInFrames(outFrames)
                    }
                }
                latencyMs = ((trk.bufferSizeInFrames + burst * 2) * 1000L / rate).toInt()
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
        } finally {
            try { record?.stop() } catch (_: Exception) {}
            try { track?.stop() } catch (_: Exception) {}
            ns?.release()
            record?.release()
            track?.release()
            levelL = 0f
            levelR = 0f
            running = false
        }
    }

    private fun dbToLin(db: Float) = 10f.pow(db / 20f)

    /** Procesamiento de la señal, muestra por muestra. */
    private class Dsp(private val fs: Float) {
        private val highpass = Biquad()
        private val presence = Biquad()
        private var lastLow = -1f
        private var lastVoice = -99f
        private var gMaster = 0f // empieza en 0 para entrar suave
        private var gLeft = 0f
        private var gRight = 0f
        private var limGain = 1f
        private val smooth = 1f - exp(-1f / (0.02f * fs))   // ~20 ms
        private val release = 1f - exp(-1f / (0.15f * fs))  // 150 ms
        private val threshold = 0.5f                          // ≈ -6 dBFS

        fun process(input: FloatArray, out: FloatArray, n: Int) {
            val low = AudioEngine.lowCutHz
            val voice = AudioEngine.voiceDb
            if (low != lastLow) { highpass.setHighpass(low, 0.707f, fs); lastLow = low }
            if (voice != lastVoice) { presence.setPeaking(2500f, 0.9f, voice, fs); lastVoice = voice }
            val tM = AudioEngine.dbToLin(AudioEngine.masterDb)
            val tL = AudioEngine.dbToLin(AudioEngine.leftDb)
            val tR = AudioEngine.dbToLin(AudioEngine.rightDb)

            var sumL = 0f
            var sumR = 0f
            for (i in 0 until n) {
                gMaster += (tM - gMaster) * smooth
                gLeft += (tL - gLeft) * smooth
                gRight += (tR - gRight) * smooth

                val x = presence.run(highpass.run(input[i])) * gMaster
                var l = x * gLeft
                var r = x * gRight

                // Limitador enlazado para ambos oídos
                val peak = max(abs(l), abs(r))
                val target = if (peak > threshold) threshold / peak else 1f
                limGain = if (target < limGain) target else limGain + (target - limGain) * release
                l = (l * limGain).coerceIn(-0.98f, 0.98f)
                r = (r * limGain).coerceIn(-0.98f, 0.98f)

                out[2 * i] = l
                out[2 * i + 1] = r
                sumL += l * l
                sumR += r * r
            }
            AudioEngine.levelL = sqrt(sumL / n)
            AudioEngine.levelR = sqrt(sumR / n)
        }
    }

    /** Filtro biquad (fórmulas RBJ), forma directa transpuesta II. */
    private class Biquad {
        private var b0 = 1f; private var b1 = 0f; private var b2 = 0f
        private var a1 = 0f; private var a2 = 0f
        private var z1 = 0f; private var z2 = 0f

        fun run(x: Float): Float {
            val y = b0 * x + z1
            z1 = b1 * x - a1 * y + z2
            z2 = b2 * x - a2 * y
            return y
        }

        fun setHighpass(f: Float, q: Float, fs: Float) {
            val w = 2.0 * PI * f / fs
            val c = cos(w); val alpha = sin(w) / (2.0 * q)
            val a0 = 1.0 + alpha
            set((1 + c) / 2 / a0, -(1 + c) / a0, (1 + c) / 2 / a0, -2 * c / a0, (1 - alpha) / a0)
        }

        fun setPeaking(f: Float, q: Float, gainDb: Float, fs: Float) {
            val a = 10.0.pow(gainDb / 40.0)
            val w = 2.0 * PI * f / fs
            val c = cos(w); val alpha = sin(w) / (2.0 * q)
            val a0 = 1.0 + alpha / a
            set((1 + alpha * a) / a0, -2 * c / a0, (1 - alpha * a) / a0, -2 * c / a0, (1 - alpha / a) / a0)
        }

        private fun set(nb0: Double, nb1: Double, nb2: Double, na1: Double, na2: Double) {
            b0 = nb0.toFloat(); b1 = nb1.toFloat(); b2 = nb2.toFloat()
            a1 = na1.toFloat(); a2 = na2.toFloat()
        }
    }
}
