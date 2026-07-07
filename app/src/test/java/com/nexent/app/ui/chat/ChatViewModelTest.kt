package com.nexent.app.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatViewModelTest {

    @Test
    fun sanitizeContent_removesStepMarkerNoiseFromStreamingOutput() {
        val text = "\\u003c步骤1\\u003e 先做基础判断\n步骤2：再给出答案"

        val sanitized = ChatViewModel.sanitizeContent(text)

        assertFalse("should not keep raw step marker tags", sanitized.contains("<步骤1>"))
        assertFalse("should not keep unicode-escaped step marker tags", sanitized.contains("\\u003c步骤1\\u003e"))
        assertFalse("should not keep numbered step prefixes", sanitized.contains("步骤2："))
        assertTrue("should keep the actual reasoning text", sanitized.contains("先做基础判断"))
        assertTrue("should keep the actual answer text", sanitized.contains("再给出答案"))
    }

    @Test
    fun sanitizeContent_removesMetadataLinesFromStreamingOutput() {
        val text = "\\u007b\\\"step_number\\\":1,\\\"token_threshold\\\":1024\\u007d\n这是一段真实回答"

        val sanitized = ChatViewModel.sanitizeContent(text)

        assertFalse("should remove metadata JSON lines", sanitized.contains("step_number"))
        assertFalse("should remove token threshold metadata", sanitized.contains("token_threshold"))
        assertTrue("should keep the real answer", sanitized.contains("这是一段真实回答"))
    }

    @Test
    fun sanitizeContent_convertsEscapedNewlinesToRealLineBreaks() {
        val text = "先输出第一行\\n第二行\\r\\n第三行"

        val sanitized = ChatViewModel.sanitizeContent(text)

        assertFalse("should not keep literal \\n escape", sanitized.contains("\\n"))
        assertTrue("should preserve real line breaks", sanitized.contains("\n"))
        assertTrue("should preserve the expected text", sanitized.contains("第一行"))
        assertTrue("should preserve the expected text", sanitized.contains("第二行"))
        assertTrue("should preserve the expected text", sanitized.contains("第三行"))
    }

    @Test
    fun sanitizeContent_removesPhaseEvaluationPayloadsFromStreamingOutput() {
        val text = "先给出结论{\"phase\":12345}后继续回答"

        val sanitized = ChatViewModel.sanitizeContent(text)

        assertFalse("should remove phase evaluation payloads", sanitized.contains("{\"phase\":12345}"))
        assertTrue("should keep the surrounding answer text", sanitized.contains("先给出结论"))
        assertTrue("should keep the remainder after the payload", sanitized.contains("后继续回答"))
    }

    @Test
    fun isAudioMimeType_recognizesCommonVoiceFormats() {
        assertTrue(ChatViewModel.isAudioMimeType("audio/mpeg"))
        assertTrue(ChatViewModel.isAudioMimeType("audio/wav"))
        assertTrue(ChatViewModel.isAudioMimeType("audio/x-m4a"))
        assertFalse(ChatViewModel.isAudioMimeType("image/jpeg"))
        assertFalse(ChatViewModel.isAudioMimeType(null))
    }

    @Test
    fun createSpeechRecognitionConfig_setsUpChineseVoiceInput() {
        val config = ChatActivity.createSpeechRecognitionConfig()

        assertTrue(config.language == "zh-CN")
        assertTrue(config.prompt == "说出你想问的问题...")
        assertTrue(config.maxResults == 1)
    }
}
