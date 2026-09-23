package com.alexis.amplificador

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import kotlin.math.log10
import kotlin.math.min

class MainActivity : Activity() {

    private data class Palette(
        val bg: Int, val panel: Int, val ink: Int, val muted: Int, val line: Int,
        val left: Int, val right: Int, val go: Int, val warn: Int
    )

    private lateinit var c: Palette
    private lateinit var prefs: SharedPreferences
    private lateinit var power: Button
    private lateinit var status: TextView
    private lateinit var meterL: ProgressBar
    private lateinit var meterR: ProgressBar
    private lateinit var sbLeft: SeekBar
    private lateinit var sbRight: SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private var wasOn = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences("ajustes", MODE_PRIVATE)
        if (!AudioEngine.running) loadPrefs()

        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        c = if (night) Palette(
            bg = 0xFF101614.toInt(), panel = 0xFF18211E.toInt(), ink = 0xFFEAF0ED.toInt(),
            muted = 0xFF9AA8A2.toInt(), line = 0xFF2A3632.toInt(), left = 0xFF6FA0F0.toInt(),
            right = 0xFFF07A6E.toInt(), go = 0xFF3CC495.toInt(), warn = 0xFFE6A640.toInt()
        ) else Palette(
            bg = 0xFFEEF2F0.toInt(), panel = 0xFFFFFFFF.toInt(), ink = 0xFF101614.toInt(),
            muted = 0xFF56635E.toInt(), line = 0xFFD5DDD9.toInt(), left = 0xFF1F5FBF.toInt(),
            right = 0xFFC2362B.toInt(), go = 0xFF0E7A5A.toInt(), warn = 0xFF9A5B00.toInt()
        )
        setContentView(buildUi())
    }

    override fun onResume() {
        super.onResume()
        wasOn = !isOn() // fuerza a refrescar el botón
        handler.post(tick)
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    // ---------- Ajustes guardados ----------

    private fun loadPrefs() {
        AudioEngine.masterDb = prefs.getInt("master", 10).toFloat()
        AudioEngine.leftDb = prefs.getInt("left", 0).toFloat()
        AudioEngine.rightDb = prefs.getInt("right", 0).toFloat()
        AudioEngine.voiceDb = prefs.getInt("voice", 6).toFloat()
        AudioEngine.lowCutHz = prefs.getInt("lowcut", 150).toFloat()
        AudioEngine.noiseReduction = prefs.getBoolean("nr", false)
        AudioEngine.inputDeviceId = prefs.getInt("mic", 0)
    }

    private fun save(key: String, v: Int) = prefs.edit().putInt(key, v).apply()

    // ---------- Encendido ----------

    private fun isOn() = AudioEngine.running || AudioEngine.restarting

    private fun toggle() {
        if (isOn()) {
            startService(Intent(this, AmpService::class.java).setAction(AmpService.ACTION_STOP))
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startAmp()
        } else {
            val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(perms.toTypedArray(), 7)
        }
    }

    private fun startAmp() {
        status.text = "Iniciando…"
        startForegroundService(Intent(this, AmpService::class.java))
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != 7) return
        val i = permissions.indexOf(Manifest.permission.RECORD_AUDIO)
        if (i >= 0 && grantResults.getOrNull(i) == PackageManager.PERMISSION_GRANTED) {
            startAmp()
        } else {
            status.setTextColor(c.right)
            status.text = "Sin permiso de micrófono. Actívalo en Ajustes > Apps > Amplificador > Permisos."
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val on = isOn()
            meterL.progress = toMeter(AudioEngine.levelL)
            meterR.progress = toMeter(AudioEngine.levelR)
            if (on != wasOn) {
                power.text = if (on) "Apagar amplificador" else "Encender amplificador"
                power.background = rounded(if (on) c.ink else c.go, 12f)
                power.setTextColor(if (on) c.bg else Color.WHITE)
                if (!on) {
                    val err = AudioEngine.lastError
                    status.setTextColor(if (err != null) c.right else c.muted)
                    status.text = if (err != null) "Se detuvo: $err" else "Apagado."
                    stopService(Intent(this@MainActivity, AmpService::class.java))
                }
                wasOn = on
            }
            if (on) {
                status.setTextColor(c.muted)
                status.text = "Escuchando. Retraso del teléfono ≈ ${AudioEngine.latencyMs} ms (el Bluetooth agrega más)."
            }
            handler.postDelayed(this, 50)
        }
    }

    private fun toMeter(rms: Float): Int {
        val db = 20f * log10(rms + 1e-8f)
        return (((db + 60f) / 60f) * 100f).toInt().coerceIn(0, 100)
    }

    // ---------- Interfaz ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun rounded(fill: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius * resources.displayMetrics.density
        stroke?.let { setStroke(dp(1), it) }
    }

    private fun text(s: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun label(s: String) = text(s.uppercase(), 12f, c.muted, bold = true).apply { letterSpacing = 0.08f }

    private fun panel() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(c.panel, 14f, c.line)
        setPadding(dp(18), dp(18), dp(18), dp(18))
    }

    private fun slider(
        parent: LinearLayout, title: String, color: Int, lo: Int, hi: Int, value: Int, step: Int,
        fmt: (Int) -> String, onChange: (Int) -> Unit
    ): SeekBar {
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val valueView = text(fmt(value), 15f, c.muted)
        head.addView(text(title, 16f, color, bold = true), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(valueView)
        val sb = SeekBar(this)
        sb.max = (hi - lo) / step
        sb.progress = ((value - lo) / step).coerceIn(0, sb.max)
        sb.progressTintList = ColorStateList.valueOf(color)
        sb.thumbTintList = ColorStateList.valueOf(color)
        sb.progressBackgroundTintList = ColorStateList.valueOf(c.line)
        sb.tag = intArrayOf(lo, step)
        sb.setPadding(dp(12), dp(14), dp(12), dp(14))
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                val x = lo + p * step
                valueView.text = fmt(x)
                onChange(x)
            }
            override fun onStartTrackingTouch(s: SeekBar) {}
            override fun onStopTrackingTouch(s: SeekBar) {}
        })
        parent.addView(head, lp(14))
        parent.addView(sb, lp(0))
        return sb
    }

    private fun setValue(sb: SeekBar, v: Int) {
        val t = sb.tag as IntArray
        sb.progress = ((v - t[0]) / t[1]).coerceIn(0, sb.max)
    }

    private fun signedDb(v: Int) = (if (v > 0) "+" else "") + "$v dB"

    private fun balance(side: Char) {
        if (side == 'C') {
            setValue(sbLeft, 0); setValue(sbRight, 0); return
        }
        val l = AudioEngine.leftDb.toInt()
        val r = AudioEngine.rightDb.toInt()
        if (side == 'L') {
            if (r > 0) setValue(sbRight, r - 3) else setValue(sbLeft, min(12, l + 3))
        } else {
            if (l > 0) setValue(sbLeft, l - 3) else setValue(sbRight, min(12, r + 3))
        }
    }

    private fun meterRow(name: String, color: Int, bar: ProgressBar) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(text(name, 15f, color, bold = true), LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT))
        addView(bar, LinearLayout.LayoutParams(0, dp(12), 1f))
    }

    private fun meter(color: Int) = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
        max = 100
        progressTintList = ColorStateList.valueOf(color)
        progressBackgroundTintList = ColorStateList.valueOf(c.line)
    }

    private fun micSpinner(): Spinner {
        val am = getSystemService(AudioManager::class.java)
        val wanted = setOf(
            AudioDeviceInfo.TYPE_BUILTIN_MIC, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET
        )
        val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS).filter { it.type in wanted }
        val ids = mutableListOf(0)
        val names = mutableListOf("Automático (micrófono del teléfono)")
        devices.forEach { d ->
            ids += d.id
            names += when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_MIC ->
                    "Micrófono del teléfono" + (
                        if (Build.VERSION.SDK_INT >= 28) d.address?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                        else " ${d.id}"
                    )
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Micrófono con cable"
                else -> "Micrófono USB-C"
            }
        }
        return Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, names)
            setSelection(ids.indexOf(AudioEngine.inputDeviceId).coerceAtLeast(0))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    val newId = ids[pos]
                    if (newId != AudioEngine.inputDeviceId) {
                        AudioEngine.inputDeviceId = newId
                        save("mic", newId)
                        AudioEngine.restartIfRunning(this@MainActivity)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(20), dp(16), dp(32))
        }

        root.addView(text("Amplificador Oído a Oído", 26f, c.ink, bold = true))
        root.addView(text("Amplifica lo que capta el micrófono y ajusta cada oído por separado.", 15f, c.muted), lp(4))

        root.addView(text(
            "Antes de empezar: ponte los audífonos y deja el volumen bajo. Súbelo poco a poco. " +
                "Si escuchas un pitido (acople), baja el volumen o aleja el teléfono.",
            14f, c.ink
        ).apply {
            background = rounded(c.panel, 10f, c.warn)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }, lp(16))

        // Encendido y medidores
        val p1 = panel()
        power = Button(this).apply {
            isAllCaps = false
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            stateListAnimator = null
            setPadding(0, dp(16), 0, dp(16))
            setOnClickListener { toggle() }
        }
        p1.addView(power, lp(0))
        status = text("Apagado.", 14f, c.muted)
        p1.addView(status, lp(10))
        meterL = meter(c.left)
        meterR = meter(c.right)
        p1.addView(meterRow("✕ Izq.", c.left, meterL), lp(12))
        p1.addView(meterRow("○ Der.", c.right, meterR), lp(8))
        root.addView(p1, lp(16))

        // Volumen
        val p2 = panel()
        p2.addView(label("Volumen"))
        slider(p2, "General", c.ink, 0, 30, AudioEngine.masterDb.toInt(), 1, { "+$it dB" }) {
            AudioEngine.masterDb = it.toFloat(); save("master", it)
        }
        sbLeft = slider(p2, "✕ Oído izquierdo", c.left, -20, 12, AudioEngine.leftDb.toInt(), 1, ::signedDb) {
            AudioEngine.leftDb = it.toFloat(); save("left", it)
        }
        sbRight = slider(p2, "○ Oído derecho", c.right, -20, 12, AudioEngine.rightDb.toInt(), 1, ::signedDb) {
            AudioEngine.rightDb = it.toFloat(); save("right", it)
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Más izq." to 'L', "Centrar" to 'C', "Más der." to 'R').forEachIndexed { i, (t, side) ->
            val b = Button(this).apply {
                text = t
                isAllCaps = false
                textSize = 14f
                setTextColor(c.ink)
                stateListAnimator = null
                background = rounded(Color.TRANSPARENT, 999f, c.line)
                setOnClickListener { balance(side) }
            }
            row.addView(b, LinearLayout.LayoutParams(0, dp(44), 1f).apply { if (i < 2) marginEnd = dp(8) })
        }
        p2.addView(row, lp(14))
        root.addView(p2, lp(16))

        // Claridad
        val p3 = panel()
        p3.addView(label("Claridad"))
        slider(p3, "Realce de voz (2,5 kHz)", c.ink, 0, 15, AudioEngine.voiceDb.toInt(), 1, { "+$it dB" }) {
            AudioEngine.voiceDb = it.toFloat(); save("voice", it)
        }
        slider(p3, "Quitar graves (retumbe)", c.ink, 40, 500, AudioEngine.lowCutHz.toInt(), 10, { "bajo $it Hz" }) {
            AudioEngine.lowCutHz = it.toFloat(); save("lowcut", it)
        }
        val nr = Switch(this).apply {
            text = "Reducción de ruido"
            textSize = 16f
            setTextColor(c.ink)
            typeface = Typeface.DEFAULT_BOLD
            isChecked = AudioEngine.noiseReduction
            setOnCheckedChangeListener { _, v ->
                AudioEngine.noiseReduction = v
                prefs.edit().putBoolean("nr", v).apply()
                AudioEngine.restartIfRunning(this@MainActivity)
            }
        }
        p3.addView(nr, lp(16))
        p3.addView(text("Ayuda en lugares ruidosos; puede recortar sonidos suaves.", 13f, c.muted), lp(2))
        p3.addView(text("Micrófono", 16f, c.ink, bold = true), lp(16))
        p3.addView(micSpinner(), lp(4))
        p3.addView(text(
            "Si tu teléfono muestra varios micrófonos (abajo, atrás), prueba cuál capta mejor. " +
                "Un micrófono USB-C con cable da el mejor resultado.",
            13f, c.muted
        ), lp(2))
        root.addView(p3, lp(16))

        root.addView(text(
            "Sigue funcionando con la pantalla apagada; se apaga desde aquí o desde la notificación. " +
                "Un limitador evita que el sonido supere un nivel seguro, pero no reemplaza un audífono médico.",
            13f, c.muted
        ), lp(16))

        window.decorView.setBackgroundColor(c.bg)
        return ScrollView(this).apply {
            setBackgroundColor(c.bg)
            fitsSystemWindows = true
            addView(root)
        }
    }
}
