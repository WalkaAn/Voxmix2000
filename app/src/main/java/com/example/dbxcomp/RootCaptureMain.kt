package com.example.dbxcomp

import android.media.AudioFormat
import android.media.AudioRecord
import android.os.Looper
import java.io.FileDescriptor
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.system.exitProcess

/**
 * Helper yang dijalankan sebagai ROOT lewat `su -c app_process ...` (BUKAN di dalam aplikasi).
 * Merekam campuran semua suara keluaran HP (AudioSource.REMOTE_SUBMIX) lalu menulis PCM 16-bit
 * stereo little-endian mentah ke stdout. Aplikasi membaca stdout itu di [RootCaptureService].
 * Pesan error ditulis ke stderr dengan awalan "ERR:".
 */
object RootCaptureMain {
    private const val SOURCE_REMOTE_SUBMIX = 8  // MediaRecorder.AudioSource.REMOTE_SUBMIX (hidden)

    @JvmStatic
    fun main(args: Array<String>) {
        val rate = args.getOrNull(0)?.toIntOrNull() ?: 48000
        try {
            Looper.prepareMainLooper()
        } catch (_: Throwable) {
        }

        // FileOutputStream (bukan System.out) supaya error pipa putus benar-benar dilempar.
        val out = FileOutputStream(FileDescriptor.out)
        val minBytes = AudioRecord.getMinBufferSize(
            rate, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        val bytes = max(minBytes, rate / 10 * 4)  // >= 100 ms

        val rec = try {
            AudioRecord(
                SOURCE_REMOTE_SUBMIX, rate, AudioFormat.CHANNEL_IN_STEREO,
                AudioFormat.ENCODING_PCM_16BIT, bytes
            )
        } catch (e: Throwable) {
            System.err.println("ERR:REMOTE_SUBMIX gagal dibuat: $e")
            exitProcess(2)
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            System.err.println("ERR:REMOTE_SUBMIX tidak siap (ditolak sistem/pabrikan HP)")
            exitProcess(3)
        }
        try {
            rec.startRecording()
        } catch (e: Throwable) {
            System.err.println("ERR:startRecording gagal: $e")
            exitProcess(4)
        }

        val buf = ByteArray(rate / 100 * 4)  // ~10 ms stereo 16-bit
        try {
            while (true) {
                val n = rec.read(buf, 0, buf.size)
                if (n > 0) {
                    out.write(buf, 0, n)  // pipa putus -> IOException -> keluar
                } else if (n < 0) {
                    System.err.println("ERR:AudioRecord.read error $n")
                    break
                }
            }
        } catch (_: Throwable) {
            // aplikasi sudah berhenti membaca: selesai
        } finally {
            try {
                rec.stop()
            } catch (_: Throwable) {
            }
            rec.release()
        }
        exitProcess(0)
    }
}
