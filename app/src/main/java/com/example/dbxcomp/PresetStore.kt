package com.example.dbxcomp

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Preset disimpan sebagai file JSON dengan NAMA parameter (bukan urutan angka), jadi tetap
 * terbaca walau nanti ada parameter baru. Isinya juga menyertakan versi aplikasi dan model HP.
 */
object PresetStore {
    const val SUB = "Preset"
    const val SUB_REKAMAN = "Rekaman"
    const val NETRAL = "Netral (bawaan)"
    const val NETRAL_FILE = "Netral.json"
    const val FORMAT = "dbxcomp-preset"

    /** Nama kunci untuk tiap ID parameter, urutannya sama dengan AudioEngine.P_*. */
    val KEYS = arrayOf(
        "threshold_db",        // 0  P_THRESH
        "ratio",               // 1  P_RATIO
        "attack_ms",           // 2  P_ATTACK
        "release_ms",          // 3  P_RELEASE
        "knee_db",             // 4  P_KNEE
        "makeup_db",           // 5  P_MAKEUP
        "ceiling_dbfs",        // 6  P_CEIL
        "limiter_on",          // 7  P_LIM_ON
        "bypass",              // 8  P_BYPASS
        "input_gain_mic_db",   // 9  P_IN_GAIN
        "output_gain_db",      // 10 P_OUT_GAIN
        "link_lr",             // 11 P_LINK
        "kanal_l_aktif",       // 12 P_GATE_L
        "kanal_r_aktif",       // 13 P_GATE_R
        "input_gain_internal_db", // 14 P_IN_GAIN_INT
        "monitor_output",      // 15 P_MONITOR
        "mic_dinamis_on",      // 16 P_MIC_DYN
        "highpass_hz",         // 17 P_HPF_FREQ
        "gate_threshold_dbfs", // 18 P_GATE_THRESH
        "gate_release_ms",     // 19 P_GATE_REL
        "reverb_on",           // 20 P_REV_ON
        "reverb_mix_pct",      // 21 P_REV_MIX
        "reverb_size_pct",     // 22 P_REV_SIZE
        "reverb_damping_pct",  // 23 P_REV_DAMP
        "reverb_predelay_ms",  // 24 P_REV_PRE
        "reverb_width_pct",    // 25 P_REV_WIDTH
        "reverb_lowcut_hz",    // 26 P_REV_LOWCUT
        "input_mute",          // 27 P_MUTE
        "tone_on",             // 28 P_TONE_ON
        "tone_bass_db",        // 29 P_BASS
        "tone_mid_db",         // 30 P_MID
        "tone_treble_db",      // 31 P_TREBLE
        "master_gain_db",      // 32
        "master_mute",         // 33
        "master_limiter_on",   // 34
        "master_ceiling_dbfs", // 35
        "pre_eq_on",           // 36
        "pre_eq_bass_db",      // 37
        "pre_eq_mid_db",       // 38
        "pre_eq_treble_db",    // 39
        "post_eq_on",          // 40
        "post_eq_bass_db",     // 41
        "post_eq_mid_db",      // 42
        "post_eq_treble_db",   // 43
        "monitor_output_volume_db" // 44
    )

    /**
     * Nilai awal semua ID (sama dengan bawaan mesin native). Strip B dan C mulai dengan
     * input gain -6 dB karena suara internal HP biasanya sudah mendekati 0 dBFS.
     */
    fun defaultsFor(strip: Int): FloatArray {
        val d = FloatArray(AudioEngine.P_COUNT) { 0f }
        d[AudioEngine.P_THRESH] = -20f
        d[AudioEngine.P_RATIO] = 4f
        d[AudioEngine.P_ATTACK] = 10f
        d[AudioEngine.P_RELEASE] = 200f
        d[AudioEngine.P_KNEE] = 6f
        d[AudioEngine.P_CEIL] = -1f
        d[AudioEngine.P_LIM_ON] = 1f
        d[AudioEngine.P_LINK] = 1f
        d[AudioEngine.P_GATE_L] = 1f
        d[AudioEngine.P_GATE_R] = 1f
        d[AudioEngine.P_MONITOR] = 1f
        d[AudioEngine.P_HPF_FREQ] = 100f
        d[AudioEngine.P_GATE_THRESH] = -55f
        d[AudioEngine.P_GATE_REL] = 200f
        d[AudioEngine.P_REV_MIX] = 20f
        d[AudioEngine.P_REV_SIZE] = 60f
        d[AudioEngine.P_REV_DAMP] = 50f
        d[AudioEngine.P_REV_PRE] = 15f
        d[AudioEngine.P_REV_WIDTH] = 100f
        d[AudioEngine.P_REV_LOWCUT] = 200f
        d[AudioEngine.P_IN_GAIN] = if (strip == 0) 0f else -6f
        d[AudioEngine.P_M_LIM_ON] = 1f
        d[AudioEngine.P_M_CEIL] = -1f
        d[AudioEngine.P_PRE_EQ_ON] = 0f
        d[AudioEngine.P_PRE_EQ_BASS] = 0f
        d[AudioEngine.P_PRE_EQ_MID] = 0f
        d[AudioEngine.P_PRE_EQ_TREBLE] = 0f
        d[AudioEngine.P_POST_EQ_ON] = 0f
        d[AudioEngine.P_POST_EQ_BASS] = 0f
        d[AudioEngine.P_POST_EQ_MID] = 0f
        d[AudioEngine.P_POST_EQ_TREBLE] = 0f
        d[AudioEngine.P_MONITOR_VOLUME] = 0f
        return d
    }

    /**
     * Preset Netral: semua efek mati dan semua gain 0 dB. NaN = tidak disentuh
     * (kanal, monitor, bypass, dan link dibiarkan seperti keadaan sekarang).
     */
    val NEUTRAL: FloatArray = FloatArray(AudioEngine.P_COUNT) { Float.NaN }.also {
        it[AudioEngine.P_THRESH] = 0f
        it[AudioEngine.P_RATIO] = 1f
        it[AudioEngine.P_ATTACK] = 10f
        it[AudioEngine.P_RELEASE] = 200f
        it[AudioEngine.P_KNEE] = 0f
        it[AudioEngine.P_MAKEUP] = 0f
        it[AudioEngine.P_CEIL] = 0f
        it[AudioEngine.P_LIM_ON] = 0f
        it[AudioEngine.P_IN_GAIN] = 0f
        it[AudioEngine.P_OUT_GAIN] = 0f
        it[AudioEngine.P_IN_GAIN_INT] = 0f
        it[AudioEngine.P_MIC_DYN] = 0f
        it[AudioEngine.P_HPF_FREQ] = 100f
        it[AudioEngine.P_GATE_THRESH] = -55f
        it[AudioEngine.P_GATE_REL] = 200f
        it[AudioEngine.P_REV_ON] = 0f
        it[AudioEngine.P_REV_MIX] = 0f
        it[AudioEngine.P_REV_SIZE] = 60f
        it[AudioEngine.P_REV_DAMP] = 50f
        it[AudioEngine.P_REV_PRE] = 15f
        it[AudioEngine.P_REV_WIDTH] = 100f
        it[AudioEngine.P_REV_LOWCUT] = 200f
        it[AudioEngine.P_MUTE] = 0f
        it[AudioEngine.P_TONE_ON] = 0f
        it[AudioEngine.P_BASS] = 0f
        it[AudioEngine.P_MID] = 0f
        it[AudioEngine.P_TREBLE] = 0f
        it[AudioEngine.P_M_GAIN] = 0f
        it[AudioEngine.P_M_MUTE] = 0f
        it[AudioEngine.P_M_LIM_ON] = 0f
        it[AudioEngine.P_M_CEIL] = 0f
        it[AudioEngine.P_PRE_EQ_ON] = 0f
        it[AudioEngine.P_PRE_EQ_BASS] = 0f
        it[AudioEngine.P_PRE_EQ_MID] = 0f
        it[AudioEngine.P_PRE_EQ_TREBLE] = 0f
        it[AudioEngine.P_POST_EQ_ON] = 0f
        it[AudioEngine.P_POST_EQ_BASS] = 0f
        it[AudioEngine.P_POST_EQ_MID] = 0f
        it[AudioEngine.P_POST_EQ_TREBLE] = 0f
        it[AudioEngine.P_MONITOR_VOLUME] = 0f
    }

    class Parsed(val name: String, val note: String, val values: FloatArray,
                 val strips: Array<FloatArray>? = null)

    fun fileNameFor(name: String): String {
        val sb = StringBuilder()
        for (ch in name.trim()) {
            val ok = ch.isLetterOrDigit() || ch == ' ' || ch == '-' || ch == '_' ||
                ch == '.' || ch == '(' || ch == ')'
            sb.append(if (ok) ch else '_')
        }
        var s = sb.toString().trim().trimEnd('.')
        if (s.isEmpty()) s = "Preset"
        return "$s.json"
    }

    fun nameFromFile(fileName: String): String = fileName.removeSuffix(".json")

    private fun round4(v: Float): Double = String.format(Locale.US, "%.4f", v).toDouble()

    fun paramsJson(values: FloatArray): JSONObject {
        val p = JSONObject()
        for (i in KEYS.indices) {
            if (i < values.size && !values[i].isNaN()) p.put(KEYS[i], round4(values[i]))
        }
        return p
    }

    @Suppress("DEPRECATION")
    fun appVersion(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (e: Exception) {
            "?"
        }
    }

    fun deviceJson(): JSONObject {
        val d = JSONObject()
        d.put("merek", Build.MANUFACTURER)
        d.put("model", Build.MODEL)
        d.put("android_sdk", Build.VERSION.SDK_INT)
        return d
    }

    fun nowIso(): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(Date())

    fun toJson(context: Context, name: String, note: String, values: FloatArray,
                strips: Array<FloatArray>? = null): JSONObject {
        val o = JSONObject()
        o.put("format", FORMAT)
        o.put("formatVersion", 1)
        o.put("name", name)
        o.put("note", note)
        o.put("createdAt", nowIso())
        val app = JSONObject()
        app.put("nama", "DbxComp")
        app.put("versi", appVersion(context))
        o.put("app", app)
        o.put("device", deviceJson())
        o.put("params", paramsJson(values))
        if (strips != null) {
            val all = JSONObject()
            val names = arrayOf("A_mic", "B_internal", "C_root")
            for (i in strips.indices) all.put(names.getOrElse(i) { "strip_$i" }, paramsJson(strips[i]))
            o.put("strips", all)
            o.put("scope", "global-all-strips-and-master")
        }
        return o
    }

    fun parse(text: String): Parsed? {
        try {
            val o = JSONObject(text)
            if (o.optString("format") != FORMAT) return null
            val p = o.optJSONObject("params") ?: return null
            val v = FloatArray(AudioEngine.P_COUNT) { Float.NaN }
            for (i in KEYS.indices) {
                if (i < v.size && p.has(KEYS[i])) v[i] = p.getDouble(KEYS[i]).toFloat()
            }
            val stripObject = o.optJSONObject("strips")
            val parsedStrips = if (stripObject != null) {
                val names = arrayOf("A_mic", "B_internal", "C_root")
                Array(AudioEngine.STRIPS) { st ->
                    val sp = stripObject.optJSONObject(names[st])
                    FloatArray(AudioEngine.P_COUNT) { idx ->
                        if (idx < AudioEngine.P_STRIP && !AudioEngine.isGlobal(idx) &&
                            idx != AudioEngine.P_GATE_L && idx != AudioEngine.P_GATE_R &&
                            sp != null && sp.has(KEYS[idx]))
                            sp.optDouble(KEYS[idx], Double.NaN).toFloat() else Float.NaN
                    }
                }
            } else null
            return Parsed(o.optString("name"), o.optString("note"), v, parsedStrips)
        } catch (e: Exception) {
            return null
        }
    }
}
