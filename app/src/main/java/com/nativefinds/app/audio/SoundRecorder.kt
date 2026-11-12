package com.nativefinds.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/** Records mono 16-bit audio from the microphone. Needs RECORD_AUDIO. */
object SoundRecorder {
    /**
     * Records until [shouldStop] returns true, [maxSeconds] pass, or the coroutine is cancelled.
     * [onProgress] gets (seconds so far, loudness 0..1) a few times a second.
     */
    @SuppressLint("MissingPermission")
    suspend fun record(
        sampleRate: Int,
        maxSeconds: Int,
        shouldStop: () -> Boolean,
        onProgress: (Float, Float) -> Unit,
    ): ShortArray = withContext(Dispatchers.IO) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate / 5 * 2),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            error("Couldn't open the microphone")
        }
        val total = sampleRate * maxSeconds
        val data = ShortArray(total)
        var pos = 0
        val chunk = ShortArray(sampleRate / 10)
        try {
            rec.startRecording()
            while (pos < total && !shouldStop() && coroutineContext.isActive) {
                val n = rec.read(chunk, 0, minOf(chunk.size, total - pos))
                if (n <= 0) continue
                System.arraycopy(chunk, 0, data, pos, n)
                pos += n
                var peak = 0
                for (i in 0 until n) peak = maxOf(peak, abs(chunk[i].toInt()))
                onProgress(pos.toFloat() / sampleRate, peak / 32768f)
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
        data.copyOf(pos)
    }

    fun toFloats(pcm: ShortArray): FloatArray = FloatArray(pcm.size) { pcm[it] / 32768f }

    /** Writes a standard 16-bit mono WAV file. */
    fun writeWav(pcm: ShortArray, sampleRate: Int, file: File) {
        val dataBytes = pcm.size * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            fun le32(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff); out.write(v shr 16 and 0xff); out.write(v shr 24 and 0xff) }
            fun le16(v: Int) { out.write(v and 0xff); out.write(v shr 8 and 0xff) }
            out.writeBytes("RIFF"); le32(36 + dataBytes); out.writeBytes("WAVE")
            out.writeBytes("fmt "); le32(16); le16(1); le16(1); le32(sampleRate); le32(sampleRate * 2); le16(2); le16(16)
            out.writeBytes("data"); le32(dataBytes)
            val buf = ByteArray(dataBytes)
            for (i in pcm.indices) {
                buf[i * 2] = (pcm[i].toInt() and 0xff).toByte()
                buf[i * 2 + 1] = (pcm[i].toInt() shr 8 and 0xff).toByte()
            }
            out.write(buf)
        }
    }
}

/** Finds can hold a sound recording in place of (or as well as) photos. */
fun isAudioPath(path: String): Boolean = path.endsWith(".wav", ignoreCase = true)
