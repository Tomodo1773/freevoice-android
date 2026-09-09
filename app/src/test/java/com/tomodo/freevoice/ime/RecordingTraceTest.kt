package com.tomodo.freevoice.ime

import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.network.ChainSpan
import com.tomodo.freevoice.network.LangsmithConfig
import com.tomodo.freevoice.network.LlmSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingTraceTest {
    private data class Sent(val config: LangsmithConfig, val root: ChainSpan, val children: List<LlmSpan>)

    private val sent = mutableListOf<Sent>()
    private val config = LangsmithConfig(enabled = true, apiKey = "key")
    private var clock = 1_000L

    @Test
    fun `flush sends the buffered spans once, wrapped in a root`() {
        val trace = trace()
        val job = trace.begin(AppSettings())!!
        clock = 5_000L
        trace.add(job, span("transcribe", completion = "あー、てすと"))
        trace.add(job, span("format", completion = "テスト"))
        clock = 9_000L

        trace.flush("テスト")

        assertEquals(1, sent.size)
        val (_, root, children) = sent.single()
        assertEquals(listOf("transcribe", "format"), children.map { it.spanName })
        // root の input は文字起こしの生テキスト、output は実際に入力したテキスト。
        assertEquals("あー、てすと", root.input)
        assertEquals("テスト", root.output)
        // root は録音の開始から入力の確定までを覆う。
        assertEquals(1_000L, root.startTimeMs)
        assertEquals(9_000L, root.endTimeMs)
    }

    @Test
    fun `a cancelled recording sends nothing`() {
        val trace = trace()
        val job = trace.begin(AppSettings())!!
        trace.add(job, span("transcribe", completion = "あー、てすと"))

        // controller と IME の両方から来るので、何度呼んでも安全でなければならない。
        trace.discard()
        trace.discard()
        trace.flush("テスト")

        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a span from a cancelled job never lands in the next recording`() {
        val trace = trace()
        val stale = trace.begin(AppSettings())!!
        trace.discard()
        val current = trace.begin(AppSettings())!!

        // キャンセル直後に再録音すると、前のジョブの finish() が遅れて届くことがある。
        trace.add(stale, span("transcribe", completion = "古い"))
        trace.add(current, span("transcribe", completion = "新しい"))
        trace.flush("新しい")

        assertEquals(listOf("新しい"), sent.single().children.map { it.completion })
    }

    @Test
    fun `unconfigured tracing buffers nothing`() {
        val trace = RecordingTrace(configFor = { null }, send = { _, _, _ -> throw AssertionError("送ってはいけない") }, now = { clock })

        assertNull(trace.begin(AppSettings()))
        trace.add(1L, span("transcribe", completion = "あー、てすと"))
        trace.flush("テスト")
    }

    @Test
    fun `the transcription span carries the final text`() {
        val trace = trace()
        val job = trace.begin(AppSettings())!!
        val session = tracedSession(trace, job, FakeSession(result = "  あー、てすと  "))

        session.start()
        clock = 4_000L
        assertEquals("  あー、てすと  ", session.finish())
        trace.flush("テスト")

        val span = sent.single().children.single()
        assertEquals("transcribe", span.spanName)
        assertEquals("azure.ai.speech", span.system)
        assertEquals("あー、てすと", span.completion)
        assertEquals(listOf("user" to "audio_segment"), span.messages.map { it.role to it.content })
        assertEquals(1_000L, span.startTimeMs)
        assertEquals(4_000L, span.endTimeMs)
        assertNull(span.errorMessage)
    }

    @Test
    fun `a failed transcription is reported and rethrown`() {
        val trace = trace()
        val job = trace.begin(AppSettings())!!
        val session = tracedSession(trace, job, FakeSession(error = UserVisibleException("音声を認識できなかった")))

        session.start()
        val error = runCatching { session.finish() }.exceptionOrNull()
        // 失敗した録音は送らないが、送るなら中身が残っていることを確かめる。
        trace.flush("テスト")

        assertEquals("音声を認識できなかった", error?.message)
        assertEquals("音声を認識できなかった", sent.single().children.single().errorMessage)
    }

    @Test
    fun `a cancelled session leaves no span`() {
        val trace = trace()
        val job = trace.begin(AppSettings())!!
        val fake = FakeSession(result = "あー、てすと")
        val session = tracedSession(trace, job, fake)

        session.start()
        session.cancel()
        trace.flush("テスト")

        assertTrue(fake.cancelled)
        assertTrue(sent.isEmpty())
    }

    private fun trace() = RecordingTrace(
        configFor = { config },
        send = { config, root, children -> sent += Sent(config, root, children) },
        now = { clock },
    )

    private fun tracedSession(trace: RecordingTrace, job: Long, delegate: FakeSession) = TracedVoiceSession(
        delegate = delegate,
        system = "azure.ai.speech",
        model = "",
        now = { clock },
        onSpan = { span -> trace.add(job, span) },
    )

    private fun span(name: String, completion: String) = LlmSpan(
        spanName = name,
        system = "azure.openai",
        requestModel = "gpt-5.6-terra",
        completion = completion,
        startTimeMs = 1_000L,
        endTimeMs = 2_000L,
    )

    private class FakeSession(
        private val result: String = "",
        private val error: Exception? = null,
    ) : VoiceInputController.VoiceSession {
        var cancelled = false

        override fun start() = Unit

        override fun finish(): String = error?.let { throw it } ?: result

        override fun cancel() {
            cancelled = true
        }
    }
}
