package com.tomodo.freevoice.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechCancellationTest {
    private fun error(code: String) = SpeechCancellation(SpeechCancelReason.ERROR, code)

    @Test
    fun `transient service failures are retried`() {
        listOf("ConnectionFailure", "ServiceTimeout", "ServiceError", "RuntimeError").forEach {
            assertTrue(it, error(it).isRetryable())
        }
    }

    @Test
    fun `end of stream mid-recording is retried`() {
        // マイクは停止するまで終端しないので、録音中の EndOfStream は
        // サービス側が切ったことを意味する。握り潰すと途中から認識されなくなる。
        assertTrue(SpeechCancellation(SpeechCancelReason.END_OF_STREAM, "NoError").isRetryable())
    }

    @Test
    fun `configuration and auth failures are not retried`() {
        listOf("AuthenticationFailure", "Forbidden", "BadRequest", "TooManyRequests").forEach {
            assertFalse(it, error(it).isRetryable())
        }
        assertFalse(SpeechCancellation(SpeechCancelReason.BY_USER, "NoError").isRetryable())
    }

    @Test
    fun `service unavailable follows the windows build and is not retried`() {
        assertFalse(error("ServiceUnavailable").isRetryable())
    }

    @Test
    fun `messages point at what the user can fix`() {
        assertEquals("API キーまたは権限を確認して", error("AuthenticationFailure").userMessage())
        assertEquals("API キーまたは権限を確認して", error("Forbidden").userMessage())
        assertEquals("音声認識サービスに接続できなかった。通信を確認して", error("ConnectionFailure").userMessage())
        assertEquals("Speech エンドポイントまたは言語設定を確認して", error("BadRequest").userMessage())
        assertEquals("API が混み合っている。少し待って再試行して", error("TooManyRequests").userMessage())
        assertEquals("音声認識が中断された", error("SomethingElse").userMessage())
    }
}
