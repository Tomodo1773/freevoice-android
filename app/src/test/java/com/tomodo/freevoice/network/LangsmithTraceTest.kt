package com.tomodo.freevoice.network

import com.tomodo.freevoice.data.AppSettings
import com.tomodo.freevoice.data.FormatProvider
import com.tomodo.freevoice.data.LangsmithRegion
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class LangsmithTraceTest {
    @Test
    fun `active requires enabled key and project`() {
        val full = LangsmithConfig(enabled = true, apiKey = "key", project = "freevoice")
        assertTrue(full.active)
        assertFalse(full.copy(enabled = false).active)
        assertFalse(full.copy(apiKey = "").active)
        assertFalse(full.copy(project = " ".trim()).active)
    }

    @Test
    fun `region selects the matching ingest endpoint`() {
        val config = LangsmithConfig(region = LangsmithRegion.US)
        assertEquals("https://api.smith.langchain.com/otel/v1/traces", config.endpoint)
        assertEquals("https://eu.api.smith.langchain.com/otel/v1/traces", config.copy(region = LangsmithRegion.EU).endpoint)
    }

    @Test
    fun `missing names the setting that blocks sending`() {
        val full = LangsmithConfig(enabled = true, apiKey = "key", project = "freevoice")
        assertNull(full.missing)
        assertEquals("API キー", full.copy(apiKey = "").missing)
        assertEquals("プロジェクト名", full.copy(project = "").missing)
    }

    @Test
    fun `sink stays null unless tracing is fully configured`() {
        val configured = AppSettings(langsmithEnabled = true, langsmithApiKey = "ls-key")
        withTracer { tracer, _ ->
            assertNotNull(tracer.sinkFor(configured))
            // 無効・キー未入力・プロジェクト未入力のいずれでも組み立てごと省く。
            assertNull(tracer.sinkFor(configured.copy(langsmithEnabled = false)))
            assertNull(tracer.sinkFor(configured.copy(langsmithApiKey = "")))
            assertNull(tracer.sinkFor(configured.copy(langsmithProject = "")))
            assertNull((null as LangsmithTracer?).sinkFor(configured))
        }
    }

    @Test
    fun `enabled but unconfigured tracing reports why nothing is sent`() {
        val configured = AppSettings(langsmithEnabled = true, langsmithApiKey = "ls-key")
        withTracer { tracer, failures ->
            tracer.sinkFor(configured.copy(langsmithApiKey = ""))
            tracer.sinkFor(configured.copy(langsmithProject = ""))
            assertEquals(2, failures.size)
            assertTrue(failures[0].contains("API キー"))
            assertTrue(failures[1].contains("プロジェクト名"))
        }
    }

    @Test
    fun `disabled tracing stays silent`() {
        withTracer { tracer, failures ->
            tracer.sinkFor(AppSettings(langsmithEnabled = false, langsmithApiKey = ""))
            assertTrue(failures.isEmpty())
        }
    }

    @Test
    fun `trace and span ids are hex of the length OTLP requires`() {
        val random = Random(42)
        val traceId = randomHex(random, 16)
        val spanId = randomHex(random, 8)

        assertEquals(32, traceId.length)
        assertEquals(16, spanId.length)
        assertTrue(traceId, traceId.matches(Regex("[0-9a-f]{32}")))
        assertTrue(spanId, spanId.matches(Regex("[0-9a-f]{16}")))
        // 負のバイトが 1 文字に潰れないこと（全バイトが 2 桁に展開される）。
        assertEquals(64, randomHex(Random(7), 32).length)
    }

    private fun withTracer(block: (LangsmithTracer, List<String>) -> Unit) {
        val failures = mutableListOf<String>()
        val tracer = LangsmithTracer(onFailure = { failures += it })
        try {
            block(tracer, failures)
        } finally {
            tracer.close()
        }
    }

    @Test
    fun `successful span carries model usage and ok status`() {
        val payload = buildLlmSpanPayload(span(), project = "freevoice", includeContent = false, traceId = "aa", spanId = "bb")

        val span = payload.span()
        assertEquals("format", span.getString("name"))
        assertEquals("aa", span.getString("traceId"))
        assertEquals("bb", span.getString("spanId"))
        assertEquals(3, span.getInt("kind"))
        assertEquals("1000000000", span.getString("startTimeUnixNano"))
        assertEquals("1500000000", span.getString("endTimeUnixNano"))
        assertEquals(1, span.getJSONObject("status").getInt("code"))
        assertEquals(0, span.getJSONArray("events").length())

        val attributes = span.attributes()
        assertEquals("azure.openai", attributes.getValue("gen_ai.system"))
        assertEquals("chat", attributes.getValue("gen_ai.operation.name"))
        assertEquals("gpt-5.6-terra", attributes.getValue("gen_ai.request.model"))
        assertEquals("low", attributes.getValue("gen_ai.request.reasoning_effort"))
        assertEquals("format", attributes.getValue("freevoice.operation"))
        assertEquals("gpt-5.6-terra-2026", attributes.getValue("gen_ai.response.model"))
        // OTLP/HTTP JSON では int64 を文字列でエンコードする。
        assertEquals("120", attributes.getValue("gen_ai.usage.input_tokens", "intValue"))
        assertEquals("34", attributes.getValue("gen_ai.usage.output_tokens", "intValue"))

        val resource = payload.getJSONArray("resourceSpans").getJSONObject(0)
            .getJSONObject("resource").getJSONArray("attributes")
        assertEquals("freevoice", resource.getValue("service.name"))
        assertEquals("freevoice", resource.getValue("langsmith.project"))
    }

    @Test
    fun `include content adds prompts and completion`() {
        val payload = buildLlmSpanPayload(span(), project = "p", includeContent = true, traceId = "aa", spanId = "bb")

        val attributes = payload.span().attributes()
        assertEquals("system", attributes.getValue("gen_ai.prompt.0.role"))
        assertEquals("校正して", attributes.getValue("gen_ai.prompt.0.content"))
        assertEquals("user", attributes.getValue("gen_ai.prompt.1.role"))
        assertEquals("<校正対象>あー、てすと</校正対象>", attributes.getValue("gen_ai.prompt.1.content"))
        assertEquals("assistant", attributes.getValue("gen_ai.completion.0.role"))
        assertEquals("テスト", attributes.getValue("gen_ai.completion.0.content"))
    }

    @Test
    fun `excluding content keeps metadata only`() {
        val attributes = buildLlmSpanPayload(span(), "p", includeContent = false, traceId = "aa", spanId = "bb")
            .span().attributes()

        assertNull(attributes.getValue("gen_ai.prompt.0.content"))
        assertNull(attributes.getValue("gen_ai.prompt.1.content"))
        assertNull(attributes.getValue("gen_ai.completion.0.content"))
        assertEquals("gpt-5.6-terra", attributes.getValue("gen_ai.request.model"))
    }

    @Test
    fun `error span reports status and exception event`() {
        val failed = span().copy(
            completion = null,
            responseModel = null,
            inputTokens = null,
            outputTokens = null,
            errorMessage = "HTTP 429",
            errorStatus = 429,
        )

        val span = buildLlmSpanPayload(failed, "p", includeContent = true, traceId = "aa", spanId = "bb").span()

        assertEquals(2, span.getJSONObject("status").getInt("code"))
        assertEquals("HTTP 429", span.getJSONObject("status").getString("message"))
        val event = span.getJSONArray("events").getJSONObject(0)
        assertEquals("exception", event.getString("name"))
        assertEquals("1500000000", event.getString("timeUnixNano"))
        assertEquals("HTTP 429", event.getJSONArray("attributes").getValue("exception.type"))
        assertEquals("HTTP 429", event.getJSONArray("attributes").getValue("exception.message"))
        // 応答が無いので usage 属性は付かない。
        assertNull(span.attributes().getValue("gen_ai.usage.input_tokens", "intValue"))
    }

    @Test
    fun `error without http status falls back to generic type`() {
        val failed = span().copy(errorMessage = "timeout", errorStatus = null)

        val event = buildLlmSpanPayload(failed, "p", includeContent = false, traceId = "aa", spanId = "bb")
            .span().getJSONArray("events").getJSONObject(0)

        assertEquals("Error", event.getJSONArray("attributes").getValue("exception.type"))
    }

    @Test
    fun `openai provider maps to openai system`() {
        val attributes = buildLlmSpanPayload(
            span().copy(provider = FormatProvider.OPENAI), "p", includeContent = false, traceId = "aa", spanId = "bb",
        ).span().attributes()

        assertEquals("openai", attributes.getValue("gen_ai.system"))
    }

    @Test
    fun `gemini provider maps to gcp gemini system`() {
        val attributes = buildLlmSpanPayload(
            span().copy(provider = FormatProvider.GEMINI), "p", includeContent = false, traceId = "aa", spanId = "bb",
        ).span().attributes()

        assertEquals("gcp.gemini", attributes.getValue("gen_ai.system"))
    }

    @Test
    fun `distill span keeps its own operation name`() {
        val attributes = buildLlmSpanPayload(
            span().copy(spanName = "distill"), "p", includeContent = false, traceId = "aa", spanId = "bb",
        ).span().attributes()

        assertEquals("distill", attributes.getValue("freevoice.operation"))
    }

    private fun span() = LlmSpan(
        spanName = "format",
        provider = FormatProvider.AZURE,
        requestModel = "gpt-5.6-terra",
        responseModel = "gpt-5.6-terra-2026",
        messages = listOf(ChatMessage("system", "校正して"), ChatMessage("user", "<校正対象>あー、てすと</校正対象>")),
        completion = "テスト",
        reasoningEffort = "low",
        inputTokens = 120,
        outputTokens = 34,
        startTimeMs = 1_000L,
        endTimeMs = 1_500L,
    )

    private fun JSONObject.span(): JSONObject = getJSONArray("resourceSpans").getJSONObject(0)
        .getJSONArray("scopeSpans").getJSONObject(0)
        .getJSONArray("spans").getJSONObject(0)

    private fun JSONObject.attributes(): JSONArray = getJSONArray("attributes")

    private fun JSONArray.getValue(key: String, type: String = "stringValue"): String? =
        (0 until length()).map { getJSONObject(it) }
            .firstOrNull { it.getString("key") == key }
            ?.getJSONObject("value")
            ?.optString(type)
            ?.takeIf { it.isNotEmpty() }
}
