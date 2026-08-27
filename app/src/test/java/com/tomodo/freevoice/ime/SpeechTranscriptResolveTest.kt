package com.tomodo.freevoice.ime

import com.tomodo.freevoice.diag.DiagLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 停止時に何を返すかの規則。Azure Speech と Gemini Live が共有する。 */
class SpeechTranscriptResolveTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val logger by lazy { DiagLogger(temporaryFolder.newFile()) }

    private fun transcript(confirmed: List<String> = emptyList(), interim: String = "") =
        SpeechTranscript().apply {
            confirmed.forEach { confirm(it) }
            observeInterim(interim)
        }

    @Test fun `confirmed text wins`() {
        assertEquals("確定", transcript(confirmed = listOf("確定"), interim = "とちゅ").resolve(null, logger, "test"))
    }

    /** 発話中に止めると確定が届かない端末がある。黙って空を返さない。 */
    @Test fun `interim is used when nothing was confirmed`() {
        assertEquals("とちゅ", transcript(interim = "とちゅ").resolve(null, logger, "test"))
    }

    /** 中断していても、届いた分は捨てずに入力する。 */
    @Test fun `confirmed text is salvaged even after an abort`() {
        assertEquals("確定", transcript(confirmed = listOf("確定")).resolve("接続が切れた", logger, "test"))
    }

    @Test fun `an abort with nothing to salvage is reported to the user`() {
        val error = runCatching { transcript().resolve("接続が切れた", logger, "test") }.exceptionOrNull()
        assertTrue(error is UserVisibleException)
        assertEquals("接続が切れた", error?.message)
    }

    @Test fun `silence is empty text, not a failure`() {
        assertEquals("", transcript().resolve(null, logger, "test"))
    }

    @Test fun `salvage and fallback leave a trace to explain the result`() {
        transcript(confirmed = listOf("確定")).resolve("接続が切れた", logger, "test")
        transcript(interim = "とちゅ").resolve(null, logger, "test")

        val log = logger.read()
        assertTrue(log.contains("salvaged"))
        assertTrue(log.contains("interim fallback"))
    }
}
