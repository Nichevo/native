package com.nativefinds.app.id

/** What the person is photographing. Plant categories map to Pl@ntNet "organs". */
enum class Category(val label: String, val plantNetOrgan: String?, val isPlant: Boolean) {
    BIRD("Bird", null, false),
    LEAF("Leaf", "leaf", true),
    FLOWER("Flower", "flower", true),
    FRUIT("Fruit / seed", "fruit", true),
    BARK("Bark", "bark", true),
    WHOLE_PLANT("Whole plant", "auto", true),
    INSECT("Bug / insect", null, false),
    LIZARD("Lizard / gecko", null, false),
    OTHER("Something else", null, false),
}

enum class IdSource(val label: String) {
    PLANTNET("Pl@ntNet"),
    ON_DEVICE("On-phone model"),
    PC_AI("PC AI"),
    MANUAL("Entered by hand"),
    SONG("BirdNET (bird song)"),
}

/** Whether the region pack says the species has been recorded in the chosen region. */
enum class RegionMatch { RECORDED, NOT_RECORDED, UNKNOWN }

data class Candidate(
    val scientificName: String,
    val commonName: String?,
    /** 0..1 */
    val score: Float,
    val source: IdSource,
    val family: String? = null,
    val note: String? = null,
    val regionMatch: RegionMatch = RegionMatch.UNKNOWN,
    val establishment: String? = null,
    val photoUrl: String? = null,
    val maoriName: String? = null,
)

open class IdentificationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The PC couldn't be reached (off, asleep, no tunnel, no signal): worth queueing for later. */
class PcUnreachableException(message: String, cause: Throwable? = null) : IdentificationException(message, cause)
