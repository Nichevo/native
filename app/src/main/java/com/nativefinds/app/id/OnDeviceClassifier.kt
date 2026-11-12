package com.nativefinds.app.id

import android.content.Context
import com.nativefinds.app.data.PhotoStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.classifier.ImageClassifier

/**
 * Runs Google's AIY bird and plant classifiers on the phone. Labels in these models are scientific
 * names; "background" / "None" labels are dropped.
 */
class OnDeviceClassifier(private val context: Context) {
    enum class Model(val asset: String) { BIRDS("models/birds.tflite"), PLANTS("models/plants.tflite") }

    private val loaded = mutableMapOf<Model, ImageClassifier>()
    private val lock = Mutex()

    fun isAvailable(model: Model): Boolean =
        runCatching { context.assets.open(model.asset).use { true } }.getOrDefault(false)

    suspend fun classify(photoPath: String, model: Model, max: Int = 10): List<Candidate> =
        withContext(Dispatchers.Default) {
            if (!isAvailable(model)) {
                throw IdentificationException(
                    "The on-phone ${model.name.lowercase()} model isn't included in this build",
                )
            }
            lock.withLock {
                val classifier = loaded.getOrPut(model) {
                    val options = ImageClassifier.ImageClassifierOptions.builder()
                        .setMaxResults(max + 3)
                        .build()
                    ImageClassifier.createFromFileAndOptions(context, model.asset, options)
                }
                val bitmap = PhotoStore.loadBitmap(photoPath, maxSide = 1024)
                val results = classifier.classify(TensorImage.fromBitmap(bitmap))
                results.flatMap { it.categories }
                    .filterNot { isBackground(it.label) || isBackground(it.displayName) }
                    .sortedByDescending { it.score }
                    .take(max)
                    .map {
                        val label = it.label.ifBlank { it.displayName }
                        Candidate(
                            scientificName = label.trim(),
                            commonName = it.displayName.takeIf { d -> d.isNotBlank() && d != label },
                            score = it.score,
                            source = IdSource.ON_DEVICE,
                        )
                    }
            }
        }

    private fun isBackground(label: String?): Boolean {
        val l = label?.trim()?.lowercase() ?: return false
        return l == "background" || l == "none" || l.isEmpty()
    }
}
