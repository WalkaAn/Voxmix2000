package com.example.dbxcomp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.Locale
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

private val C_BG = Color.rgb(16, 20, 24)
private val C_STRIP = Color.rgb(12, 16, 20)
private val C_TEXT = Color.rgb(170, 180, 190)
private val C_BTN = Color.rgb(36, 46, 58)

/** Bar meter horizontal sederhana. */
class MeterView(
    context: Context,
    private val minValue: Float,
    private val maxValue: Float,
    private val baseColor: Int,
    private val redAbove: Float? = null
) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    var value: Float = minValue
        set(v) {
            field = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        paint.color = Color.rgb(40, 46, 54)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        val frac = ((value - minValue) / (maxValue - minValue)).coerceIn(0f, 1f)
        paint.color =
            if (redAbove != null && value > redAbove) Color.rgb(230, 70, 60) else baseColor
        canvas.drawRect(0f, 0f, width * frac, height.toFloat(), paint)
    }
}

/** Tombol ON/OFF: ON menyala seperti lampu LED, OFF lebih redup. */
class LedToggle(
    context: Context,
    private val onText: String = "ON",
    private val offText: String = "OFF",
    private val onFill: Int = Color.rgb(46, 204, 113),
    private val onStroke: Int = Color.rgb(150, 255, 195),
    private val onTextColor: Int = Color.rgb(4, 40, 20)
) : TextView(context) {
    private val shape = GradientDrawable()
    private var ready = false

    var onChange: ((Boolean) -> Unit)? = null

    var checked: Boolean = false
        set(v) {
            if (field != v) {
                field = v
                refresh()
            }
        }

    init {
        val d = resources.displayMetrics.density
        gravity = Gravity.CENTER
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        isClickable = true
        shape.cornerRadius = 8f * d
        background = shape
        setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
        setOnClickListener {
            if (isEnabled) {
                checked = !checked
                onChange?.invoke(checked)
            }
        }
        ready = true
        refresh()
    }

    private fun refresh() {
        if (!ready) return
        val d = resources.displayMetrics.density
        if (checked) {
            shape.setColor(onFill)
            shape.setStroke((1.5f * d).toInt(), onStroke)
            setTextColor(onTextColor)
            text = onText
        } else {
            shape.setColor(Color.rgb(34, 40, 38))
            shape.setStroke((1f * d).toInt(), Color.rgb(62, 74, 68))
            setTextColor(Color.rgb(112, 128, 120))
            text = offText
        }
        alpha = if (isEnabled) 1f else 0.4f
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        refresh()
    }
}

class MainActivity : Activity() {

    private companion object {
        const val REQ_PERM = 1
        const val REQ_CAPTURE = 2
        const val REQ_IMPORT = 3
        const val CHANNEL_HINT =
            "Berlaku saat START. Stereo asli hanya jika HP atau interface memberi 2 channel."
        const val INPUT_IDLE = "Input: belum aktif (tekan START)"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val meters = FloatArray(11)

    // Nilai terkini tiap parameter (NaN = belum diset) untuk STRIP TERPILIH + parameter global
    // (bypass, monitor, master), dan fungsi untuk mengubahnya dari preset / ganti strip.
    private val values = FloatArray(AudioEngine.P_COUNT) { Float.NaN }

    // Nilai tersimpan tiap strip (A mic, B internal, C ROOT). `values` adalah salinan kerja
    // untuk strip yang sedang dipilih (curStrip).
    private val stripValues = Array(AudioEngine.STRIPS) { PresetStore.defaultsFor(it) }
    private var curStrip = 0
    private val stripButtons = ArrayList<TextView>()
    private val stripNames = listOf("A  Mic", "B  Internal", "C  ROOT")
    private val setters = arrayOfNulls<(Float) -> Unit>(AudioEngine.P_COUNT)

    private lateinit var prefs: SharedPreferences
    private lateinit var recorder: WavRecorder

    private lateinit var scroll: ScrollView
    private val tabButtons = ArrayList<TextView>()
    private val tabPages = ArrayList<LinearLayout>()

    private lateinit var meterIn: MeterView
    private lateinit var meterOut: MeterView
    private lateinit var meterGr: MeterView
    private lateinit var startButton: Button
    private lateinit var recordButton: Button
    private lateinit var abToggle: LedToggle
    private lateinit var stereoToggle: LedToggle
    private var srcMask = 1  // kombinasi SRC_MIC / SRC_INT / SRC_ROOT yang dipilih
    private val srcToggles = ArrayList<LedToggle>()
    private lateinit var channelText: TextView
    private lateinit var statusText: TextView
    private lateinit var inSpinner: Spinner
    private lateinit var outSpinner: Spinner
    private lateinit var presetSpinner: Spinner
    private lateinit var presetDetails: TextView
    private var monitorVolumeDb = 0f

    private var presetFiles: List<String> = emptyList()
    private var inDevices: List<AudioDeviceInfo?> = emptyList()
    private var outDevices: List<AudioDeviceInfo?> = emptyList()
    private var running = false
    private var activeMask = 0  // sumber yang benar-benar berjalan

    private val ticker = object : Runnable {
        override fun run() {
            AudioEngine.nativeGetMeters(meters)
            // IN dan GR = strip terpilih; MST = output master.
            meterIn.value = max(meters[curStrip * 3], meterIn.value - 2f)
            meterGr.value = max(meters[curStrip * 3 + 2], meterGr.value - 0.8f)
            meterOut.value = max(meters[9], meterOut.value - 2f)
            if (recorder.isRecording) {
                recordButton.text = "■ ${formatTime(recorder.elapsedMs)}"
            }
            if (running) handler.postDelayed(this, 33)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        prefs = getSharedPreferences("dbxcomp", Context.MODE_PRIVATE)
        recorder = WavRecorder(this) { location -> onRecordingDone(location) }
        monitorVolumeDb = prefs.getFloat("monitor_volume_db", 0f).coerceIn(-60f, 0f)
        recorder.infoSnapshot = { recordingSnapshot() }

        // Pulihkan pengaturan terakhir tiap strip (data lama "last" masuk ke strip A).
        for (st in 0 until AudioEngine.STRIPS) {
            val csv = prefs.getString("last_s$st", null) ?: if (st == 0) prefs.getString("last", null) else null
            val saved = parseValues(csv) ?: continue
            for (i in saved.indices) if (!saved[i].isNaN()) stripValues[st][i] = saved[i]
        }
        // Keadaan sesaat tidak dipulihkan: kanal L/R aktif, mute mati, monitor hidup, bypass mati.
        for (st in 0 until AudioEngine.STRIPS) {
            stripValues[st][AudioEngine.P_GATE_L] = 1f
            stripValues[st][AudioEngine.P_GATE_R] = 1f
            stripValues[st][AudioEngine.P_MUTE] = 0f
            stripValues[st][AudioEngine.P_M_MUTE] = 0f
        }
        stripValues[0].copyInto(values)
        values[AudioEngine.P_MONITOR_VOLUME] = monitorVolumeDb
        values[AudioEngine.P_BYPASS] = Float.NaN
        values[AudioEngine.P_GATE_L] = Float.NaN
        values[AudioEngine.P_GATE_R] = Float.NaN
        values[AudioEngine.P_MONITOR] = Float.NaN
        srcMask = prefs.getInt("src_mask", 1) and 7

        val outer = LinearLayout(this)
        outer.orientation = LinearLayout.VERTICAL
        outer.setBackgroundColor(C_BG)

        // Bilah atas yang selalu terlihat: meter + START + REKAM + BYPASS, lalu tab.
        outer.addView(buildStrip(), LinearLayout.LayoutParams(MATCH, WRAP))
        outer.addView(buildTabBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        outer.addView(buildStripSelector(), LinearLayout.LayoutParams(MATCH, WRAP))

        scroll = ScrollView(this)
        val content = LinearLayout(this)
        content.orientation = LinearLayout.VERTICAL
        content.setPadding(dp(12), dp(10), dp(12), dp(28))
        scroll.addView(content)
        outer.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f))
        setContentView(outer)

        val pages = listOf(
            buildSourcePage(), buildStripPage(), buildTonePage(), buildCompPage(),
            buildReverbPage(), buildMasterPage(), buildPresetPage()
        )
        for (p in pages) {
            p.visibility = View.GONE
            content.addView(p, LinearLayout.LayoutParams(MATCH, WRAP))
            tabPages.add(p)
        }
        migrateOldPresets()
        refreshPresets()
        pushAll()
        updateStripButtons()
        selectTab(prefs.getInt("tab", 0).coerceIn(0, tabPages.size - 1))
    }

    // ---------- Susunan layar ----------

    private fun buildStrip(): LinearLayout {
        val strip = LinearLayout(this)
        strip.orientation = LinearLayout.HORIZONTAL
        strip.gravity = Gravity.CENTER_VERTICAL
        strip.setBackgroundColor(C_STRIP)
        strip.setPadding(dp(10), dp(6), dp(10), dp(6))

        meterIn = MeterView(this, -60f, 0f, Color.rgb(60, 200, 120), -3f)
        meterGr = MeterView(this, 0f, 20f, Color.rgb(240, 160, 40))
        meterOut = MeterView(this, -60f, 0f, Color.rgb(60, 200, 120), -3f)

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val names = listOf("IN", "GR", "MST")
        val bars = listOf(meterIn, meterGr, meterOut)
        for (k in names.indices) {
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.gravity = Gravity.CENTER_VERTICAL
            val tv = TextView(this)
            tv.text = names[k]
            tv.textSize = 9f
            tv.setTextColor(Color.rgb(140, 150, 160))
            row.addView(tv, LinearLayout.LayoutParams(dp(26), WRAP))
            row.addView(bars[k], LinearLayout.LayoutParams(0, dp(7), 1f))
            col.addView(row, LinearLayout.LayoutParams(MATCH, dp(11)))
        }
        strip.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))

        startButton = smallButton("START", Color.rgb(30, 84, 58))
        startButton.setOnClickListener { if (running) stopAudio() else requestAndStart() }
        val lpStart = LinearLayout.LayoutParams(dp(58), dp(36))
        lpStart.leftMargin = dp(6)
        strip.addView(startButton, lpStart)

        recordButton = smallButton("● REK", Color.rgb(104, 36, 36))
        recordButton.setOnClickListener { toggleRecord() }
        val lpRec = LinearLayout.LayoutParams(dp(62), dp(36))
        lpRec.leftMargin = dp(6)
        strip.addView(recordButton, lpRec)

        val bypass = LedToggle(
            this, "BYP ON", "BYP OFF",
            Color.rgb(255, 150, 40), Color.rgb(255, 205, 130), Color.rgb(40, 20, 0)
        )
        bypass.textSize = 11f
        bypass.setPadding(dp(4), 0, dp(4), 0)
        bindToggle(bypass, AudioEngine.P_BYPASS, false)
        val lpByp = LinearLayout.LayoutParams(dp(66), dp(36))
        lpByp.leftMargin = dp(6)
        strip.addView(bypass, lpByp)
        return strip
    }

    private fun buildTabBar(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.setBackgroundColor(C_STRIP)
        bar.setPadding(dp(6), 0, dp(6), dp(6))
        val names = listOf("Sumber", "Strip", "Tone", "Komp", "Reverb", "Master", "Preset")
        for (i in names.indices) {
            val t = TextView(this)
            t.text = names[i]
            t.textSize = 11f
            t.gravity = Gravity.CENTER
            t.maxLines = 1
            t.setPadding(0, dp(8), 0, dp(8))
            t.setOnClickListener { selectTab(i) }
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.leftMargin = dp(2)
            lp.rightMargin = dp(2)
            bar.addView(t, lp)
            tabButtons.add(t)
        }
        return bar
    }

    private fun selectTab(i: Int) {
        for (k in tabPages.indices) {
            val sel = k == i
            tabPages[k].visibility = if (sel) View.VISIBLE else View.GONE
            val t = tabButtons[k]
            t.setTextColor(if (sel) Color.WHITE else Color.rgb(140, 150, 160))
            val bg = GradientDrawable()
            bg.cornerRadius = dp(8).toFloat()
            bg.setColor(if (sel) Color.rgb(38, 66, 96) else Color.rgb(24, 30, 36))
            t.background = bg
        }
        scroll.scrollTo(0, 0)
        prefs.edit().putInt("tab", i).apply()
    }

    private fun newPage(): LinearLayout {
        val page = LinearLayout(this)
        page.orientation = LinearLayout.VERTICAL
        return page
    }

    private fun buildSourcePage(): LinearLayout {
        val page = newPage()
        page.addView(
            label(
                "Pakai headphone atau audio interface. Mic HP + speaker HP = feedback.",
                12f, Color.rgb(240, 180, 80)
            )
        )

        val g1 = group(
            page, "Sumber & perangkat",
            "Suara internal: Android tidak bisa membisukan aplikasi sumber, jadi suara aslinya " +
                "tetap keluar normal. Agar tidak tumpang tindih dengan hasil olahan: matikan " +
                "\"Monitor output\" lalu REKAM, atau arahkan Output ke perangkat lain. " +
                "Aplikasi yang memblokir penangkapan (mis. Spotify, DRM) tidak bisa ditangkap.\n\n" +
                "Suara internal ROOT: butuh HP ter-root (izinkan akses su untuk DbxComp). " +
                "Menangkap SEMUA aplikasi, dan suara asli biasanya dibisukan (hanya hasil olahan " +
                "yang terdengar). Pilih Output ke perangkat tertentu (speaker HP / kabel), " +
                "jangan Default. Hasil bergantung pada HP dan versi Android.\n\n" +
                "Mic HP + speaker HP di ruangan yang sama menyebabkan feedback (howling). " +
                "Bluetooth = latensi tinggi."
        )
        g1.addView(label("Sumber input (boleh lebih dari satu sekaligus)"))
        val srcDefs = listOf(
            Triple(AudioEngine.SRC_MIC, "A  Mic / line-in / USB",
                "Strip A. Memakai pilihan Input di bawah."),
            Triple(AudioEngine.SRC_INT, "B  Suara internal HP",
                "Strip B. Android 10+, lewat izin tangkap layar/suara. Aplikasi yang memblokir " +
                    "penangkapan (mis. Spotify, DRM) tidak masuk."),
            Triple(AudioEngine.SRC_ROOT, "C  Suara internal ROOT",
                "Strip C. Butuh HP ter-root. Menangkap semua aplikasi. Biasanya suara asli " +
                    "dibisukan sehingga hanya hasil olahan yang terdengar.")
        )
        for ((bit, title, info) in srcDefs) {
            val t = LedToggle(this)
            t.checked = (srcMask and bit) != 0
            t.onChange = { on ->
                srcMask = if (on) srcMask or bit else srcMask and bit.inv()
                prefs.edit().putInt("src_mask", srcMask).apply()
                applySourceUi()
                updateStripButtons()
            }
            addToggleRow(g1, title, t, info)
            srcToggles.add(t)
        }
        g1.addView(
            label(
                "B dan C menangkap suara yang sama, jadi kalau dinyalakan bersamaan akan terdengar " +
                    "dobel. Bisukan salah satunya dengan tombol MUTE di tab Strip.",
                12f, Color.rgb(240, 180, 80)
            )
        )

        inDevices = deviceList(true)
        outDevices = deviceList(false)
        g1.addView(label("Input (untuk A)"))
        inSpinner = Spinner(this)
        styleSpinner(inSpinner)
        inSpinner.adapter = spinnerAdapter(inDevices.map { describe(it) })
        g1.addView(inSpinner)
        g1.addView(label("Output"))
        outSpinner = Spinner(this)
        styleSpinner(outSpinner)
        outSpinner.adapter = spinnerAdapter(outDevices.map { describe(it) })
        g1.addView(outSpinner)

        // Input stereo asli (2 channel). Berlaku saat START.
        stereoToggle = LedToggle(this)
        stereoToggle.checked = prefs.getBoolean("stereo_in", false)
        stereoToggle.onChange = { c -> prefs.edit().putBoolean("stereo_in", c).apply() }
        addToggleRow(g1, "Input stereo (2 channel)", stereoToggle, CHANNEL_HINT)
        channelText = label(INPUT_IDLE, 12f)
        g1.addView(channelText)

        applySourceUi()

        val g2 = group(page, "Kanal & monitor (L/R dan Link: strip terpilih)")
        addToggle(
            g2, "Monitor output", AudioEngine.P_MONITOR, true,
            "Dengar hasil olahan di output. Matikan untuk hanya merekam."
        )
        val lr = LinearLayout(this)
        lr.orientation = LinearLayout.HORIZONTAL
        g2.addView(lr, LinearLayout.LayoutParams(MATCH, WRAP))
        addToggle(lr, "L aktif", AudioEngine.P_GATE_L, true)
        addToggle(lr, "R aktif", AudioEngine.P_GATE_R, true)
        addToggle(
            g2, "Link L/R", AudioEngine.P_LINK, true,
            "Gain reduction sama di kiri dan kanan, jadi gambar stereo tidak bergeser saat " +
                "salah satu sisi lebih keras."
        )

        val g4 = group(page, "Rekam")
        abToggle = LedToggle(this)
        addToggleRow(
            g4, "Rekam A/B", abToggle,
            "Kiri = asli, kanan = hasil. Untuk dibuka di editor, bukan didengar di headphone. " +
                "Hanya untuk input mono; switch L/R diabaikan."
        )
        statusText = label(
            "WAV: Music/DbxComp. Info pendamping: Documents/DbxComp/Rekaman.", 12f
        )
        g4.addView(statusText)
        return page
    }

    private fun redToggle(): LedToggle = LedToggle(
        this, "MUTE", "OFF", Color.rgb(220, 60, 50), Color.rgb(255, 150, 140), Color.WHITE
    )

    private fun buildStripPage(): LinearLayout {
        val page = newPage()
        val g1 = group(
            page, "Input strip",
            "Semua kontrol di tab Strip, Tone, Komp, dan Reverb berlaku untuk strip yang dipilih " +
                "di bilah A / B / C. Tiap strip punya setelan sendiri-sendiri dan baru bertemu di Master."
        )
        val mute = redToggle()
        bindToggle(mute, AudioEngine.P_MUTE, false)
        val inner = mute.onChange
        mute.onChange = { on ->
            inner?.invoke(on)
            saveCurrentToStrip()
            updateStripButtons()
        }
        addToggleRow(g1, "Mute input strip", mute, "Membisukan input strip ini (reverb yang sedang menggema ikut habis).")
        addSlider(
            g1, "Input gain", AudioEngine.P_IN_GAIN, -60f, 48f, 0f, false, "dB", 1,
            "Mic/line-in: naikkan sampai puncak suara bicara sekitar -12 sampai -6 dBFS (meter IN). " +
                "Suara internal HP sudah keras dari sananya: biarkan di sekitar -6 dB dan jangan " +
                "dinaikkan sampai meter IN merah. Ujung bawah fader = senyap sungguhan.",
            minIsOff = true
        )

        val g = group(
            page, "Gate & high-pass (anti-feedback)",
            "High-pass memotong dengung dan bunyi pegangan, noise gate menutup saat tidak ada " +
                "suara. Paling berguna untuk mic."
        )
        addToggle(g, "Mode dinamis (high-pass + gate)", AudioEngine.P_MIC_DYN, false)
        addSlider(g, "High-pass", AudioEngine.P_HPF_FREQ, 40f, 300f, 100f, true, "Hz", 0)
        addSlider(
            g, "Gate threshold", AudioEngine.P_GATE_THRESH, -80f, -20f, -55f, false, "dBFS", 0,
            "Atur sedikit di atas desis saat diam. Terlalu tinggi akan memotong ujung kata."
        )
        addSlider(g, "Gate release", AudioEngine.P_GATE_REL, 20f, 1000f, 200f, true, "ms", 0)
        return page
    }

    private fun buildTonePage(): LinearLayout {
        val page = newPage()
        val g = group(
            page, "Tone control",
            "EQ 3 band untuk strip terpilih, dipasang sebelum compressor: Bass (shelf 120 Hz), " +
                "Mid (1 kHz), Treble (shelf 6 kHz). Rentang -12 sampai +12 dB."
        )
        addToggle(g, "Tone aktif", AudioEngine.P_TONE_ON, false)
        addSlider(g, "Bass", AudioEngine.P_BASS, -12f, 12f, 0f, false, "dB", 1)
        addSlider(g, "Mid", AudioEngine.P_MID, -12f, 12f, 0f, false, "dB", 1)
        addSlider(g, "Treble", AudioEngine.P_TREBLE, -12f, 12f, 0f, false, "dB", 1)
        return page
    }

    private fun buildMasterPage(): LinearLayout {
        val page = newPage()
        val g = group(
            page, "Master",
            "Semua strip aktif dijumlahkan di sini, lalu melewati gain master dan limiter " +
                "lookahead (gain sudah turun sebelum puncak tiba, jadi tidak pecah). " +
                "Meter MST di bilah atas menunjukkan level akhir."
        )
        val mute = redToggle()
        bindToggle(mute, AudioEngine.P_M_MUTE, false)
        addToggleRow(g, "Mute master", mute)
        addSlider(
            g, "Master gain", AudioEngine.P_M_GAIN, -60f, 12f, 0f, false, "dB", 1,
            "Ujung bawah fader = senyap sungguhan.", minIsOff = true
        )
        addToggle(g, "Limiter master aktif", AudioEngine.P_M_LIM_ON, true)
        addSlider(g, "Ceiling master", AudioEngine.P_M_CEIL, -12f, 0f, -1f, false, "dBFS", 1)
        val pre = group(page, "Pre-master EQ · sebelum gain master dan limiter",
            "EQ ini masuk ke jalur internal dan ikut terekam.")
        addToggle(pre, "Pre-master EQ aktif", AudioEngine.P_PRE_EQ_ON, false)
        addSlider(pre, "Bass", AudioEngine.P_PRE_EQ_BASS, -12f, 12f, 0f, false, "dB", 1)
        addSlider(pre, "Mid", AudioEngine.P_PRE_EQ_MID, -12f, 12f, 0f, false, "dB", 1)
        addSlider(pre, "Treble", AudioEngine.P_PRE_EQ_TREBLE, -12f, 12f, 0f, false, "dB", 1)
        val post = group(page, "Output EQ · sesudah limiter",
            "Hanya untuk suara yang didengar. Tidak mengubah meter internal atau file rekaman.")
        addToggle(post, "Output EQ aktif", AudioEngine.P_POST_EQ_ON, false)
        addSlider(post, "Bass", AudioEngine.P_POST_EQ_BASS, -12f, 12f, 0f, false, "dB", 1)
        addSlider(post, "Mid", AudioEngine.P_POST_EQ_MID, -12f, 12f, 0f, false, "dB", 1)
        addSlider(post, "Treble", AudioEngine.P_POST_EQ_TREBLE, -12f, 12f, 0f, false, "dB", 1)
        return page
    }

    private fun buildCompPage(): LinearLayout {
        val page = newPage()
        val g1 = group(
            page, "Compressor",
            "Tombol BYP di bilah atas mematikan semua efek sekaligus: high-pass, gate, " +
                "compressor, reverb, dan limiter."
        )
        addSlider(g1, "Threshold", AudioEngine.P_THRESH, -60f, 0f, -20f, false, "dB", 1)
        addSlider(g1, "Ratio", AudioEngine.P_RATIO, 1f, 20f, 4f, false, ": 1", 1)
        addSlider(g1, "Attack", AudioEngine.P_ATTACK, 0.1f, 100f, 10f, true, "ms", 1)
        addSlider(g1, "Release", AudioEngine.P_RELEASE, 10f, 2000f, 200f, true, "ms", 0)
        addSlider(g1, "Knee (0 = hard)", AudioEngine.P_KNEE, 0f, 24f, 6f, false, "dB", 1)
        addSlider(g1, "Makeup gain", AudioEngine.P_MAKEUP, 0f, 24f, 0f, false, "dB", 1)

        val g2 = group(page, "Limiter")
        addToggle(g2, "Limiter aktif", AudioEngine.P_LIM_ON, true)
        addSlider(g2, "Ceiling", AudioEngine.P_CEIL, -12f, 0f, -1f, false, "dBFS", 1)
        addSlider(
            g2, "Fader keluaran strip", AudioEngine.P_OUT_GAIN, -60f, 12f, 0f, false, "dB", 1,
            "Level strip ini sebelum masuk Master. Ujung bawah fader = senyap sungguhan.",
            minIsOff = true
        )
        return page
    }

    private fun buildReverbPage(): LinearLayout {
        val page = newPage()
        // Reverb: di antara compressor dan limiter, jadi ekornya tidak melewati ceiling.
        val g1 = group(
            page, "Reverb",
            "Reverb berada di antara compressor dan limiter, jadi ekornya tidak melewati ceiling."
        )
        addToggle(g1, "Reverb aktif", AudioEngine.P_REV_ON, false)
        addSlider(g1, "Mix", AudioEngine.P_REV_MIX, 0f, 100f, 20f, false, "%", 0)
        addSlider(
            g1, "Ukuran ruang / decay", AudioEngine.P_REV_SIZE, 0f, 100f, 60f, false, "%", 0
        )
        addSlider(
            g1, "Damping (meredam treble ekor)", AudioEngine.P_REV_DAMP, 0f, 100f, 50f, false,
            "%", 0
        )
        addSlider(g1, "Pre-delay", AudioEngine.P_REV_PRE, 0f, 100f, 15f, false, "ms", 0)
        addSlider(g1, "Lebar stereo", AudioEngine.P_REV_WIDTH, 0f, 100f, 100f, false, "%", 0)
        addSlider(
            g1, "Low-cut reverb (kurangi bass)", AudioEngine.P_REV_LOWCUT, 20f, 600f, 200f, true,
            "Hz", 0
        )

        val g2 = group(page, "Preset reverb")
        // Urutan nilai: mix, ukuran, damping, pre-delay, lebar, low-cut
        val revIds = intArrayOf(
            AudioEngine.P_REV_MIX, AudioEngine.P_REV_SIZE, AudioEngine.P_REV_DAMP,
            AudioEngine.P_REV_PRE, AudioEngine.P_REV_WIDTH, AudioEngine.P_REV_LOWCUT
        )
        val revPresets = listOf(
            "Ruang" to floatArrayOf(15f, 35f, 60f, 5f, 80f, 150f),
            "Hall" to floatArrayOf(20f, 85f, 40f, 25f, 100f, 200f),
            "Plate" to floatArrayOf(20f, 65f, 20f, 10f, 100f, 250f),
            "Vokal" to floatArrayOf(12f, 55f, 50f, 20f, 90f, 300f)
        )
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        for (k in revPresets.indices) {
            val v = revPresets[k].second
            val b = smallButton(revPresets[k].first, C_BTN)
            b.setOnClickListener {
                for (i in revIds.indices) setters[revIds[i]]?.invoke(v[i])
            }
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
            if (k > 0) lp.leftMargin = dp(6)
            row.addView(b, lp)
        }
        row.setPadding(0, dp(6), 0, 0)
        g2.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        return page
    }

    private fun buildPresetPage(): LinearLayout {
        val page = newPage()
        val g = group(
            page, "Preset",
            "Preset disimpan sebagai file JSON di Documents/DbxComp/Preset, berisi nama parameter, " +
                "versi aplikasi, dan model HP. Setiap rekaman juga dibuatkan file info di " +
                "Documents/DbxComp/Rekaman (setelan, perangkat, dan statistik level) dengan " +
                "nama yang sama dengan file WAV-nya.\n\n" +
                "Netral = semua efek mati dan semua gain 0 dB, dipakai sebagai pembanding.\n" +
                "Bagikan = kirim file preset lewat aplikasi lain. Impor = ambil file preset dari " +
                "penyimpanan. Pengaturan terakhir otomatis kembali saat aplikasi dibuka " +
                "(kecuali Bypass, kanal, dan monitor)."
        )
        presetSpinner = Spinner(this)
        styleSpinner(presetSpinner)
        g.addView(presetSpinner)
        buttonRow(
            g,
            "Muat" to { onLoadClicked() },
            "Simpan" to { onSaveClicked() },
            "Hapus" to { onDeleteClicked() }
        )
        buttonRow(
            g,
            "Bagikan" to { onShareClicked() },
            "Impor" to { onImportClicked() }
        )
        presetDetails = TextView(this)
        presetDetails.setTextColor(C_TEXT)
        presetDetails.textSize = 12f
        presetDetails.setPadding(dp(8), dp(14), dp(8), dp(10))
        presetDetails.setBackgroundColor(C_STRIP)
        g.addView(presetDetails, LinearLayout.LayoutParams(MATCH, WRAP))
        refreshPresetDetails()
        return page
    }

    // ---------- Pembangun komponen ----------

    private fun smallButton(caption: String, fill: Int): Button {
        val b = Button(this)
        b.text = caption
        b.transformationMethod = null  // jangan huruf besar semua
        b.textSize = 12f
        b.setTextColor(Color.WHITE)
        b.minWidth = 0
        b.minHeight = 0
        b.minimumWidth = 0
        b.minimumHeight = 0
        b.setPadding(dp(4), dp(4), dp(4), dp(4))
        val bg = GradientDrawable()
        bg.cornerRadius = dp(8).toFloat()
        bg.setColor(fill)
        bg.setStroke(dp(1), Color.rgb(70, 82, 94))
        b.background = bg
        return b
    }

    private fun buttonRow(parent: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.setPadding(0, dp(6), 0, 0)
        for (idx in items.indices) {
            val action = items[idx].second
            val b = smallButton(items[idx].first, C_BTN)
            b.setOnClickListener { action() }
            val lp = LinearLayout.LayoutParams(0, dp(40), 1f)
            if (idx > 0) lp.leftMargin = dp(6)
            row.addView(b, lp)
        }
        parent.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    private fun infoText(s: String): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = 12f
        t.setTextColor(C_TEXT)
        t.setPadding(0, dp(4), 0, dp(6))
        t.visibility = View.GONE
        return t
    }

    private fun infoButton(target: View): TextView {
        val b = TextView(this)
        b.text = "i"
        b.textSize = 13f
        b.typeface = Typeface.DEFAULT_BOLD
        b.gravity = Gravity.CENTER
        b.setTextColor(Color.rgb(150, 200, 255))
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setStroke(dp(1), Color.rgb(80, 120, 170))
        b.background = bg
        b.setOnClickListener {
            target.visibility = if (target.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        return b
    }

    /** Kotak berbingkai untuk satu kelompok kontrol, dengan judul dan tombol info opsional. */
    private fun group(parent: LinearLayout, title: String, info: String? = null): LinearLayout {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.cornerRadius = dp(10).toFloat()
        bg.setColor(Color.rgb(20, 25, 30))
        bg.setStroke(dp(1), Color.rgb(58, 68, 78))
        box.background = bg
        box.setPadding(dp(12), dp(8), dp(12), dp(10))

        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val tv = TextView(this)
        tv.text = title
        tv.textSize = 14f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setTextColor(Color.WHITE)
        head.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
        val infoView: TextView? = if (info != null) infoText(info) else null
        if (infoView != null) {
            head.addView(infoButton(infoView), LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        box.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        if (infoView != null) box.addView(infoView, LinearLayout.LayoutParams(MATCH, WRAP))

        val lp = LinearLayout.LayoutParams(MATCH, WRAP)
        lp.bottomMargin = dp(10)
        parent.addView(box, lp)
        return box
    }

    /** Baris: nama di kiri, tombol ON/OFF di kanan, tombol info opsional. */
    private fun addToggleRow(parent: LinearLayout, name: String, tg: LedToggle, info: String? = null) {
        val cell = LinearLayout(this)
        cell.orientation = LinearLayout.VERTICAL
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(0, dp(6), 0, dp(2))
        val tv = TextView(this)
        tv.text = name
        tv.textSize = 14f
        tv.setTextColor(Color.WHITE)
        row.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
        val infoView: TextView? = if (info != null) infoText(info) else null
        if (infoView != null) {
            val lpI = LinearLayout.LayoutParams(dp(28), dp(28))
            lpI.rightMargin = dp(8)
            row.addView(infoButton(infoView), lpI)
        }
        row.addView(tg, LinearLayout.LayoutParams(dp(64), dp(34)))
        cell.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        if (infoView != null) cell.addView(infoView, LinearLayout.LayoutParams(MATCH, WRAP))

        val lp: LinearLayout.LayoutParams
        if (parent.orientation == LinearLayout.HORIZONTAL) {
            lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.rightMargin = dp(8)
        } else {
            lp = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        parent.addView(cell, lp)
    }

    /** Hubungkan tombol ON/OFF ke satu parameter mesin audio. */
    private fun bindToggle(tg: LedToggle, id: Int, def: Boolean) {
        val start = if (values[id].isNaN()) (if (def) 1f else 0f) else values[id]
        fun push(on: Boolean) {
            values[id] = if (on) 1f else 0f
            AudioEngine.setParam(curStrip, id, values[id])
            refreshPresetDetails()
        }
        tg.checked = start > 0.5f
        push(tg.checked)
        tg.onChange = { on -> push(on) }
        setters[id] = { v ->
            tg.checked = v > 0.5f
            push(v > 0.5f)
        }
    }

    private fun addToggle(
        parent: LinearLayout, name: String, id: Int, def: Boolean, info: String? = null
    ) {
        val tg = LedToggle(this)
        bindToggle(tg, id, def)
        addToggleRow(parent, name, tg, info)
    }

    private fun addSlider(
        parent: LinearLayout, name: String, id: Int,
        lo: Float, hi: Float, def: Float, log: Boolean, unit: String, decimals: Int,
        info: String? = null, minIsOff: Boolean = false
    ) {
        val tv = TextView(this)
        tv.setTextColor(Color.WHITE)
        tv.textSize = 14f
        val head = LinearLayout(this)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        head.setPadding(0, dp(8), 0, 0)
        head.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
        val infoView: TextView? = if (info != null) infoText(info) else null
        if (infoView != null) {
            head.addView(infoButton(infoView), LinearLayout.LayoutParams(dp(28), dp(28)))
        }
        val bar = SeekBar(this)
        bar.max = 1000

        fun toValue(p: Int): Float =
            if (minIsOff && p == 0) -90f  // ujung bawah fader = senyap sungguhan
            else if (log) (lo * (hi / lo).toDouble().pow(p / 1000.0)).toFloat()
            else lo + (hi - lo) * p / 1000f

        fun toProgress(v: Float): Int {
            if (minIsOff && v <= -89f) return 0
            val p = if (log) 1000.0 * ln((v / lo).toDouble()) / ln((hi / lo).toDouble())
            else ((v - lo) / (hi - lo) * 1000f).toDouble()
            return p.toInt().coerceIn(0, 1000)
        }

        fun applyValue(v: Float) {
            values[id] = v
            tv.text = if (minIsOff && v <= -89f) "$name: senyap (−∞)"
            else "$name: ${String.format(Locale.US, "%.${decimals}f", v)} $unit"
            AudioEngine.setParam(curStrip, id, v)
            refreshPresetDetails()
        }

        val start = if (values[id].isNaN()) def else values[id]
        bar.progress = toProgress(start)
        applyValue(start)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                applyValue(toValue(progress))
            }

            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {}
        })
        setters[id] = { v ->
            bar.progress = toProgress(v)
            applyValue(v)  // nilai persis, menimpa pembulatan dari slider
        }
        parent.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        if (infoView != null) parent.addView(infoView, LinearLayout.LayoutParams(MATCH, WRAP))
        parent.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (running && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) {
            val step = if (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP) 2f else -2f
            monitorVolumeDb = (monitorVolumeDb + step).coerceIn(-60f, 0f)
            AudioEngine.setParam(0, AudioEngine.P_MONITOR_VOLUME, monitorVolumeDb)
            if (::presetDetails.isInitialized) refreshPresetDetails()
            Toast.makeText(this, "Level monitor luar: ${String.format(Locale.US, "%.0f", monitorVolumeDb)} dB", Toast.LENGTH_SHORT).show()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        if (running && (keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onStop() {
        super.onStop()
        // Simpan pengaturan terakhir tiap strip (master ikut tersimpan lewat strip A).
        saveCurrentToStrip()
        for (id in AudioEngine.P_STRIP until AudioEngine.P_COUNT) {
            if (!values[id].isNaN()) stripValues[0][id] = values[id]
        }
        val ed = prefs.edit()
        for (st in 0 until AudioEngine.STRIPS) {
            ed.putString(
                "last_s$st",
                stripValues[st].joinToString(",") { String.format(Locale.US, "%.4f", it) }
            )
        }
        ed.putFloat("monitor_volume_db", monitorVolumeDb)
        ed.apply()
        // Mic/line-in dan suara internal sama-sama berjalan lewat layanan foreground
        // (MicService / CaptureService), jadi audio boleh lanjut di latar belakang.
    }

    override fun onDestroy() {
        if (running && isFinishing) stopAudio()
        super.onDestroy()
    }

    // ---------- Audio ----------

    private fun applySourceUi() {
        val micOn = (srcMask and AudioEngine.SRC_MIC) != 0
        for (t in srcToggles) t.isEnabled = !running
        inSpinner.isEnabled = micOn && !running
        stereoToggle.isEnabled = micOn && !running
    }

    private fun micPermNeeded() = (srcMask and (AudioEngine.SRC_MIC or AudioEngine.SRC_INT)) != 0

    private fun requestAndStart() {
        if (srcMask == 0) {
            Toast.makeText(this, "Nyalakan minimal satu sumber di tab Sumber", Toast.LENGTH_LONG).show()
            return
        }
        if ((srcMask and AudioEngine.SRC_INT) != 0 && Build.VERSION.SDK_INT < 29) {
            Toast.makeText(this, "Suara internal butuh Android 10 ke atas", Toast.LENGTH_LONG).show()
            return
        }
        val need = ArrayList<String>()
        // Mic dan penangkap suara internal (AudioPlaybackCapture) butuh izin mikrofon; mode ROOT tidak.
        if (micPermNeeded() &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.RECORD_AUDIO)
        }
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)  // opsional: untuk notifikasi layanan
        }
        if (need.isEmpty()) startAudio() else requestPermissions(need.toTypedArray(), REQ_PERM)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERM) return
        if (!micPermNeeded() ||
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        ) {
            startAudio()
        } else {
            Toast.makeText(this, "Izin mikrofon diperlukan", Toast.LENGTH_LONG).show()
        }
    }

    private fun startAudio() {
        if ((srcMask and AudioEngine.SRC_INT) != 0) {
            // Sumber B butuh izin tangkap suara (MediaProjection); mesin dimulai di onActivityResult.
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE)
            return
        }
        startEngine(0, null)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_IMPORT) {
            handleImport(resultCode, data)
            return
        }
        if (requestCode != REQ_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, "Izin tangkap suara internal ditolak", Toast.LENGTH_LONG).show()
            return
        }
        startEngine(resultCode, data)
    }

    /** Output untuk sumber ROOT: harus perangkat tertentu agar tidak ikut dialihkan ke submix. */
    private fun pickRootOutputId(): Int {
        val chosen = outDevices.getOrNull(outSpinner.selectedItemPosition)?.id ?: 0
        if (chosen != 0) return chosen
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val prefer = intArrayOf(
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
        )
        for (t in prefer) outs.firstOrNull { it.type == t }?.let { return it.id }
        return 0
    }

    private fun stopServices(mask: Int) {
        if ((mask and AudioEngine.SRC_MIC) != 0) stopService(Intent(this, MicService::class.java))
        if ((mask and AudioEngine.SRC_INT) != 0) stopService(Intent(this, CaptureService::class.java))
        if ((mask and AudioEngine.SRC_ROOT) != 0) stopService(Intent(this, RootCaptureService::class.java))
    }

    /** Mulai layanan latar untuk tiap sumber terpilih, lalu mesin audio dengan semua strip sekaligus. */
    private fun startEngine(projCode: Int, projData: Intent?) {
        val mask = srcMask
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val rate = am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 48000
        val inId = inDevices.getOrNull(inSpinner.selectedItemPosition)?.id ?: 0
        val outId = if ((mask and AudioEngine.SRC_ROOT) != 0) pickRootOutputId()
        else outDevices.getOrNull(outSpinner.selectedItemPosition)?.id ?: 0

        // Pesan dari layanan tangkap. Kalau hanya satu sumber aktif dan sumber itu gagal, berhenti;
        // kalau ada sumber lain yang masih jalan, cukup beri tahu.
        CaptureService.listener = { msg ->
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            if (running && (activeMask and (activeMask - 1)) == 0) stopAudio()
        }

        try {
            if ((mask and AudioEngine.SRC_MIC) != 0) {
                // Layanan foreground bertipe microphone: syarat Android agar mic tetap aktif di latar.
                val micSvc = Intent(this, MicService::class.java)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(micSvc) else startService(micSvc)
            }
            if ((mask and AudioEngine.SRC_INT) != 0) {
                val svc = Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODE, projCode)
                    .putExtra(CaptureService.EXTRA_DATA, projData)
                    .putExtra(CaptureService.EXTRA_RATE, rate)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            }
            if ((mask and AudioEngine.SRC_ROOT) != 0) {
                val svc = Intent(this, RootCaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_RATE, rate)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(svc) else startService(svc)
            }
        } catch (e: Exception) {
            Log.w("DbxComp", "Layanan latar gagal dimulai", e)
        }

        if (AudioEngine.nativeStart(inId, outId, stereoToggle.checked, mask, rate)) {
            onStarted(mask)
        } else {
            stopServices(mask)
            CaptureService.listener = null
            Toast.makeText(this, "Gagal membuka perangkat audio", Toast.LENGTH_LONG).show()
        }
    }

    private fun onStarted(mask: Int) {
        running = true
        activeMask = mask
        startButton.text = "STOP"
        val micOn = (mask and AudioEngine.SRC_MIC) != 0
        val ch = if (micOn) AudioEngine.nativeGetInputChannels() else 2
        // Rekam A/B hanya bila mic mono adalah satu-satunya sumber.
        abToggle.isEnabled = mask == AudioEngine.SRC_MIC && ch < 2
        val parts = ArrayList<String>()
        if (micOn) {
            parts.add(
                when {
                    ch >= 2 -> "A mic stereo asli"
                    stereoToggle.checked -> "A mic mono (perangkat tidak memberi 2 channel)"
                    else -> "A mic mono"
                }
            )
        }
        if ((mask and AudioEngine.SRC_INT) != 0) parts.add("B internal HP")
        if ((mask and AudioEngine.SRC_ROOT) != 0) parts.add("C internal ROOT")
        channelText.text = "Input aktif: " + parts.joinToString(" + ")
        applySourceUi()
        handler.post(ticker)
    }

    private fun stopAudio() {
        recorder.stop()  // rekaman diselesaikan dulu di thread latar
        AudioEngine.nativeStop()
        stopServices(activeMask)
        CaptureService.listener = null
        running = false
        activeMask = 0
        startButton.text = "START"
        abToggle.isEnabled = true
        channelText.text = INPUT_IDLE
        applySourceUi()
    }

    private fun activeSourceText(): String {
        val p = ArrayList<String>()
        if ((activeMask and AudioEngine.SRC_MIC) != 0) p.add("mikrofon / line-in / USB")
        if ((activeMask and AudioEngine.SRC_INT) != 0) p.add("suara internal HP")
        if ((activeMask and AudioEngine.SRC_ROOT) != 0) p.add("suara internal ROOT")
        return p.joinToString(" + ")
    }

    // ---------- Rekam ----------

    private fun toggleRecord() {
        if (recorder.isRecording) {
            recorder.stop()
            recordButton.text = "Simpan…"
            return
        }
        if (!running) {
            Toast.makeText(this, "Tekan START dulu, baru REKAM", Toast.LENGTH_SHORT).show()
            return
        }
        val rate = AudioEngine.nativeGetSampleRate()
        // Selalu WAV stereo: normal = (L, R) hasil; A/B (input mono) = (asli, hasil).
        AudioEngine.nativeSetRecordAB(abToggle.checked && abToggle.isEnabled)
        if (recorder.start(rate, true)) {
            recordButton.text = "■ 00:00"
        } else {
            Toast.makeText(this, "Gagal membuat file rekaman", Toast.LENGTH_LONG).show()
        }
    }

    private fun onRecordingDone(location: String?) {
        recordButton.text = "● REK"
        if (location != null) {
            val info = recorder.lastInfoLocation
            statusText.text = "Tersimpan: $location" + (if (info != null) "\nInfo: $info" else "")
            Toast.makeText(this, "Rekaman tersimpan", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Rekaman gagal disimpan", Toast.LENGTH_LONG).show()
        }
    }

    /** Potret setelan dan perangkat saat rekaman dimulai, untuk file info pendamping. */
    private fun recordingSnapshot(): JSONObject {
        val o = JSONObject()
        val app = JSONObject()
        app.put("nama", "DbxComp")
        app.put("versi", PresetStore.appVersion(this))
        o.put("app", app)
        o.put("device", PresetStore.deviceJson())

        val ch = AudioEngine.nativeGetInputChannels()
        val ab = abToggle.checked && abToggle.isEnabled
        val audio = JSONObject()
        audio.put("sumber", activeSourceText())
        audio.put("sampleRate", AudioEngine.nativeGetSampleRate())
        audio.put("inputChannel", ch)
        audio.put(
            "inputPerangkat",
            if ((activeMask and AudioEngine.SRC_MIC) != 0)
                describe(inDevices.getOrNull(inSpinner.selectedItemPosition))
            else "-"
        )
        audio.put("outputPerangkat", describe(outDevices.getOrNull(outSpinner.selectedItemPosition)))
        audio.put("rekamAB", ab)
        audio.put(
            "isiWav",
            if (ab) "kiri = suara asli (setelah input gain), kanan = hasil olahan"
            else if (activeMask != AudioEngine.SRC_MIC) "kiri dan kanan = hasil Master (jumlah semua strip aktif)"
            else if (ch >= 2) "kiri dan kanan = hasil olahan stereo"
            else "mono: kiri dan kanan = hasil olahan"
        )
        o.put("audio", audio)
        o.put("params", PresetStore.paramsJson(values))
        // Setelan tiap strip dan master saat rekaman dimulai.
        val strips = JSONObject()
        val names = listOf("A_mic", "B_internal", "C_root")
        for (st in 0 until AudioEngine.STRIPS) {
            strips.put(names[st], PresetStore.paramsJson(if (st == curStrip) values else stripValues[st]))
        }
        o.put("strips", strips)
        val master = JSONObject()
        fun putM(key: String, id: Int) {
            val v = values[id]
            if (!v.isNaN()) master.put(key, v.toDouble())
        }
        putM("gain_db", AudioEngine.P_M_GAIN)
        putM("mute", AudioEngine.P_M_MUTE)
        putM("limiter_on", AudioEngine.P_M_LIM_ON)
        putM("ceiling_dbfs", AudioEngine.P_M_CEIL)
        o.put("master", master)
        return o
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.US, "%02d:%02d", s / 60, s % 60)
    }

    // ---------- Preset ----------

    private fun refreshPresetDetails() {
        if (!::presetDetails.isInitialized) return
        val names = arrayOf("Threshold", "Ratio", "Attack", "Release", "Knee", "Makeup", "Ceiling", "Limiter", "Bypass", "Input gain", "Fader", "Link L/R", "Kanal L", "Kanal R", "Input internal", "Monitor", "Mic dynamic", "High-pass", "Gate threshold", "Gate release", "Reverb", "Reverb mix", "Reverb size", "Damping", "Pre-delay", "Width", "Low-cut", "Mute", "Tone", "Bass", "Mid", "Treble")
        val b = StringBuilder("SETELAN AKTIF · STRIP ${stripNames[curStrip]}\n")
        for (id in 0 until AudioEngine.P_STRIP) {
            val v = values[id]
            if (!v.isNaN()) {
                val toggleIds = setOf(AudioEngine.P_LIM_ON, AudioEngine.P_BYPASS,
                    AudioEngine.P_LINK, AudioEngine.P_GATE_L, AudioEngine.P_GATE_R,
                    AudioEngine.P_MONITOR, AudioEngine.P_MIC_DYN, AudioEngine.P_REV_ON,
                    AudioEngine.P_MUTE, AudioEngine.P_TONE_ON)
                val shown = if (id in toggleIds) onOff(v) else String.format(Locale.US, "%.2f", v)
                b.append(names[id]).append(": ").append(shown).append("\n")
            }
        }
        b.append("\nMASTER / GLOBAL\n")
        val globalNames = arrayOf("Master gain", "Master mute", "Master limiter", "Master ceiling", "Pre-EQ", "Pre bass", "Pre mid", "Pre treble", "Post-EQ", "Post bass", "Post mid", "Post treble", "Volume monitor")
        for (id in AudioEngine.P_STRIP until AudioEngine.P_COUNT) {
            val v = if (id == AudioEngine.P_MONITOR_VOLUME) monitorVolumeDb else values[id]
            if (!v.isNaN()) {
                val globalToggleIds = setOf(AudioEngine.P_M_MUTE, AudioEngine.P_M_LIM_ON,
                    AudioEngine.P_PRE_EQ_ON, AudioEngine.P_POST_EQ_ON)
                val shown = if (id in globalToggleIds) onOff(v) else String.format(Locale.US, "%.2f", v)
                b.append(globalNames.getOrElse(id - AudioEngine.P_STRIP) { "Global $id" })
                    .append(": ").append(shown).append("\n")
            }
        }
        b.append("\nTombol: ")
        b.append("Limiter=${onOff(values[AudioEngine.P_M_LIM_ON])}, ")
        b.append("Mute=${onOff(values[AudioEngine.P_M_MUTE])}, ")
        b.append("Pre-EQ=${onOff(values[AudioEngine.P_PRE_EQ_ON])}, ")
        b.append("Post-EQ=${onOff(values[AudioEngine.P_POST_EQ_ON])}")
        presetDetails.text = b.toString()
    }

    private fun onOff(v: Float): String = if (!v.isNaN() && v > 0.5f) "ON" else "OFF"

    private fun parseValues(csv: String?): FloatArray? {
        if (csv == null) return null
        val parts = csv.split(",")
        // Toleran terhadap versi lama (parameter lebih sedikit): sisanya tetap default.
        if (parts.size > values.size) return null
        val out = FloatArray(values.size) { Float.NaN }
        for (i in parts.indices) {
            out[i] = parts[i].toFloatOrNull() ?: return null
        }
        return out
    }

    private fun formatValues(): String =
        values.joinToString(",") { String.format(Locale.US, "%.4f", it) }

    /** Pindahkan preset lama (tersimpan di dalam aplikasi) ke file, sekali saja. */
    private fun migrateOldPresets() {
        if (prefs.getBoolean("presets_migrated", false)) return
        val existing = DocStore.list(this, PresetStore.SUB)
        var ok = true
        for (key in prefs.all.keys.filter { it.startsWith("p_") }) {
            val name = key.removePrefix("p_")
            val vals = parseValues(prefs.getString(key, null)) ?: continue
            val fileName = PresetStore.fileNameFor(name)
            if (fileName in existing) continue
            val json = PresetStore.toJson(this, name, "dipindahkan dari penyimpanan lama", vals)
            if (DocStore.write(this, PresetStore.SUB, fileName, json.toString(2)) == null) ok = false
        }
        if (ok) prefs.edit().putBoolean("presets_migrated", true).apply()
    }

    private fun refreshPresets(select: String? = null) {
        presetFiles = DocStore.list(this, PresetStore.SUB).filter { it != PresetStore.NETRAL_FILE }
        val shown = listOf(PresetStore.NETRAL) + presetFiles.map { PresetStore.nameFromFile(it) }
        presetSpinner.adapter = spinnerAdapter(shown)
        if (select != null) presetSpinner.setSelection(shown.indexOf(select).coerceAtLeast(0))
    }

    /** Nama file preset terpilih, atau null untuk Netral bawaan. */
    private fun selectedFile(): String? =
        presetFiles.getOrNull(presetSpinner.selectedItemPosition - 1)

    private fun applyPreset(vals: FloatArray, allStrips: Array<FloatArray>? = null) {
        if (allStrips != null) {
            for (st in 0 until AudioEngine.STRIPS) {
                for (id in 0 until AudioEngine.P_STRIP) {
                    if (AudioEngine.isGlobal(id) || id == AudioEngine.P_GATE_L || id == AudioEngine.P_GATE_R) continue
                    val v = allStrips.getOrNull(st)?.getOrNull(id) ?: Float.NaN
                    if (!v.isNaN()) {
                        stripValues[st][id] = v
                        AudioEngine.setParam(st, id, v)
                    }
                }
            }
        }
        for (id in vals.indices) {
            if (id == AudioEngine.P_BYPASS || id == AudioEngine.P_GATE_L ||
                id == AudioEngine.P_GATE_R || id == AudioEngine.P_MONITOR || vals[id].isNaN()
            ) continue
            setters[id]?.invoke(vals[id])
        }
        if (allStrips == null) saveCurrentToStrip()
        else {
            for (id in 0 until AudioEngine.P_STRIP) {
                val v = stripValues[curStrip][id]
                if (!v.isNaN()) setters[id]?.invoke(v)
            }
        }
        if (vals.size > AudioEngine.P_MONITOR_VOLUME && !vals[AudioEngine.P_MONITOR_VOLUME].isNaN()) {
            monitorVolumeDb = vals[AudioEngine.P_MONITOR_VOLUME].coerceIn(-60f, 0f)
            AudioEngine.setParam(0, AudioEngine.P_MONITOR_VOLUME, monitorVolumeDb)
        }
        updateStripButtons()
        refreshPresetDetails()
    }

    private fun onLoadClicked() {
        val file = selectedFile()
        if (file == null) {
            val neutralStrips = Array(AudioEngine.STRIPS) { PresetStore.NEUTRAL.copyOf() }
            applyPreset(PresetStore.NEUTRAL, neutralStrips)
            Toast.makeText(this, "Preset Netral global dimuat", Toast.LENGTH_SHORT).show()
            return
        }
        val text = DocStore.read(this, PresetStore.SUB, file)
        val parsed = if (text != null) PresetStore.parse(text) else null
        if (parsed == null) {
            Toast.makeText(this, "Preset rusak atau tidak cocok", Toast.LENGTH_SHORT).show()
            return
        }
        applyPreset(parsed.values, parsed.strips)
        Toast.makeText(this, "Preset dimuat: ${PresetStore.nameFromFile(file)}", Toast.LENGTH_SHORT).show()
    }

    private fun onSaveClicked() {
        val current = selectedFile()?.let { PresetStore.nameFromFile(it) }
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.setPadding(dp(20), dp(8), dp(20), 0)
        val nameIn = EditText(this)
        nameIn.inputType = InputType.TYPE_CLASS_TEXT
        nameIn.hint = "Nama preset"
        nameIn.setText(current ?: "Preset 1")
        val noteIn = EditText(this)
        noteIn.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        noteIn.hint = "Catatan (opsional): mic, jarak, tujuan"
        box.addView(nameIn)
        box.addView(noteIn)
        AlertDialog.Builder(this)
            .setTitle("Simpan preset")
            .setView(box)
            .setPositiveButton("Simpan") { _, _ ->
                val name = nameIn.text.toString().trim()
                if (name.isEmpty()) {
                    Toast.makeText(this, "Nama tidak boleh kosong", Toast.LENGTH_SHORT).show()
                } else if (name == PresetStore.NETRAL || name == "Netral") {
                    Toast.makeText(this, "Nama itu dipakai preset bawaan", Toast.LENGTH_SHORT).show()
                } else {
                    saveCurrentToStrip()
                    val globalValues = values.copyOf()
                    globalValues[AudioEngine.P_MONITOR_VOLUME] = monitorVolumeDb
                    val allStrips = Array(AudioEngine.STRIPS) { stripValues[it].copyOf() }
                    val json = PresetStore.toJson(this, name, noteIn.text.toString().trim(), globalValues, allStrips)
                    val fileName = PresetStore.fileNameFor(name)
                    val loc = DocStore.write(this, PresetStore.SUB, fileName, json.toString(2))
                    if (loc == null) {
                        Toast.makeText(this, "Gagal menyimpan preset", Toast.LENGTH_LONG).show()
                    } else {
                        refreshPresets(PresetStore.nameFromFile(fileName))
                        Toast.makeText(this, "Preset disimpan: $loc", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun onDeleteClicked() {
        val file = selectedFile()
        if (file == null) {
            Toast.makeText(this, "Preset Netral bawaan tidak bisa dihapus", Toast.LENGTH_SHORT).show()
            return
        }
        val name = PresetStore.nameFromFile(file)
        AlertDialog.Builder(this)
            .setTitle("Hapus preset?")
            .setMessage(name)
            .setPositiveButton("Hapus") { _, _ ->
                if (DocStore.delete(this, PresetStore.SUB, file)) {
                    refreshPresets()
                    Toast.makeText(this, "Preset dihapus: $name", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this, "Gagal menghapus preset", Toast.LENGTH_LONG).show()
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun onShareClicked() {
        val file = selectedFile()
        val fileName: String
        val text: String
        if (file == null) {
            // Netral bawaan: buat filenya dulu supaya bisa dibagikan.
            val json = PresetStore.toJson(
                this, "Netral", "Semua efek mati, semua gain 0 dB (pembanding)", PresetStore.NEUTRAL
            )
            text = json.toString(2)
            fileName = PresetStore.NETRAL_FILE
            DocStore.write(this, PresetStore.SUB, fileName, text)
        } else {
            val read = DocStore.read(this, PresetStore.SUB, file)
            if (read == null) {
                Toast.makeText(this, "Preset tidak bisa dibaca", Toast.LENGTH_SHORT).show()
                return
            }
            text = read
            fileName = file
        }
        try {
            DocStore.share(this, PresetStore.SUB, fileName, text)
        } catch (e: Exception) {
            Toast.makeText(this, "Tidak ada aplikasi untuk berbagi", Toast.LENGTH_LONG).show()
        }
    }

    private fun onImportClicked() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT)
        intent.addCategory(Intent.CATEGORY_OPENABLE)
        intent.type = "*/*"
        @Suppress("DEPRECATION")
        startActivityForResult(intent, REQ_IMPORT)
    }

    private fun handleImport(resultCode: Int, data: Intent?) {
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        val text: String? = try {
            val input = contentResolver.openInputStream(uri)
            if (input == null) null else input.use { String(it.readBytes(), Charsets.UTF_8) }
        } catch (e: Exception) {
            null
        }
        val parsed = if (text != null) PresetStore.parse(text) else null
        if (text == null || parsed == null) {
            Toast.makeText(this, "File itu bukan preset DbxComp yang valid", Toast.LENGTH_LONG).show()
            return
        }
        var name = parsed.name.trim()
        if (name.isEmpty()) name = "Preset impor"
        if (name == PresetStore.NETRAL || name == "Netral") name = "Netral impor"
        val fileName = PresetStore.fileNameFor(name)
        // Isi file asli disimpan apa adanya, termasuk catatan dan info perangkat.
        val loc = DocStore.write(this, PresetStore.SUB, fileName, text)
        if (loc == null) {
            Toast.makeText(this, "Gagal menyimpan preset impor", Toast.LENGTH_LONG).show()
        } else {
            refreshPresets(PresetStore.nameFromFile(fileName))
            Toast.makeText(this, "Preset diimpor: $name", Toast.LENGTH_LONG).show()
        }
    }

    // ---------- Strip A / B / C ----------

    private fun buildStripSelector(): LinearLayout {
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.CENTER_VERTICAL
        bar.setBackgroundColor(C_STRIP)
        bar.setPadding(dp(6), 0, dp(6), dp(6))
        val cap = TextView(this)
        cap.text = "Strip:"
        cap.textSize = 11f
        cap.setTextColor(Color.rgb(140, 150, 160))
        cap.setPadding(dp(4), 0, dp(6), 0)
        bar.addView(cap, LinearLayout.LayoutParams(WRAP, WRAP))
        for (i in 0 until AudioEngine.STRIPS) {
            val t = TextView(this)
            t.textSize = 12f
            t.gravity = Gravity.CENTER
            t.typeface = Typeface.DEFAULT_BOLD
            t.maxLines = 1
            t.setPadding(0, dp(8), 0, dp(8))
            t.setOnClickListener { switchStrip(i) }
            val lp = LinearLayout.LayoutParams(0, WRAP, 1f)
            lp.leftMargin = dp(2)
            lp.rightMargin = dp(2)
            bar.addView(t, lp)
            stripButtons.add(t)
        }
        updateStripButtons()
        return bar
    }

    private fun isMuted(st: Int): Boolean {
        val v = if (st == curStrip) values[AudioEngine.P_MUTE] else stripValues[st][AudioEngine.P_MUTE]
        return !v.isNaN() && v > 0.5f
    }

    /** Tanda tiap tombol strip: terpilih, sumbernya dinyalakan (bingkai hijau), atau di-mute. */
    private fun updateStripButtons() {
        for (i in stripButtons.indices) {
            val t = stripButtons[i]
            val sel = i == curStrip
            val on = (srcMask and (1 shl i)) != 0
            val muted = isMuted(i)
            t.text = stripNames[i] + (if (muted) " · MUTE" else "")
            t.setTextColor(
                when {
                    muted -> Color.rgb(255, 140, 130)
                    sel -> Color.WHITE
                    on -> Color.rgb(200, 210, 220)
                    else -> Color.rgb(110, 120, 130)
                }
            )
            val bg = GradientDrawable()
            bg.cornerRadius = dp(8).toFloat()
            bg.setColor(if (sel) Color.rgb(38, 66, 96) else Color.rgb(24, 30, 36))
            bg.setStroke(
                dp(if (on) 2 else 1),
                if (on) Color.rgb(46, 204, 113) else Color.rgb(50, 60, 70)
            )
            t.background = bg
        }
    }

    /** Salin nilai kerja (strip terpilih) ke penyimpanan strip itu. Parameter global dilewati. */
    private fun saveCurrentToStrip() {
        for (id in 0 until AudioEngine.P_STRIP) {
            if (AudioEngine.isGlobal(id) || values[id].isNaN()) continue
            stripValues[curStrip][id] = values[id]
        }
    }

    private fun switchStrip(n: Int) {
        if (n == curStrip) return
        saveCurrentToStrip()
        curStrip = n
        for (id in 0 until AudioEngine.P_STRIP) {
            if (AudioEngine.isGlobal(id)) continue
            val v = stripValues[n][id]
            if (v.isNaN()) continue
            setters[id]?.invoke(v)  // memperbarui kontrol di layar dan strip native yang sama
        }
        updateStripButtons()
        refreshPresetDetails()
        meterIn.value = -60f
        meterGr.value = 0f
    }

    /** Kirim semua nilai tiap strip dan parameter global ke mesin native (sekali, saat dibuka). */
    private fun pushAll() {
        for (st in 0 until AudioEngine.STRIPS) {
            for (id in 0 until AudioEngine.P_STRIP) {
                if (AudioEngine.isGlobal(id)) continue
                val v = if (st == curStrip) values[id] else stripValues[st][id]
                if (!v.isNaN()) AudioEngine.setParam(st, id, v)
            }
        }
        for (id in 0 until AudioEngine.P_COUNT) {
            if (AudioEngine.isGlobal(id) && !values[id].isNaN()) AudioEngine.setParam(0, id, values[id])
        }
    }

    // ---------- UI helper ----------

    /** Kotak pilihan dengan teks terang di atas latar gelap (teks bawaan sama warna dengan latar). */
    private fun spinnerAdapter(items: List<String>): ArrayAdapter<String> {
        val a = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, items) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.textSize = 14f
                return v
            }

            override fun getDropDownView(position: Int, convertView: View?, parent: ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(Color.WHITE)
                v.setBackgroundColor(Color.rgb(28, 36, 46))
                v.textSize = 14f
                v.setPadding(dp(14), dp(12), dp(14), dp(12))
                return v
            }
        }
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        return a
    }

    private fun styleSpinner(sp: Spinner) {
        val bg = GradientDrawable()
        bg.cornerRadius = dp(8).toFloat()
        bg.setColor(Color.rgb(28, 36, 46))
        bg.setStroke(dp(1), Color.rgb(90, 108, 126))
        sp.background = bg
        sp.setPadding(dp(8), dp(10), dp(8), dp(10))
        sp.setPopupBackgroundDrawable(ColorDrawable(Color.rgb(28, 36, 46)))
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(s: String, size: Float = 13f, color: Int = C_TEXT): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = size
        t.setTextColor(color)
        t.setPadding(0, dp(10), 0, dp(2))
        return t
    }

    private fun deviceList(input: Boolean): List<AudioDeviceInfo?> {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flag = if (input) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        return listOf<AudioDeviceInfo?>(null) + am.getDevices(flag).toList()
    }

    private fun describe(d: AudioDeviceInfo?): String {
        if (d == null) return "Default"
        val kind = when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "mic HP"
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker HP"
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB"
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "kabel"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
            else -> "tipe ${d.type}"
        }
        return "${d.productName} ($kind)"
    }
}
