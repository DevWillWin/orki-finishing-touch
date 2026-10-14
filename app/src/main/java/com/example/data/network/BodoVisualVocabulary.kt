package com.example.data.network

import android.util.Log

/**
 * Deterministic Bodo visual dictionary and validation utility.
 * Guarantees that Romanized Bodo or Devanagari terms are correctly mapped to English
 * visual subjects before reaching diffusion models (FLUX, Pollinations, Cloudflare).
 *
 * Prevents:
 * 1. "swima" being interpreted as English "swim" -> water / swimming pool / women.
 * 2. Conversational Bodo responses from ever reaching the image generator.
 * 3. Devanagari script being passed to English CLIP/T5 text encoders.
 */
object BodoVisualVocabulary {

    private const val TAG = "BodoVisualVocab"

    // Core starter vocabulary & cultural concepts
    private val VOCABULARY_MAP = linkedMapOf<String, String>(
        // Animals (with romanized variants & Devanagari)
        "swima" to "dog",
        "suima" to "dog",
        "soima" to "dog",
        "सैमा" to "dog",
        "सैमाया" to "dog",
        "mouzi" to "cat",
        "mousi" to "cat",
        "mauzi" to "cat",
        "मौजौ" to "cat",
        "मौजिया" to "cat",
        "dao" to "bird",
        "दाव" to "bird",
        "oma" to "pig",
        "अमा" to "pig",
        "mousho" to "cow",
        "mouso" to "cow",
        "mowsho" to "cow",
        "मौस" to "cow",
        "gorai" to "horse",
        "गराय" to "horse",
        "na" to "fish",
        "ना" to "fish",
        // Possessive forms & compound terms
        "swimani" to "dog",
        "suimani" to "dog",
        "सैमानि" to "dog",
        "mouzini" to "cat",
        "मौजौनि" to "cat",
        // People & Portraits
        "hinjaw" to "Bodo woman",
        "हिनजाव" to "Bodo woman",
        "sikhla" to "young Bodo woman",
        "सिख्ला" to "young Bodo woman",
        "howa" to "Bodo man",
        "hawa" to "Bodo man",
        "हौवा" to "Bodo man",
        "gotho" to "Bodo child",
        "गथ'" to "Bodo child",
        // Nature & Scenery
        "bibar" to "blossoming flowers in full bloom",
        "बिबार" to "blossoming flowers in full bloom",
        "nokhor" to "traditional rural Assam courtyard village home",
        "नखर" to "traditional rural Assam courtyard village home",
        "hagrama" to "lush green forest jungle with tall trees",
        "हाग्रामा" to "lush green forest jungle with tall trees",
        "hagra" to "forest",
        "dwi" to "crystal clear river water stream",
        "दै" to "crystal clear river water stream",
        "ha" to "land",
        "hor" to "night sky with stars",
        "san" to "sun bright daylight",
        "okhrang" to "blue sky with soft clouds",
        // Cultural Attire & Traditions
        "dokhona" to "traditional Bodo dokhona attire handwoven with intricate Agor motifs in bright yellow, green, and red",
        "दखना" to "traditional Bodo dokhona attire handwoven with intricate Agor motifs in bright yellow, green, and red",
        "aronai" to "traditional Bodo aronai scarf with geometric Agor embroidery around the neck",
        "आर'नाइ" to "traditional Bodo aronai scarf with geometric Agor embroidery around the neck",
        "fasra" to "traditional Bodo fasra upper cloth drape",
        "खाम" to "traditional Bodo kham drum",
        "kham" to "traditional Bodo kham drum instrument",
        "sifung" to "traditional Bodo long bamboo flute sifung",
        "सिफुं" to "traditional Bodo long bamboo flute sifung",
        "serja" to "traditional Bodo violin instrument serja",
        "bwisagu" to "Bodo Bwisagu spring festival celebration, traditional dance, musical instruments",
        "बैसागु" to "Bodo Bwisagu spring festival celebration, traditional dance, musical instruments"
    )

    // Bodo classifiers to normalize away (e.g. "mase swima" = "one dog" -> "a dog")
    private val CLASSIFIERS = listOf(
        "mase", "masi", "mwnse", "monse", "sase", "sasi", "gangse", "khonse", "thw", "thoi", "ni"
    )

    // Required English visual keywords corresponding to specific Bodo subjects
    private val SUBJECT_KEYWORDS = mapOf(
        "swima" to listOf("dog", "canine", "puppy", "hound"),
        "suima" to listOf("dog", "canine", "puppy", "hound"),
        "soima" to listOf("dog", "canine", "puppy", "hound"),
        "सैमा" to listOf("dog", "canine", "puppy", "hound"),
        "mouzi" to listOf("cat", "feline", "kitten"),
        "mousi" to listOf("cat", "feline", "kitten"),
        "मौजौ" to listOf("cat", "feline", "kitten"),
        "dao" to listOf("bird", "avian", "feather"),
        "दाव" to listOf("bird", "avian", "feather"),
        "oma" to listOf("pig", "piglet", "swine"),
        "अमा" to listOf("pig", "piglet", "swine"),
        "mousho" to listOf("cow", "bovine", "cattle"),
        "gorai" to listOf("horse", "stallion", "equine"),
        "गराय" to listOf("horse", "stallion", "equine"),
        "na" to listOf("fish", "aquatic"),
        "ना" to listOf("fish", "aquatic"),
        "hagrama" to listOf("forest", "jungle", "trees", "woods"),
        "dwi" to listOf("water", "river", "stream")
    )

    /**
     * Resolves a Romanized or Devanagari Bodo user prompt to a clean English visual description.
     */
    fun resolveBodoToEnglishVisual(rawPrompt: String, isVideo: Boolean = false): String {
        val clean = rawPrompt.trim()
        if (clean.isEmpty()) return if (isVideo) "Cinematic 8k video footage" else "A high-quality 8k photograph"

        var resolvedText = clean

        // 1. Normalize classifiers first (e.g. "mase swima" -> "a swima")
        resolvedText = resolvedText
            .replace(Regex("\\bmase\\b", RegexOption.IGNORE_CASE), "a")
            .replace(Regex("\\bmasi\\b", RegexOption.IGNORE_CASE), "a")
            .replace(Regex("\\bmwnse\\b", RegexOption.IGNORE_CASE), "a")
            .replace(Regex("\\bmonse\\b", RegexOption.IGNORE_CASE), "a")
            .replace(Regex("\\bsase\\b", RegexOption.IGNORE_CASE), "a")
            .replace(Regex("\\bsasi\\b", RegexOption.IGNORE_CASE), "a")
            .replace(Regex("\\bमासे\\b", RegexOption.IGNORE_CASE), "a")
            .replace(Regex("\\bमोनसे\\b", RegexOption.IGNORE_CASE), "a")
            .replace(Regex("\\bसासे\\b", RegexOption.IGNORE_CASE), "a")

        // 2. Comprehensive vocabulary translation
        for ((bodoTerm, englishMeaning) in VOCABULARY_MAP) {
            val pattern = Regex("\\b${Regex.escape(bodoTerm)}\\b", RegexOption.IGNORE_CASE)
            resolvedText = resolvedText.replace(pattern, englishMeaning)
        }

        resolvedText = resolvedText.replace(Regex("\\s+"), " ").trim()

        return buildDeterministicPrompt(resolvedText, null, resolvedText, isVideo)
    }

    private fun safeLogW(tag: String, message: String) {
        try {
            Log.w(tag, message)
        } catch (_: Throwable) {
            println("[$tag] $message")
        }
    }

    /**
     * Validates that an enhanced prompt received from AI is a valid English visual prompt
     * and preserves the intended subject.
     */
    fun validateEnhancedPrompt(
        originalPrompt: String,
        enhancedCandidate: String
    ): Boolean {
        val trimmed = enhancedCandidate.trim()
        if (trimmed.isEmpty()) return false

        // 1. Must NOT contain Devanagari script (\u0900-\u097F)
        if (trimmed.any { it in '\u0900'..'\u097F' }) {
            safeLogW(TAG, "Validation failed: candidate contains Devanagari characters: $trimmed")
            return false
        }

        // 2. Must NOT be conversational (questions, greetings, assistance offers)
        val lower = trimmed.lowercase()
        val conversationalMarkers = listOf(
            "what do you want", "i can help you", "can i help", "how can i",
            "here is your", "sure, here", "as an ai", "what would you like to know",
            "tell me what", "hello", "hi there", "nongni", "khobor", "mwdang",
            "ang boro", "swngnai"
        )
        if (conversationalMarkers.any { lower.contains(it) } || trimmed.endsWith("?")) {
            safeLogW(TAG, "Validation failed: candidate is conversational text: $trimmed")
            return false
        }

        // 3. Subject Preservation: If original prompt contains "swima"/"suima", candidate MUST contain dog/canine
        val originalLower = originalPrompt.lowercase()
        for ((term, requiredKeywords) in SUBJECT_KEYWORDS) {
            val termRegex = Regex("\\b${Regex.escape(term)}\\b", RegexOption.IGNORE_CASE)
            if (termRegex.containsMatchIn(originalLower)) {
                val hasExpectedSubject = requiredKeywords.any { lower.contains(it) }
                if (!hasExpectedSubject) {
                    safeLogW(TAG, "Validation failed: original request was for '$term', but enhanced prompt did not contain any of $requiredKeywords. Prompt: $trimmed")
                    return false
                }
                // Specific check: if subject was swima (dog), forbid "swim", "swimming", "swimmer"
                if ((term == "swima" || term == "suima") && !lower.contains("dog") && (lower.contains("swim") || lower.contains("pool"))) {
                    safeLogW(TAG, "Validation failed: 'swima' falsely translated to swimming: $trimmed")
                    return false
                }
            }
        }

        return true
    }

    /**
     * Constructs a high-definition visual description template preserving user style.
     */
    private fun buildDeterministicPrompt(
        rawPrompt: String,
        matchedTerm: String?,
        translatedMeaning: String,
        isVideo: Boolean
    ): String {
        val baseSubject = when {
            matchedTerm != null -> {
                // If user wrote "swima sitting on grass", replace "swima" with "dog"
                rawPrompt.replace(Regex("\\b${Regex.escape(matchedTerm)}\\b", RegexOption.IGNORE_CASE), translatedMeaning)
                    .replace(Regex("\\bmase\\s+", RegexOption.IGNORE_CASE), "a ")
                    .replace(Regex("\\bmwnse\\s+", RegexOption.IGNORE_CASE), "a ")
                    .trim()
            }
            else -> translatedMeaning
        }

        return if (isVideo) {
            "Cinematic 8k video footage of $baseSubject, smooth cinematic camera motion, photorealistic natural lighting, highly detailed textures, 4k ultra-hd clarity."
        } else {
            "A high-quality, photorealistic photograph of $baseSubject, captured with a professional 85mm lens, natural daylight, sharp focus, rich colors, 8k resolution masterpiece."
        }
    }
}
