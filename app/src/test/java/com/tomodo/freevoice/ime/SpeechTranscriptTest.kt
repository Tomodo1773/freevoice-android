package com.tomodo.freevoice.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SpeechTranscriptTest {
    @Test
    fun `interim text is replaced while confirmed text accumulates`() {
        val transcript = SpeechTranscript()

        assertEquals("今日", transcript.observeInterim("今日"))
        assertEquals("今日の会議", transcript.observeInterim("今日の会議"))
        assertEquals("今日の会議です。", transcript.confirm("今日の会議です。"))
        assertEquals("今日の会議です。それで", transcript.observeInterim("それで"))

        assertEquals("今日の会議です。", transcript.confirmedText())
        assertEquals("それで", transcript.interimText())
    }

    @Test
    fun `unchanged or empty updates report no change`() {
        val transcript = SpeechTranscript()

        assertNull(transcript.observeInterim(""))
        assertNull(transcript.confirm(""))
        transcript.observeInterim("今日")
        assertNull(transcript.observeInterim("今日"))
    }

    @Test
    fun `promoting interim keeps text that a reconnect would otherwise drop`() {
        val transcript = SpeechTranscript()
        transcript.confirm("最初の文。")
        transcript.observeInterim("まだ確定していない")

        transcript.promoteInterim()

        assertEquals("最初の文。まだ確定していない", transcript.confirmedText())
        assertEquals("", transcript.interimText())

        // 再接続後の認識は、昇格済みのテキストの後ろに積まれる。
        assertEquals("最初の文。まだ確定していない続き", transcript.confirm("続き"))
    }

    @Test
    fun `promoting with no interim text changes nothing`() {
        val transcript = SpeechTranscript()
        transcript.confirm("確定分")

        transcript.promoteInterim()

        assertEquals("確定分", transcript.confirmedText())
    }
}
