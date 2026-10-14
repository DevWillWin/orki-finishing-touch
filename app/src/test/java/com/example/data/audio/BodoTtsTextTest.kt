package com.example.data.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class BodoTtsTextTest {

    private fun tokenizer(): BodoTokenizer = BodoTokenizer.parse(TestVocab.tokensText().lineSequence())

    // ---- speakers --------------------------------------------------------------------------------

    @Test
    fun femaleVoiceMapsToBrxFemaleNotMale() {
        // Regression: the old check `voice.contains("male")` also matched "female_*" -> speaker 5.
        assertEquals(4L, BodoSpeakers.speakerIdFor("female_mainao"))
        assertEquals(4L, BodoSpeakers.speakerIdFor("female"))
        assertEquals(4L, BodoSpeakers.speakerIdFor("female_alari"))
        assertEquals(4L, BodoSpeakers.speakerIdFor(" FEMALE_Mainao "))
    }

    @Test
    fun maleVoiceMapsToBrxMale() {
        assertEquals(5L, BodoSpeakers.speakerIdFor("male_birphung"))
        assertEquals(5L, BodoSpeakers.speakerIdFor("male"))
        assertEquals(5L, BodoSpeakers.speakerIdFor("male_somkhwr"))
        assertEquals(5L, BodoSpeakers.speakerIdFor("Male_Birphung"))
    }

    @Test
    fun unknownVoiceDefaultsToFemaleLikeVoiceOptions() {
        assertEquals(4L, BodoSpeakers.speakerIdFor(""))
        assertEquals(4L, BodoSpeakers.speakerIdFor("robot"))
    }

    // ---- tokenizer -------------------------------------------------------------------------------

    @Test
    fun parsesSpaceTokenWrittenAsTwoSpacesAndId() {
        val t = tokenizer()
        assertArrayEquals(intArrayOf(154, TestVocab.SPACE, 154), t.encode("आ आ").tokenIds)
    }

    @Test
    fun spacesAreCollapsedAndTrimmedSoDroppedCharactersLeaveNoDoublePause() {
        val t = tokenizer()
        assertArrayEquals(intArrayOf(154, 16, 154), t.encode("  आ  7  \t आ  ").tokenIds) // the '7' is dropped
        val onlySpacesAndUnknown = t.encode(" 12 34 ")
        assertEquals(0, onlySpacesAndUnknown.tokenIds.size)
        assertEquals(0, onlySpacesAndUnknown.letterCount)
        assertEquals(4, onlySpacesAndUnknown.droppedCharacters)
    }

    @Test
    fun letterCountIgnoresPunctuationOnlyText() {
        val t = tokenizer()
        assertEquals(0, t.encode("।").letterCount)
        assertEquals(0, t.encode(", .").letterCount)
        assertEquals(2, t.encode("आ क।").letterCount)
    }

    @Test
    fun idsMatchTheRealVocabularyRules() {
        val t = tokenizer()
        // आ=U+0906 -> 154, ं=U+0902 -> 150, क=U+0915 -> 169, ।=U+0964 -> 248 (values seen in the published file).
        assertArrayEquals(intArrayOf(154, 150, 16, 169, 248), t.encode("आं क।").tokenIds)
        assertEquals(17, t.encode("A").tokenIds[0])
        assertEquals(43, t.encode("a").tokenIds[0])
        assertEquals(68, t.encode("z").tokenIds[0])
    }

    @Test
    fun apostrophesBecomeTheBodoToneModifier() {
        val t = tokenizer()
        val da = TestVocab.id(0x0926) // द
        val expected = intArrayOf(da, TestVocab.APOSTROPHE_MODIFIER)
        assertArrayEquals(expected, t.encode("द'").tokenIds)
        assertArrayEquals(expected, t.encode("द\u2019").tokenIds)
        assertArrayEquals(expected, t.encode("द\u02BC").tokenIds)
    }

    @Test
    fun unsupportedCharactersAreDroppedAndCounted() {
        val t = tokenizer()
        val e = t.encode("आ7आ") // ASCII digits are not in the vocabulary
        assertEquals(2, e.tokenIds.size)
        assertEquals(1, e.droppedCharacters)
        val emoji = t.encode("आ\uD83D\uDE00आ") // one astral code point, counted once
        assertEquals(2, emoji.tokenIds.size)
        assertEquals(1, emoji.droppedCharacters)
    }

    @Test
    fun otherWhitespaceMapsToTheSpaceToken() {
        val t = tokenizer()
        assertArrayEquals(intArrayOf(154, 16, 154, 16, 154), t.encode("आ\tआ\u00A0आ").tokenIds)
    }

    @Test
    fun blankInterleavingMatchesVitsAddBlank() {
        assertArrayEquals(longArrayOf(0, 5, 0, 7, 0), BodoTokenizer.interleaveBlank(intArrayOf(5, 7)))
        assertArrayEquals(longArrayOf(0), BodoTokenizer.interleaveBlank(IntArray(0)))
        val ids = tokenizer().encode(TestVocab.SHORT_SENTENCE).tokenIds
        val withBlanks = BodoTokenizer.interleaveBlank(ids)
        assertEquals(ids.size * 2 + 1, withBlanks.size)
        assertEquals(0L, withBlanks.first())
        assertEquals(0L, withBlanks.last())
    }

    @Test
    fun parseToleratesCrLfBomAndMalformedLines() {
        val text = "\uFEFF" + TestVocab.tokensText("\r\n") + "garbage-line-without-id\r\n\r\nxx 12\r\nq notanumber\r\n"
        val t = BodoTokenizer.parse(text.lineSequence())
        assertEquals(154, t.encode("आ").tokenIds[0])
        assertEquals(TestVocab.SPACE, t.encode("आ आ").tokenIds[1])
        // a multi-code-point token line ("xx 12") is ignored (the exporter only emits single symbols) and
        // a non-numeric id ("q notanumber") must not clobber the real id of 'q'
        assertEquals(66, t.encode("x").tokenIds[0])
        assertEquals(59, t.encode("q").tokenIds[0])
        assertFalse(t.encode("xx").tokenIds.contains(12))
    }

    @Test
    fun parseRejectsTinyOrEmptyVocabulary() {
        val e = org.junit.Assert.assertThrows(TtsException::class.java) {
            BodoTokenizer.parse(sequenceOf("  16", "A 17", "B 18"))
        }
        assertEquals(TtsErrorKind.MODEL_INVALID, e.kind)
        val e2 = org.junit.Assert.assertThrows(TtsException::class.java) { BodoTokenizer.parse(emptySequence()) }
        assertEquals(TtsErrorKind.MODEL_INVALID, e2.kind)
    }

    // ---- chunker ---------------------------------------------------------------------------------

    @Test
    fun shortTextIsOneChunk() {
        val r = BodoTextChunker.split(TestVocab.SHORT_SENTENCE)
        assertEquals(listOf(TestVocab.SHORT_SENTENCE), r.items)
        assertFalse(r.truncated)
    }

    @Test
    fun blankTextYieldsNoChunks() {
        assertTrue(BodoTextChunker.split("").items.isEmpty())
        assertTrue(BodoTextChunker.split("  \n\t \u00A0 ").items.isEmpty())
    }

    @Test
    fun whitespaceIsCollapsedButNothingElseChanges() {
        val r = BodoTextChunker.split("आं   नों\n\nखौ\tमोजां।")
        assertEquals(listOf("आं नों खौ मोजां।"), r.items)
    }

    @Test
    fun sentencesPackUpToTheLimitAndSplitAtDanda() {
        val s = "आं नोंखौ मोजां।"
        val text = (1..30).joinToString(" ") { s } // 30 sentences, ~450 chars
        val r = BodoTextChunker.split(text, maxChars = 100)
        assertTrue(r.items.size > 3)
        for (c in r.items) {
            assertTrue("chunk too long: ${c.length}", c.length <= 100)
            assertTrue("chunk must end at a sentence end: $c", c.endsWith("।"))
        }
        assertEquals(text, r.items.joinToString(" "))
    }

    @Test
    fun clauseBoundariesAreUsedBeforeWordBoundaries() {
        val clause = "आं नोंखौ मोजां मोन्नाय"
        val text = (1..12).joinToString(", ") { clause } + "।"
        val r = BodoTextChunker.split(text, maxChars = 60)
        for (c in r.items) assertTrue(c.length <= 60)
        // every chunk except the last ends with the clause comma
        for (c in r.items.dropLast(1)) assertTrue("expected clause end in '$c'", c.endsWith(","))
        assertEquals(text, r.items.joinToString(" "))
    }

    @Test
    fun singleHugeWordIsSplitOnlyAtGraphemeSafeBoundaries() {
        val cluster = "क्षि" // क + ् + ष + ि  (conjunct + matra)
        val word = cluster.repeat(120) // 480 chars, no spaces at all
        val r = BodoTextChunker.split(word, maxChars = 50, maxTotalChars = 1500)
        assertTrue(r.items.size > 5)
        for (c in r.items) {
            assertTrue("chunk exceeds limit+slack: ${c.length}", c.length <= 50 + BodoTextChunker.SLACK)
            assertFalse("chunk must not end with a virama: $c", c.last() == '\u094D')
            val first = c.first()
            assertFalse("chunk must not start with a matra/virama: $c", first == '\u093F' || first == '\u094D')
        }
        assertEquals(word, r.items.joinToString(""))
    }

    @Test
    fun noChunkStartsWithACombiningMarkOrToneApostrophe() {
        val word = "बर'".repeat(100) // Bodo tone apostrophes inside a very long word
        val r = BodoTextChunker.split(word, maxChars = 40)
        for (c in r.items) {
            assertFalse("starts with apostrophe: $c", c.startsWith("'"))
        }
        assertEquals(word, r.items.joinToString(""))
    }

    @Test
    fun surrogatePairsAreNeverSplit() {
        val word = "\uD83D\uDE00".repeat(80)
        val r = BodoTextChunker.split(word, maxChars = 33)
        for (c in r.items) {
            assertTrue(c.length % 2 == 0)
            assertFalse(Character.isLowSurrogate(c.first()))
        }
        assertEquals(word, r.items.joinToString(""))
    }

    @Test
    fun overlongInputIsTruncatedAtASentenceBoundaryAndFlagged() {
        val s = "आं नोंखौ मोजां मोन्नाय।"
        val text = (1..400).joinToString(" ") { s }
        val r = BodoTextChunker.split(text, maxChars = 180, maxTotalChars = 500)
        assertTrue(r.truncated)
        val joined = r.items.joinToString(" ")
        assertTrue(joined.length <= 500)
        assertTrue(joined.endsWith("।"))
    }

    @Test
    fun latinFullStopInsideANumberIsNotASentenceBoundary() {
        // Sentence 1 is 27 chars (> 24) so it must be split further; "3.5" must stay in one piece.
        val r = BodoTextChunker.split("abcdefghij 3.5 klmnopqrstu। vwxyz", maxChars = 24)
        assertTrue(r.items.any { it.contains("3.5") })
        assertTrue(r.items.none { it.endsWith("3.") })
        assertEquals("abcdefghij 3.5 klmnopqrstu। vwxyz", r.items.joinToString(" "))
    }

    @Test
    fun pausesAreLongerAfterSentencesThanClauses() {
        assertTrue(BodoTextChunker.pauseAfterMs("आं।") > BodoTextChunker.pauseAfterMs("आं,"))
        assertTrue(BodoTextChunker.pauseAfterMs("आं,") > BodoTextChunker.pauseAfterMs("आं"))
        assertEquals(220, BodoTextChunker.pauseAfterMs("आं।\u201D "))
    }

    @Test
    fun propertyEveryCharacterSurvivesInOrderAndChunksStayBounded() {
        val alphabet = ("कखगघचजटडतदनपबमयरलवशसह" + "ािीुूेैोौंँः" + "्" + "ʼ'" + "।।,;  ").toCharArray()
        val rnd = Random(20260101)
        repeat(300) { iteration ->
            val len = 1 + rnd.nextInt(900)
            val sb = StringBuilder()
            repeat(len) { sb.append(alphabet[rnd.nextInt(alphabet.size)]) }
            val input = sb.toString()
            val max = 30 + rnd.nextInt(170)
            val r = BodoTextChunker.split(input, maxChars = max, maxTotalChars = 100_000)

            val expected = input.filter { !it.isWhitespace() }
            val actual = r.items.joinToString("").filter { !it.isWhitespace() }
            assertEquals("iteration $iteration lost/reordered characters", expected, actual)
            assertFalse(r.truncated)
            for (c in r.items) {
                assertTrue("iteration $iteration: chunk of ${c.length} > ${max + BodoTextChunker.SLACK}", c.length <= max + BodoTextChunker.SLACK)
                assertTrue(c.isNotBlank())
                assertEquals(c, c.trim())
            }
        }
    }

    @Test
    fun tokensPerChunkStayBelowTheModelBudget() {
        val t = tokenizer()
        val text = (1..200).joinToString(" ") { "आं नोंखौ मोजां मोन्नाय दान्थि," } + "।"
        val r = BodoTextChunker.split(text, maxTotalChars = 10_000)
        val budget = 2 * (BodoTextChunker.DEFAULT_MAX_CHARS + BodoTextChunker.SLACK) + 1
        for (c in r.items) {
            val tokens = BodoTokenizer.interleaveBlank(t.encode(c).tokenIds).size
            assertTrue("$tokens tokens > $budget", tokens <= budget)
        }
    }
}
