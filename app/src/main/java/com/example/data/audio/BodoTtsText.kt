package com.example.data.audio

import java.io.File
import java.util.Locale

/**
 * Speaker ids of `ai4bharat/vits_rasa_13` (model card, "Speaker-Style Identifier Overview"):
 * BRX_F = 4, BRX_M = 5. Style id 0 is "ALEXA" (neutral assistant style).
 */
object BodoSpeakers {
    const val BRX_FEMALE = 4L
    const val BRX_MALE = 5L
    const val DEFAULT_EMOTION_ID = 0L

    /**
     * Maps an app voice id ("female_mainao", "male_birphung" and the legacy aliases "female",
     * "male", "male_somkhwr", "female_alari") to a speaker id. The *female* check must come first
     * because "female" contains the substring "male"; unknown ids default to the female voice,
     * matching `VoiceOptions.get`.
     */
    fun speakerIdFor(voiceId: String): Long {
        val v = voiceId.trim().lowercase(Locale.ROOT)
        return when {
            v.startsWith("female") -> BRX_FEMALE
            v.startsWith("male") -> BRX_MALE
            else -> BRX_FEMALE
        }
    }
}

/**
 * Character-level frontend for the Rasa VITS model.
 *
 * `tokens.txt` is written by the exporter as `"<char> <id>"` for every single-code-point symbol of
 * the upstream vocabulary. The space symbol is therefore the line `"  16"` (a space, a separator
 * space, then the id), so the id is always taken after the *last* space of the line.
 * Verified against the published file: space=16, A–Z=17..42, a–z=43..68, Devanagari block
 * U+0900..U+097F = 148..275 (danda U+0964 = 248), modifier apostrophe U+02BC = 1231.
 * ASCII digits and the ASCII apostrophe are *not* in the vocabulary.
 */
class BodoTokenizer private constructor(private val idByCodePoint: Map<Int, Int>) {

    val vocabularySize: Int get() = idByCodePoint.size

    /**
     * [tokenIds] have repeated spaces collapsed and no leading/trailing space (dropping unsupported
     * characters must not leave "double pauses" or a lone space). [letterCount] is the number of
     * letters/digits that were actually encoded; zero means there is nothing to pronounce.
     */
    class Encoding(val tokenIds: IntArray, val droppedCharacters: Int, val letterCount: Int)

    /** Converts text to raw token ids (no blanks). Characters outside the vocabulary are dropped and counted. */
    fun encode(text: String): Encoding {
        val spaceId = idByCodePoint[SPACE] ?: TOKEN_SPACE
        val out = IntArray(text.length)
        var count = 0
        var dropped = 0
        var letters = 0
        var lastWasSpace = true // suppresses leading spaces
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            val id: Int = when {
                // Typed apostrophes stand in for the Bodo high-tone modifier letter.
                cp == APOSTROPHE || cp == RIGHT_SINGLE_QUOTE || cp == LEFT_SINGLE_QUOTE ->
                    idByCodePoint[MODIFIER_APOSTROPHE] ?: TOKEN_APOSTROPHE_MODIFIER
                else -> idByCodePoint[cp]
                    ?: if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) spaceId else -1
            }
            if (id < 0) {
                dropped++
                continue
            }
            if (id == spaceId) {
                if (lastWasSpace) continue
                lastWasSpace = true
            } else {
                lastWasSpace = false
                if (Character.isLetter(cp) || Character.isDigit(cp)) letters++
            }
            out[count++] = id
        }
        if (count > 0 && out[count - 1] == spaceId) count-- // trim trailing space
        return Encoding(out.copyOf(count), dropped, letters)
    }

    companion object {
        const val TOKEN_SPACE = 16
        const val TOKEN_APOSTROPHE_MODIFIER = 1231 // ʼ (U+02BC)
        const val BLANK_ID = 0L

        private const val SPACE = 0x20
        private const val APOSTROPHE = 0x27
        private const val RIGHT_SINGLE_QUOTE = 0x2019
        private const val LEFT_SINGLE_QUOTE = 0x2018
        private const val MODIFIER_APOSTROPHE = 0x02BC
        private const val BOM = '\uFEFF'

        /** A real vocabulary has ~1,230 symbols; anything tiny is a truncated or wrong file. */
        const val MIN_VOCABULARY = 100

        /** VITS `add_blank=1`: `[0, t1, 0, t2, ..., tn, 0]` (length `2n + 1`). */
        fun interleaveBlank(tokenIds: IntArray): LongArray {
            val out = LongArray(tokenIds.size * 2 + 1) // zero-initialised == BLANK_ID
            for (i in tokenIds.indices) {
                out[i * 2 + 1] = tokenIds[i].toLong()
            }
            return out
        }

        fun toLongArray(tokenIds: IntArray): LongArray = LongArray(tokenIds.size) { tokenIds[it].toLong() }

        fun parse(lines: Sequence<String>): BodoTokenizer {
            val map = HashMap<Int, Int>(2048)
            var first = true
            for (rawLine in lines) {
                var line = rawLine.trimEnd('\r', '\n')
                if (first) {
                    first = false
                    if (line.isNotEmpty() && line[0] == BOM) line = line.substring(1)
                }
                if (line.isEmpty()) continue
                val sep = line.lastIndexOf(' ')
                if (sep < 0) continue
                val id = line.substring(sep + 1).trim().toIntOrNull() ?: continue
                if (id < 0) continue
                val token = line.substring(0, sep)
                val codePoint = when {
                    token.isEmpty() && sep == 0 -> SPACE // lenient: " 16" is read as the space token
                    token.isEmpty() -> continue
                    token.codePointCount(0, token.length) != 1 -> continue // exporter only emits single symbols
                    else -> token.codePointAt(0)
                }
                map[codePoint] = id
            }
            if (map.size < MIN_VOCABULARY) {
                throw TtsException(
                    TtsErrorKind.MODEL_INVALID,
                    "The voice pack's symbol table looks incomplete (${map.size} symbols). Please re-download the voice pack."
                )
            }
            if (!map.containsKey(SPACE)) map[SPACE] = TOKEN_SPACE
            return BodoTokenizer(map)
        }

        fun fromFile(file: File): BodoTokenizer =
            file.bufferedReader(Charsets.UTF_8).useLines { parse(it) }
    }
}

/**
 * Splits long text into model-sized chunks without corrupting Bodo text.
 *
 * Rules, in order of preference: sentence boundary (danda `।` / `॥` / `.?!…`), clause boundary
 * (`, ; : — –`), word boundary, and only for a single "word" longer than the limit a *grapheme-safe*
 * hard split (never between a base letter and its matra/nukta/anusvara, never after a virama, never
 * before an apostrophe/tone mark, never inside a surrogate pair). No character is ever dropped,
 * duplicated or reordered, which is covered by property-style tests.
 */
object BodoTextChunker {
    /** Keeps VITS inputs short: ~12 s of speech and a bounded attention / decoder memory peak. */
    const val DEFAULT_MAX_CHARS = 180

    /** Upper bound for one request; the remainder is reported as truncated, never silently dropped. */
    const val DEFAULT_MAX_TOTAL_CHARS = 1500

    /** A hard split may overshoot by this much to keep one grapheme cluster intact. */
    const val SLACK = 16

    class Chunks(val items: List<String>, val truncated: Boolean)

    private val SENTENCE_END = charArrayOf('।', '॥', '.', '?', '!', '…', '‽')
    private val CLAUSE_END = charArrayOf(',', ';', ':', '—', '–', '،', '؛')
    private val CLOSERS = charArrayOf('"', '”', '’', ')', ']', '»')

    // Viramas of the Indic scripts present in the model vocabulary.
    private val VIRAMAS = intArrayOf(0x094D, 0x09CD, 0x0A4D, 0x0ACD, 0x0B4D, 0x0BCD, 0x0C4D, 0x0CCD, 0x0D4D)
    private const val ZWNJ = 0x200C
    private const val ZWJ = 0x200D

    fun split(
        text: String,
        maxChars: Int = DEFAULT_MAX_CHARS,
        maxTotalChars: Int = DEFAULT_MAX_TOTAL_CHARS
    ): Chunks {
        require(maxChars >= 24) { "maxChars too small" }
        require(maxTotalChars >= maxChars) { "maxTotalChars must be >= maxChars" }

        var body = collapseWhitespace(text)
        if (body.isEmpty()) return Chunks(emptyList(), false)

        var truncated = false
        if (body.length > maxTotalChars) {
            body = cutAtBoundary(body, maxTotalChars)
            truncated = true
        }

        val pieces = ArrayList<String>()
        for (sentence in splitSentences(body)) {
            if (sentence.length <= maxChars) pieces.add(sentence) else pieces.addAll(splitLong(sentence, maxChars))
        }
        return Chunks(pack(pieces, maxChars), truncated)
    }

    /** Silence to insert after [chunk]: longer after a sentence end, shorter after a clause end. */
    fun pauseAfterMs(chunk: String): Int {
        var i = chunk.length - 1
        while (i >= 0 && (chunk[i] == ' ' || chunk[i] in CLOSERS)) i--
        if (i < 0) return 90
        val c = chunk[i]
        return when {
            c in SENTENCE_END -> 220
            c in CLAUSE_END -> 140
            else -> 90
        }
    }

    private fun collapseWhitespace(text: String): String {
        val sb = StringBuilder(text.length)
        var pendingSpace = false
        for (ch in text) {
            if (ch.isWhitespace() || Character.isSpaceChar(ch)) {
                pendingSpace = sb.isNotEmpty()
            } else {
                if (pendingSpace) {
                    sb.append(' ')
                    pendingSpace = false
                }
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun isTerminatorAt(s: String, i: Int): Boolean {
        val c = s[i]
        if (c !in SENTENCE_END) return false
        // A Latin full stop only ends a sentence before whitespace / end of text (keeps "3.5" and "a.b" intact).
        return c != '.' || i + 1 >= s.length || s[i + 1] == ' '
    }

    private fun splitSentences(s: String): List<String> {
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < s.length) {
            if (isTerminatorAt(s, i)) {
                var end = i + 1
                while (end < s.length && (isTerminatorAt(s, end) || s[end] in CLOSERS)) end++
                addTrimmed(out, s.substring(start, end))
                start = end
                i = end
            } else {
                i++
            }
        }
        if (start < s.length) addTrimmed(out, s.substring(start))
        return out
    }

    private fun addTrimmed(out: MutableList<String>, piece: String) {
        val t = piece.trim()
        if (t.isNotEmpty()) out.add(t)
    }

    private fun splitLong(sentence: String, max: Int): List<String> {
        // Clause pieces first.
        val clauses = ArrayList<String>()
        var start = 0
        for (i in sentence.indices) {
            val c = sentence[i]
            if (c in CLAUSE_END && (i + 1 >= sentence.length || sentence[i + 1] == ' ')) {
                addTrimmed(clauses, sentence.substring(start, i + 1))
                start = i + 1
            }
        }
        if (start < sentence.length) addTrimmed(clauses, sentence.substring(start))

        val pieces = ArrayList<String>()
        for (clause in clauses) {
            if (clause.length <= max) pieces.add(clause) else pieces.addAll(splitByWords(clause, max))
        }
        return pack(pieces, max)
    }

    private fun splitByWords(s: String, max: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            if (sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.setLength(0)
            }
        }
        for (word in s.split(' ')) {
            if (word.isEmpty()) continue
            if (word.length > max) {
                flush()
                out.addAll(hardSplit(word, max))
                continue
            }
            if (sb.isEmpty()) {
                sb.append(word)
            } else if (sb.length + 1 + word.length <= max) {
                sb.append(' ').append(word)
            } else {
                flush()
                sb.append(word)
            }
        }
        flush()
        return out
    }

    private fun hardSplit(word: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var start = 0
        while (word.length - start > max) {
            val cut = safeBoundary(word, start, start + max)
            out.add(word.substring(start, cut))
            start = cut
        }
        if (start < word.length) out.add(word.substring(start))
        return out
    }

    private fun pack(pieces: List<String>, max: Int): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (p in pieces) {
            if (sb.isEmpty()) {
                sb.append(p)
            } else if (sb.length + 1 + p.length <= max) {
                sb.append(' ').append(p)
            } else {
                out.add(sb.toString())
                sb.setLength(0)
                sb.append(p)
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** Cuts [s] to at most [limit] chars preferring a sentence end, then a space, then a safe boundary. */
    private fun cutAtBoundary(s: String, limit: Int): String {
        var bestSentence = -1
        for (i in 0 until minOf(limit, s.length)) {
            if (isTerminatorAt(s, i)) {
                var end = i + 1
                while (end < s.length && end < limit && (isTerminatorAt(s, end) || s[end] in CLOSERS)) end++
                if (end <= limit) bestSentence = end
            }
        }
        if (bestSentence >= limit / 2) return s.substring(0, bestSentence).trim()
        val space = s.lastIndexOf(' ', limit)
        if (space >= limit / 2) return s.substring(0, space).trim()
        return s.substring(0, safeBoundary(s, 0, limit)).trim()
    }

    /**
     * Largest legal boundary in (start, desired]; if none exists, the smallest legal boundary
     * above [desired] (bounded by [SLACK]) so a cluster is never cut; otherwise the end of [s].
     */
    internal fun safeBoundary(s: String, start: Int, desired: Int): Int {
        val upper = minOf(desired, s.length)
        var i = upper
        while (i > start) {
            if (isBoundaryAllowed(s, i)) return i
            i--
        }
        i = upper + 1
        val limit = minOf(s.length, upper + SLACK)
        while (i <= limit) {
            if (isBoundaryAllowed(s, i)) return i
            i++
        }
        return s.length
    }

    /** True when splitting between `s[i - 1]` and `s[i]` cannot damage a grapheme cluster. */
    internal fun isBoundaryAllowed(s: String, i: Int): Boolean {
        if (i <= 0 || i >= s.length) return true
        if (Character.isHighSurrogate(s[i - 1]) && Character.isLowSurrogate(s[i])) return false
        val next = s.codePointAt(i)
        val prev = s.codePointBefore(i)
        if (prev in VIRAMAS || prev == ZWJ || prev == ZWNJ) return false
        if (next == ZWJ || next == ZWNJ) return false
        // Apostrophes carry the Bodo tone mark and belong to the preceding syllable.
        if (next == 0x27 || next == 0x2019 || next == 0x02BC) return false
        return when (Character.getType(next).toByte()) {
            Character.NON_SPACING_MARK, Character.COMBINING_SPACING_MARK, Character.ENCLOSING_MARK -> false
            else -> true
        }
    }
}
