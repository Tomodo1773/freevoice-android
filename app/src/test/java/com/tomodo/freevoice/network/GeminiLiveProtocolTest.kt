package com.tomodo.freevoice.network

import com.tomodo.freevoice.network.GeminiLiveProtocol.ServerEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Base64

class GeminiLiveProtocolTest {
    private fun setup(model: String = "gemini-3.5-transcribe-live", language: String = "ja-JP") =
        JSONObject(GeminiLiveProtocol.setup(model, language)).getJSONObject("setup")

    @Test fun `setup names the model, the language and text output`() {
        val setup = setup()
        assertEquals("models/gemini-3.5-transcribe-live", setup.getString("model"))
        assertEquals("TEXT", setup.getJSONObject("generationConfig").getJSONArray("responseModalities").getString(0))
        val transcription = setup.getJSONObject("inputAudioTranscription")
        assertEquals("ja-JP", transcription.getJSONArray("languageCodes").getString(0))
    }

    @Test fun `setup keeps filler words for the formatting model to handle`() {
        assertEquals("VERBATIM", setup().getJSONObject("inputAudioTranscription").getString("mode"))
    }

    /** 沈黙でターンを切られると1回の入力が分断される。 */
    @Test fun `setup disables automatic activity detection`() {
        val detection = setup().getJSONObject("realtimeInputConfig").getJSONObject("automaticActivityDetection")
        assertEquals(true, detection.getBoolean("disabled"))
    }

    @Test fun `setup does not prefix a model that is already qualified`() {
        assertEquals("models/gemini-3.5-transcribe-live", setup(model = "models/gemini-3.5-transcribe-live").getString("model"))
    }

    @Test fun `audio chunk sends only the recorded bytes`() {
        val buffer = byteArrayOf(1, 2, 3, 4, 5, 6)
        val audio = JSONObject(GeminiLiveProtocol.audioChunk(buffer, 4)).getJSONObject("realtimeInput").getJSONObject("audio")
        assertEquals("audio/pcm;rate=16000", audio.getString("mimeType"))
        assertEquals(listOf<Byte>(1, 2, 3, 4), Base64.getDecoder().decode(audio.getString("data")).toList())
    }

    @Test fun `activity signals bracket the utterance`() {
        assertEquals(
            true,
            JSONObject(GeminiLiveProtocol.activityStart()).getJSONObject("realtimeInput").has("activityStart"),
        )
        assertEquals(
            true,
            JSONObject(GeminiLiveProtocol.activityEnd()).getJSONObject("realtimeInput").has("activityEnd"),
        )
    }

    @Test fun `setup completion is recognised`() {
        assertEquals(ServerEvent.SetupComplete, GeminiLiveProtocol.parse("""{"setupComplete":{}}"""))
    }

    @Test fun `final and interim transcriptions are told apart`() {
        assertEquals(
            ServerEvent.Final("確定した文"),
            GeminiLiveProtocol.parse("""{"serverContent":{"inputTranscription":{"text":"確定した文"}}}"""),
        )
        assertEquals(
            ServerEvent.Interim("とちゅ"),
            GeminiLiveProtocol.parse("""{"serverContent":{"interimInputTranscription":{"text":"とちゅ"}}}"""),
        )
    }

    /** 同じメッセージに両方載っていたら、確定のほうが新しい。 */
    @Test fun `a final wins over an interim in the same message`() {
        assertEquals(
            ServerEvent.Final("確定"),
            GeminiLiveProtocol.parse(
                """{"serverContent":{"interimInputTranscription":{"text":"かく"},"inputTranscription":{"text":"確定"}}}""",
            ),
        )
    }

    @Test fun `everything else is ignored instead of throwing`() {
        assertEquals(ServerEvent.Ignored, GeminiLiveProtocol.parse("""{"serverContent":{"turnComplete":true}}"""))
        assertEquals(ServerEvent.Ignored, GeminiLiveProtocol.parse("""{"serverContent":{"inputTranscription":{"text":""}}}"""))
        assertEquals(ServerEvent.Ignored, GeminiLiveProtocol.parse("""{"goAway":{}}"""))
        assertEquals(ServerEvent.Ignored, GeminiLiveProtocol.parse("not json"))
    }
}
