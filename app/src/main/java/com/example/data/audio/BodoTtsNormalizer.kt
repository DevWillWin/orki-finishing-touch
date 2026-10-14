package com.example.data.audio

import java.util.regex.Pattern

/**
 * High-performance Phonetic Normalizer & Pre-processor for Bodo (बर') Speech Synthesis.
 *
 * Resolves pronunciation degradation by:
 * 1. Stripping markdown formatting, code blocks, bullet points, and web symbols.
 * 2. Purging all emoji glyphs (which confuse Indic TTS neural acoustic models).
 * 3. Phonetically transliterating English loanwords (e.g. "Spanish" -> "स्पेनिस", "phone" -> "फोन", "AI" -> "एआइ").
 * 4. Converting numeric digits into spoken Bodo numbers (e.g. 1 -> से, 2 -> नै, 3 -> थाम).
 * 5. Syllable-aware Roman Bodo to Devanagari conversion preventing illegal Unicode matra combinations.
 * 6. Inserting acoustic pause markers at punctuation boundaries (commas, periods, question marks).
 */
object BodoTtsNormalizer {

    private val EMOJI_REGEX = Pattern.compile(
        "[\uD83C-\uDBFF\uDC00-\uDFFF]|[\u2600-\u27BF]|[\uE000-\uF8FF]|[\uFE00-\uFE0F]|[\u200D\u200C]"
    )

    private val MARKDOWN_REGEX = Regex("[#*_`~>\\[\\]()|{}]")

    // Common Bodo words in colloquial Roman script mapped directly to standard Bodo Devanagari (बर' हांखो)
    private val ROMAN_BODO_DICTIONARY = linkedMapOf(
        "khulumbai" to "खुलुमबाय",
        "hambai" to "हामबाय",
        "sabaykhar" to "साबायखर",
        "sabayzanai" to "साबायजानय",
        "mwjang" to "मोजां",
        "mwzang" to "मोजां",
        "mabwi" to "माबोरै",
        "mabrwi" to "माबोरै",
        "mabwrwi" to "माबोरै",
        "bwrwi" to "बोरै",
        "jwmwi" to "जोमै",
        "zomwi" to "जोमै",
        "dong" to "दं",
        "donga" to "दङ",
        "dongw" to "दङ",
        "gwiya" to "गैया",
        "gaiya" to "गैया",
        "nwng" to "नों",
        "nwngni" to "नोंनि",
        "nwngha" to "नोंहा",
        "nwngkhwo" to "नोंखौ",
        "nwngkhw" to "नोंखौ",
        "ang" to "आं",
        "angni" to "आंनि",
        "angha" to "आंहा",
        "angkhwo" to "आंखौ",
        "angkhw" to "आंखौ",
        "ma" to "मा",
        "khobor" to "खबर",
        "thang" to "थां",
        "thangdwng" to "थांदों",
        "thangnw" to "थांनो",
        "khalam" to "खालाम",
        "khalamdwng" to "खालामदों",
        "khalamnw" to "खालामनो",
        "swr" to "सोर",
        "swrni" to "सोरनि",
        "boha" to "बहा",
        "baha" to "बहा",
        "bobe" to "बबे",
        "bobo" to "बबे",
        "fai" to "फै",
        "phai" to "फै",
        "faidwng" to "फैदों",
        "phaidwng" to "फैदों",
        "onkham" to "ओंखाम",
        "wngkham" to "ओंखाम",
        "mung" to "मुं",
        "munga" to "मुंआ",
        "bodo" to "बर'",
        "borw" to "बर'",
        "harini" to "हारिनि",
        "saorai" to "सावराय",
        "saorainw" to "सावरायनो",
        "saoraidw" to "सावरायदो",
        "raylai" to "रायलाय",
        "raylainw" to "रायलायनो",
        "gwdan" to "गोदान",
        "gjam" to "गोजाम",
        "nama" to "नामा",
        "nagirdwng" to "नागिरदों",
        "salte" to "साल्थे",
        "ada" to "आदा",
        "abo" to "आब'",
        "aie" to "आइ",
        "afw" to "आफा",
        "aphw" to "आफा",
        "fwrwng" to "फोरों",
        "phwrwng" to "फोरों",
        "solong" to "सोलों",
        "mithi" to "मिथि",
        "mithiyw" to "मिथियो",
        "mithigo" to "मिथियो",
        "sanse" to "सानसे",
        "boraibai" to "बरायबाय",
        "bwraibai" to "बरायबाय",
        // Common Animals & Everyday Vocabulary
        "swima" to "सैमा",
        "suima" to "सैमा",
        "saima" to "सैमा",
        "mousi" to "मावजि",
        "maosi" to "मावजि",
        "maoji" to "मावजि",
        "mwsou" to "मसौ",
        "mosou" to "मसौ",
        "mousou" to "मसौ",
        "mwisw" to "मैस'",
        "maisu" to "मैस'",
        "oma" to "अमा",
        "dao" to "दाव",
        "na" to "ना",
        "dwi" to "दै",
        "nokhor" to "नखर",
        "hagra" to "हाग्रा"
    )

    // English loanwords transliterated into phonetic Devanagari so Indic TTS models pronounce them naturally
    private val ENGLISH_LOANWORDS = linkedMapOf(
        "spanish" to "स्पेनिस",
        "english" to "इंग्राजि",
        "phone" to "फोन",
        "mobile" to "मबाइल",
        "ai" to "एआइ",
        "orki" to "अरकि",
        "app" to "एप",
        "application" to "एप्लिकेसन",
        "chat" to "सेट",
        "message" to "मेसेज",
        "call" to "कल",
        "google" to "गुगोल",
        "youtube" to "इउथुब",
        "whatsapp" to "ह्वाट्सएप",
        "video" to "भिडिअ'",
        "photo" to "फथ'",
        "image" to "इमेज",
        "camera" to "केमेरा",
        "computer" to "कमपिउतार",
        "internet" to "इन्तारनेथ",
        "online" to "अनलाइन",
        "status" to "स्तेतास",
        "settings" to "सेतिं",
        "audio" to "अडिअ'",
        "music" to "म्युजिक",
        "voice" to "भयस",
        "friend" to "लोगो",
        "hello" to "हेल'",
        "hi" to "हाइ",
        "hey" to "हे",
        "ok" to "अके",
        "okay" to "अके",
        "sorry" to "सरि",
        "thanks" to "साबायखर",
        "thank you" to "साबायखर",
        "yes" to "नोंगो",
        "no" to "नोंङा",
        "battery" to "बेतारि",
        "link" to "लिंक",
        "help" to "हेलप",
        "problem" to "समस्या",
        "question" to "सोंनाय"
    )

    // Bodo numerals in speech
    private val BODO_NUMBERS = mapOf(
        "0" to "लथ'",
        "1" to "से",
        "2" to "नै",
        "3" to "थाम",
        "4" to "ब्रै",
        "5" to "बा",
        "6" to "द'",
        "7" to "स्नि",
        "8" to "दान्थि",
        "9" to "गु",
        "10" to "जि"
    )

    /**
     * Prepares raw LLM output or user text for clean, natural speech synthesis.
     */
    fun normalizeForTts(rawText: String): String {
        if (rawText.isBlank()) return ""

        // 1. Strip markdown syntax
        var text = MARKDOWN_REGEX.replace(rawText, " ")

        // 2. Strip emojis and special Unicode pictographs
        text = EMOJI_REGEX.matcher(text).replaceAll("")

        // 3. Normalize multiple whitespace and dashes
        text = text.replace(Regex("-{2,}"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        // 4. Replace single numbers (0-10) with Bodo speech words
        for ((digit, word) in BODO_NUMBERS) {
            text = text.replace(Regex("\\b$digit\\b"), word)
        }

        // 5. Replace English loanwords with Devanagari phonetics
        for ((en, deva) in ENGLISH_LOANWORDS) {
            val pattern = Regex("(?i)\\b$en\\b")
            text = text.replace(pattern, deva)
        }

        // 6. If the text already contains Devanagari characters, clean punctuation and return
        val devanagariCount = text.count { it in '\u0900'..'\u097F' }
        if (devanagariCount >= 3) {
            return formatPunctuationPauses(cleanDevanagariArtifacts(text))
        }

        // 7. Otherwise, text is in Roman Bodo script. Transliterate to standard Bodo Devanagari
        var romanText = text.lowercase()

        // Apply whole-word dictionary replacements first
        for ((rom, deva) in ROMAN_BODO_DICTIONARY) {
            romanText = romanText.replace(Regex("\\b$rom\\b"), deva)
        }

        // Syllable-aware phonetics for remaining words
        val transliterated = transliterateRomanToDevanagari(romanText)

        return formatPunctuationPauses(cleanDevanagariArtifacts(transliterated))
    }

    /**
     * Syllable-based transliteration ensuring base consonants precede vowel matras.
     */
    private fun transliterateRomanToDevanagari(input: String): String {
        val words = input.split(" ")
        val sb = StringBuilder()

        for ((idx, word) in words.withIndex()) {
            if (idx > 0) sb.append(" ")

            // If word already contains Devanagari, keep it as-is
            if (word.any { it in '\u0900'..'\u097F' }) {
                sb.append(word)
                continue
            }

            // Word-level transliteration
            sb.append(convertWord(word))
        }

        return sb.toString()
    }

    private fun convertWord(w: String): String {
        var str = w

        // Specific Bodo digraphs
        str = str.replace("kh", "ख")
            .replace("ph", "फ")
            .replace("th", "थ")
            .replace("ch", "छ")
            .replace("sh", "श")
            .replace("ng", "ं")
            .replace("wi", "ुइ")
            .replace("ai", "ै")
            .replace("ao", "ौ")
            .replace("au", "ौ")
            .replace("jw", "जो")
            .replace("nw", "नो")
            .replace("bw", "बो")
            .replace("dw", "दो")
            .replace("gw", "गो")
            .replace("sw", "सो")
            .replace("fw", "फो")

        // Consonants
        val consonants = mapOf(
            "b" to "ब", "d" to "द", "g" to "ग", "h" to "ह",
            "j" to "ज", "k" to "क", "l" to "ल", "m" to "म",
            "n" to "न", "p" to "प", "r" to "र", "s" to "स",
            "t" to "त", "y" to "य", "z" to "ज", "v" to "भ",
            "w" to "व"
        )
        for ((c, dev) in consonants) {
            str = str.replace(c, dev)
        }

        // Standalone or dependent vowels
        str = str.replace(Regex("^a"), "आ")
            .replace(Regex("^i"), "इ")
            .replace(Regex("^u"), "उ")
            .replace(Regex("^e"), "ए")
            .replace(Regex("^o"), "ओ")
            .replace("a", "ा")
            .replace("i", "ि")
            .replace("u", "ु")
            .replace("e", "े")
            .replace("o", "ो")

        return str
    }

    /**
     * Fix dangling matras (e.g. standalone ' ा' without consonant) that distort neural vocoders.
     */
    private fun cleanDevanagariArtifacts(text: String): String {
        return text
            // Replace dangling vowel matras at the beginning of words with independent vowels
            .replace(Regex("\\bा"), "आ")
            .replace(Regex("\\bि"), "इ")
            .replace(Regex("\\bु"), "उ")
            .replace(Regex("\\bे"), "ए")
            .replace(Regex("\\bो"), "ओ")
            .replace(Regex("\\bौ"), "औ")
            .replace(Regex("\\bै"), "ऐ")
            .replace(Regex("\\s+([ािुेोौैं])"), " $1")
    }

    /**
     * Normalize punctuation for natural acoustic pauses and pitch contours.
     */
    private fun formatPunctuationPauses(text: String): String {
        var res = text
            .replace(Regex("[?!.]+"), ".")
            .replace(".", "। ")
            .replace(",", ", ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (res.isNotEmpty() && !res.endsWith("।") && !res.endsWith("?")) {
            res += "।"
        }
        return res
    }
}
