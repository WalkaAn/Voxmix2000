package com.example.dbxcomp

/** Jembatan JNI ke mesin audio native (Oboe). */
object AudioEngine {
    // ID parameter, harus sama dengan enum ParamId di audio_engine.cpp
    const val P_THRESH = 0
    const val P_RATIO = 1
    const val P_ATTACK = 2
    const val P_RELEASE = 3
    const val P_KNEE = 4
    const val P_MAKEUP = 5
    const val P_CEIL = 6
    const val P_LIM_ON = 7
    const val P_BYPASS = 8
    const val P_IN_GAIN = 9
    const val P_OUT_GAIN = 10
    const val P_LINK = 11
    const val P_GATE_L = 12
    const val P_GATE_R = 13
    const val P_IN_GAIN_INT = 14
    const val P_MONITOR = 15
    const val P_MIC_DYN = 16
    const val P_HPF_FREQ = 17
    const val P_GATE_THRESH = 18
    const val P_GATE_REL = 19
    const val P_REV_ON = 20
    const val P_REV_MIX = 21
    const val P_REV_SIZE = 22
    const val P_REV_DAMP = 23
    const val P_REV_PRE = 24
    const val P_REV_WIDTH = 25
    const val P_REV_LOWCUT = 26
    const val P_MUTE = 27
    const val P_TONE_ON = 28
    const val P_BASS = 29
    const val P_MID = 30
    const val P_TREBLE = 31
    const val P_STRIP = 32  // jumlah parameter per strip (A, B, C)

    // Parameter master (global, bukan per strip).
    const val P_M_GAIN = 32
    const val P_M_MUTE = 33
    const val P_M_LIM_ON = 34
    const val P_M_CEIL = 35
    const val P_PRE_EQ_ON = 36
    const val P_PRE_EQ_BASS = 37
    const val P_PRE_EQ_MID = 38
    const val P_PRE_EQ_TREBLE = 39
    const val P_POST_EQ_ON = 40
    const val P_POST_EQ_BASS = 41
    const val P_POST_EQ_MID = 42
    const val P_POST_EQ_TREBLE = 43
    const val P_MONITOR_VOLUME = 44
    const val P_COUNT = 45

    const val STRIPS = 3  // 0 = A mic, 1 = B internal HP, 2 = C internal ROOT
    const val SRC_MIC = 1
    const val SRC_INT = 2
    const val SRC_ROOT = 4

    /** ID yang tidak per strip: bypass, monitor, dan semua parameter master. */
    fun isGlobal(id: Int) = id == P_BYPASS || id == P_MONITOR || id >= P_STRIP

    /** Indeks parameter di mesin native; harus sama dengan enum ParamId/GlobalId di C++. */
    fun nativeIndex(strip: Int, id: Int): Int {
        val g = STRIPS * P_STRIP
        return when (id) {
            P_BYPASS -> g
            P_MONITOR -> g + 1
            P_M_GAIN -> g + 2
            P_M_MUTE -> g + 3
            P_M_LIM_ON -> g + 4
            P_M_CEIL -> g + 5
            P_PRE_EQ_ON -> g + 6
            P_PRE_EQ_BASS -> g + 7
            P_PRE_EQ_MID -> g + 8
            P_PRE_EQ_TREBLE -> g + 9
            P_POST_EQ_ON -> g + 10
            P_POST_EQ_BASS -> g + 11
            P_POST_EQ_MID -> g + 12
            P_POST_EQ_TREBLE -> g + 13
            P_MONITOR_VOLUME -> g + 14
            else -> strip * P_STRIP + id
        }
    }

    fun setParam(strip: Int, id: Int, value: Float) = nativeSetParam(nativeIndex(strip, id), value)

    init {
        System.loadLibrary("dbxcomp")
    }

    /**
     * [stereo] = minta input mic 2 channel; kalau perangkat menolak, otomatis turun ke mono.
     * [sourceMask] = kombinasi SRC_MIC, SRC_INT, SRC_ROOT (boleh lebih dari satu sekaligus);
     * [rate] = laju sampel tangkap internal (output Oboe disamakan).
     */
    external fun nativeStart(
        inputDeviceId: Int, outputDeviceId: Int, stereo: Boolean, sourceMask: Int, rate: Int
    ): Boolean

    /**
     * Dari thread tangkap: [source] 1 = B (MediaProjection), 2 = C (root);
     * [count] float stereo interleaved (L, R, L, R, ...).
     */
    external fun nativePushCapture(source: Int, data: FloatArray, count: Int)

    /** Mode rekam A/B (hanya sumber mono). Panggil sebelum rekaman dimulai. */
    external fun nativeSetRecordAB(ab: Boolean)
    external fun nativeStop()
    external fun nativeSetParam(index: Int, value: Float)

    /**
     * Mengisi [out] (minimal 11 float): untuk tiap strip A, B, C: input peak dB, output peak dB,
     * gain reduction dB (positif); lalu output master peak dB dan gain reduction master.
     */
    external fun nativeGetMeters(out: FloatArray)

    /** Aktif/nonaktifkan penangkapan audio (pasangan asli+hasil) ke ring buffer. */
    external fun nativeSetRecording(on: Boolean)

    /** Mengambil data rekaman terkumpul ke [out]; mengembalikan jumlah float (genap). */
    external fun nativeReadRecorded(out: FloatArray): Int

    external fun nativeGetSampleRate(): Int

    /** Jumlah channel input yang benar-benar diberikan perangkat (1 atau 2). */
    external fun nativeGetInputChannels(): Int
}
