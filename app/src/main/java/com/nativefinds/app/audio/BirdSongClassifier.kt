package com.nativefinds.app.audio

import android.content.Context
import com.nativefinds.app.id.Candidate
import com.nativefinds.app.id.IdSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.exp

/**
 * BirdNET V2.4 on the phone: 3-second windows of 48 kHz mono audio in, a score per species out
 * (about 6,500 species worldwide). Works offline.
 */
class BirdSongClassifier(private val context: Context) {
    data class SongMatch(val scientificName: String, val commonName: String?, val score: Float, val windows: Int)

    private val lock = Mutex()
    private var interpreter: Interpreter? = null
    private var labels: List<Pair<String, String?>> = emptyList()

    fun isAvailable(): Boolean = runCatching { context.assets.openFd(MODEL).close(); true }.getOrDefault(false)

    @Synchronized
    private fun load(): Interpreter {
        interpreter?.let { return it }
        val fd = context.assets.openFd(MODEL)
        val buffer: MappedByteBuffer = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        val interp = Interpreter(buffer, Interpreter.Options().setNumThreads(4))
        labels = context.assets.open(LABELS).bufferedReader().readLines().filter { it.isNotBlank() }.map { line ->
            val i = line.indexOf('_')
            if (i > 0) line.substring(0, i).trim() to line.substring(i + 1).trim().ifBlank { null } else line.trim() to null
        }
        interpreter = interp
        return interp
    }

    /**
     * Scores [samples] (48 kHz mono, -1..1) in 3 s windows overlapping by 1.5 s. Each species gets its
     * best window's score; [SongMatch.windows] counts windows where it scored over [minScore].
     */
    suspend fun classify(samples: FloatArray, minScore: Float = 0.1f, max: Int = 10): List<SongMatch> =
        withContext(Dispatchers.Default) { lock.withLock {
            val interp = load()
            val inShape = interp.getInputTensor(0).shape()
            val windowLen = inShape.last()
            val outCount = interp.getOutputTensor(0).shape().last()
            val step = windowLen / 2
            val best = FloatArray(outCount)
            val hits = IntArray(outCount)
            var start = 0
            do {
                val window = FloatArray(windowLen)
                val n = minOf(windowLen, samples.size - start).coerceAtLeast(0)
                System.arraycopy(samples, start, window, 0, n)
                val out = Array(1) { FloatArray(outCount) }
                interp.run(arrayOf(window), out)
                for (i in 0 until outCount) {
                    val p = sigmoid(out[0][i])
                    if (p > best[i]) best[i] = p
                    if (p >= minScore) hits[i]++
                }
                start += step
            } while (start + step < samples.size)

            best.indices.filter { best[it] >= minScore && it < labels.size }
                .sortedByDescending { best[it] }
                .take(max)
                .map { SongMatch(labels[it].first, labels[it].second, best[it], hits[it]) }
        } }

    fun toCandidates(matches: List<SongMatch>): List<Candidate> = matches.map {
        Candidate(
            scientificName = it.scientificName,
            commonName = it.commonName,
            score = it.score,
            source = IdSource.SONG,
            note = "Heard in ${it.windows} of the recording's 3-second clips",
        )
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val MODEL = "models/birdnet.tflite"
        private const val LABELS = "models/birdnet_labels.txt"
    }
}
