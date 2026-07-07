package com.nexent.app.ui.chat

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import java.util.Locale
import java.net.URLDecoder
import java.net.URI
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.nexent.app.data.model.ChatRequest
import com.nexent.app.data.model.ChatStreamChunk
import com.nexent.app.data.network.RetrofitClient
import com.nexent.app.util.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import android.util.Log
import android.os.Build

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val content: String,
    val thinkingContent: String = "",
    val isUser: Boolean,
    val imageUri: String? = null,
    val isStreaming: Boolean = false,
    val isDeepThink: Boolean = false
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefHelper = PreferenceHelper(application)

    private val _messages = MutableLiveData<List<ChatMessage>>(emptyList())
    val messages: LiveData<List<ChatMessage>> = _messages

    private val _isLoading = MutableLiveData<Boolean>(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var conversationId: Int = 1
    private var currentAgentName: String = ""
    private var deepThinkEnabled: Boolean = false

    private val _thinkMode = MutableLiveData<String>("quick")
    val thinkMode: LiveData<String> = _thinkMode

    fun init(agentName: String) {
        currentAgentName = agentName
    }

    fun setThinkMode(mode: String) {
        _thinkMode.value = mode
        deepThinkEnabled = mode == "deep"
    }

    fun sendMessage(text: String, attachments: List<String>? = null, attachmentsMeta: List<Map<String, String>>? = null) {
        if (text.isBlank()) return

        viewModelScope.launch(Dispatchers.Main) {
            val currentMessages = _messages.value.orEmpty().toMutableList()
            currentMessages.add(ChatMessage(content = text, isUser = true))
            _messages.value = currentMessages.toList()

            val baseUrl = prefHelper.baseUrl
            val request = ChatRequest(
                conversationId = conversationId,
                agentName = currentAgentName,
                query = text,
                deepThink = deepThinkEnabled,
                attachments = attachments
            )

            _isLoading.value = true
            _error.value = null

            // Add a placeholder AI message for streaming with ellipsis
            val aiMsg = ChatMessage(
                content = "…",
                isUser = false,
                isStreaming = true,
                isDeepThink = deepThinkEnabled
            )
            val updated = _messages.value.orEmpty().toMutableList()
            updated.add(aiMsg)
            _messages.value = updated.toList()

            val fullRaw = StringBuilder()
            var finalApplied = false
            var separationLocked = false
            var cachedThinkPart = ""
            var chunkCount = 0
            val separationInterval = 3

            try {
                RetrofitClient.streamChat(baseUrl, request, prefHelper.apikey)
                    .flowOn(Dispatchers.IO)
                    .collect { chunk ->
                        // Only sanitize the new chunk content, not the entire accumulated text
                        val chunkText = sanitizeContent(chunk.content)
                        fullRaw.append(chunkText)
                        chunk.conversationId?.let { conversationId = it }
                        chunkCount++

                        if (!separationLocked && (chunk.done || chunkCount % separationInterval == 0)) {
                            // Incremental separation: only run every separationInterval chunks or when done
                            val currentFull = fullRaw.toString()
                            val (thinkPart, answerPart) = separateThinkingAnswer(currentFull, deepThinkEnabled)

                            // Lock separation once we have a clear think/answer split
                            if (answerPart.isNotBlank() && thinkPart.isNotBlank()) {
                                separationLocked = true
                                cachedThinkPart = thinkPart
                            }

                            updateStreamingMessage(answerPart.ifBlank { "…" }, thinkPart)
                        } else if (separationLocked) {
                            // Separation is locked - only update the answer part incrementally
                            val currentFull = fullRaw.toString()
                            val (_, answerPart) = separateThinkingAnswer(currentFull, deepThinkEnabled)
                            updateStreamingMessage(answerPart.ifBlank { "…" }, cachedThinkPart)
                        }

                        if (chunk.done) {
                            applyFinalSeparation(fullRaw)
                            finalApplied = true
                            return@collect
                        }
                    }

                if (!finalApplied) {
                    applyFinalSeparation(fullRaw)
                }
            } catch (e: Exception) {
                // Keep partial content on error instead of removing it entirely
                Log.e("ChatViewModel", "streamChat error", e)
                handleStreamError(fullRaw, e)
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun updateStreamingMessage(content: String, thinkingContent: String) {
        val msgs = _messages.value.orEmpty().toMutableList()
        val lastIdx = msgs.lastIndex
        if (lastIdx >= 0 && msgs[lastIdx].isStreaming) {
            msgs[lastIdx] = msgs[lastIdx].copy(
                content = content,
                thinkingContent = thinkingContent
            )
            _messages.value = msgs.toList()
        }
    }

    private fun handleStreamError(fullRaw: StringBuilder, e: Exception) {
        val errMsgs = _messages.value.orEmpty().toMutableList()
        if (errMsgs.isNotEmpty() && errMsgs.last().isStreaming) {
            val partialContent = fullRaw.toString().trim()
            if (partialContent.isNotBlank()) {
                val (think, answer) = separateThinkingAnswer(partialContent, deepThinkEnabled)
                errMsgs[errMsgs.lastIndex] = errMsgs.last().copy(
                    content = answer.ifBlank { partialContent },
                    thinkingContent = think,
                    isStreaming = false
                )
            } else {
                errMsgs.removeLast()
            }
            _messages.value = errMsgs.toList()
        }
            // Ensure we surface a useful message and also log full stack for debugging
            val msg = e.message ?: e::class.java.name
            _error.value = "发送失败: $msg"
            Log.e("ChatViewModel", "handleStreamError: $msg\n${e.stackTraceToString()}")
    }

    fun sendImageMessage(imageUri: Uri, description: String) {
        uploadAttachmentAndSendMessage(imageUri, description, isVoice = false)
    }

    fun sendVoiceMessage(audioUri: Uri, description: String) {
        uploadAttachmentAndSendMessage(audioUri, description, isVoice = true)
    }

    private fun uploadAttachmentAndSendMessage(uri: Uri, description: String, isVoice: Boolean) {
        val currentMessages = _messages.value.orEmpty().toMutableList()
        val placeholderText = description.ifBlank {
            if (isVoice) "[语音]" else "[图片]"
        }
        currentMessages.add(
            ChatMessage(
                content = placeholderText,
                isUser = true,
                imageUri = uri.toString()
            )
        )
        _messages.value = currentMessages.toList()

        viewModelScope.launch(Dispatchers.IO) {
            _isLoading.postValue(true)
            _error.postValue(null)
            try {
                val resolver = getApplication<Application>().contentResolver
                val input = resolver.openInputStream(uri) ?: throw Exception(if (isVoice) "无法打开语音文件" else "无法打开图片")
                val bytes = input.use { it.readBytes() }
                val mime = resolver.getType(uri) ?: guessMimeType(uri)
                val isVoiceAttachment = isVoice || isAudioMimeType(mime)
                val name = queryFileName(uri) ?: (if (isVoiceAttachment) "voice.mp3" else "image.jpg")

                val uploadedUrl = RetrofitClient.uploadAttachment(
                    prefHelper.baseUrl,
                    name,
                    mime ?: "application/octet-stream",
                    bytes,
                    prefHelper.apikey
                )
                Log.d("ChatViewModel", "uploadAttachment result: $uploadedUrl, filename=$name, mime=$mime")

                    if (uploadedUrl == null) {
                    _error.postValue(if (isVoice) "语音上传失败" else "图片上传失败")
                } else {
                            // extract presigned_url from the returned URL and use it as the attachment URL
                            val meta = extractAttachmentMetadata(uploadedUrl)
                                Log.d("ChatViewModel", "attachment meta: $meta")
                                val backendPath = meta["backend_path"]
                                val presigned = meta["presigned_url"]

                                // Prefer backend-friendly path (e.g. /nexent/attachments/...), then presigned URL, then the raw returned URL
                                val attachmentToSend = when {
                                    !backendPath.isNullOrBlank() -> backendPath
                                    !presigned.isNullOrBlank() -> presigned
                                    else -> uploadedUrl
                                }
                    val prompt = description.ifBlank {
                        if (isVoiceAttachment) "请分析这段语音" else "请分析这张图片"
                    }

                    // send the presigned URL (decoded) if available, otherwise fallback to uploadedUrl
                    sendMessage(prompt, listOf(attachmentToSend))
                }
            } catch (e: Exception) {
                _error.postValue(if (isVoice) "语音上传失败: ${e.message}" else "图片上传失败: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            val cursor = getApplication<Application>().contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null
            )
            cursor?.use {
                if (it.moveToFirst()) {
                    return it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        return uri.lastPathSegment
    }

    /**
     * Parse the uploaded URL returned by the API and extract useful metadata.
     * Handles cases where the response encodes the presigned URL (e.g. contains "presigned_url\u003d...").
     */
    private fun extractAttachmentMetadata(uploadedUrl: String): Map<String, String> {
        // Simplified: only extract the presigned_url value (decoded) and return it
        try {
            // Normalize common JSON-escaped sequences
            val normalized = uploadedUrl.replace("\\u003d", "=").replace("\\u0026", "&")

            // Try decode then extract presigned_url param
            val decoded = try {
                URLDecoder.decode(normalized, "UTF-8")
            } catch (e: Exception) {
                normalized
            }

            val presigned = Regex("presigned_url=([^&]+)").find(decoded)?.groups?.get(1)?.value
                ?: Regex("presigned_url(?:=|\\u003d)([^&]+)").find(normalized)?.groups?.get(1)?.value
                ?: ""

            val presignedDecoded = try {
                if (presigned.isNotBlank()) URLDecoder.decode(presigned, "UTF-8") else ""
            } catch (e: Exception) {
                presigned
            }

            // Try to derive a backend-friendly path such as:
            // 1) s3://bucket/attachments/xxx
            // 2) /bucket/attachments/xxx
            // 3) bucket/attachments/xxx (relative)
            var backendPath = ""

            // If presignedDecoded contains an s3:// URL, keep it (format #1)
            Regex("s3://[^\\s/\"]+/attachments/[^?\\s\"]+").find(presignedDecoded)?.let {
                backendPath = it.value
            }

            // Otherwise try to extract an http path like /bucket/attachments/...
            if (backendPath.isBlank() && presignedDecoded.isNotBlank()) {
                try {
                    val uri = URI(presignedDecoded)
                    val path = uri.rawPath ?: ""
                    if (path.isNotBlank()) {
                        // If the path contains "/attachments/", prefer the subpath starting at the bucket
                        val attachmentsIdx = path.indexOf("/attachments/")
                        if (attachmentsIdx >= 0) {
                            // include the bucket segment before /attachments
                            val startIdx = path.lastIndexOf('/', attachmentsIdx - 1).let { if (it >= 0) it else 0 }
                            backendPath = path.substring(startIdx)
                        } else {
                            // fallback: if path contains "/bucket/attachments/" pattern (bucket first)
                            val m = Regex("/[^/]+/attachments/[^?\\s]+").find(path)
                            if (m != null) backendPath = m.value
                            else if (path.contains("attachments/")) {
                                // find attachments/ and return the remainder as relative
                                val rel = path.substringAfter("attachments/")
                                backendPath = "attachments/" + rel
                            } else {
                                backendPath = path
                            }
                        }
                    }
                } catch (e: Exception) {
                    // ignore and fallback to pattern matching below
                }
            }

            // If still blank, try regex on decoded string to find /bucket/attachments/... or bucket/attachments/...
            if (backendPath.isBlank()) {
                Regex("(/[^\\s?/]+/attachments/[^?\\s]+)").find(presignedDecoded)?.let { backendPath = it.groupValues[1] }
            }
            if (backendPath.isBlank()) {
                Regex("([^\\s?/]+/attachments/[^?\\s]+)").find(presignedDecoded)?.let { backendPath = it.groupValues[1] }
                if (backendPath.isNotBlank() && !backendPath.startsWith("/")) backendPath = "/$backendPath"
            }

            return mapOf(
                "presigned_url" to presignedDecoded,
                "backend_path" to backendPath
            )
        } catch (e: Exception) {
            return mapOf("presigned_url" to "", "backend_path" to "")
        }
    }

    private fun guessMimeType(uri: Uri): String? {
        val extension = MimeTypeMap.getFileExtensionFromUrl(uri.toString())?.lowercase(Locale.getDefault())
        return if (extension.isNullOrBlank()) null else MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

    private fun applyFinalSeparation(fullRaw: StringBuilder) {
        val finalMsgs = _messages.value.orEmpty().toMutableList()
        val last = finalMsgs.lastIndex
        if (last < 0 || !finalMsgs[last].isStreaming) return

        val fullText = fullRaw.toString()
        val (think, answer) = separateThinkingAnswer(fullText, deepThinkEnabled)
        finalMsgs[last] = finalMsgs[last].copy(
            content = answer.ifBlank { fullText },
            thinkingContent = think,
            isStreaming = false
        )
        _messages.value = finalMsgs.toList()
    }

    companion object {
        // MCP/tool call markers - extracted into thinkingContent instead of removed
        private val MCP_LINE_PATTERN = Regex("(?mi)^\\s*(MCP_START|MCP_END|MCP_CALL|TOOL_START|TOOL_END).*$")
        private val TOOL_CALL_BLOCK_PATTERN = Regex("<(tool_call|function_call)>.*?</\\1>", RegexOption.DOT_MATCHES_ALL)
        private val MCP_BRACKET_PATTERN = Regex("\\[MCP_.*?\\]", RegexOption.IGNORE_CASE)
        private val META_JSON_LINE_PATTERN = Regex("(?m)^\\s*\\{.*?(step_number|token_threshold|token_limit|reasoning).*$")
        private val STEP_MARKER_PREFIX_PATTERN = Regex("(?m)^\\s*(<步骤\\d+>|步骤\\d+[:：.]?)\\s*")
        private val PHASE_PAYLOAD_PATTERN = Regex("""\{\s*["“”']phase["“”']\s*:\s*[^{}]*\}""", RegexOption.DOT_MATCHES_ALL)
        private val BLANK_LINE_PATTERN = Regex("(?m)^\\s*$\\n?", RegexOption.MULTILINE)
        private val UNICODE_ESCAPE_PATTERN = Regex("\\\\u([0-9a-fA-F]{4})")

        // Thinking/answer structural markers (in priority order)
        private val THINK_TAG_REGEX = Regex("<think>(.*?)</think>", RegexOption.DOT_MATCHES_ALL)
        private val THINKING_TAG_REGEX = Regex("<thinking>(.*?)</thinking>", RegexOption.DOT_MATCHES_ALL)
        private val THINK_BLOCK_REGEX = Regex("```thinking\\s*\\n?(.*?)\\n?```", RegexOption.DOT_MATCHES_ALL)
        private val THINK_BRACKET_REGEX = Regex("\\[think\\](.*?)\\[/think\\]", RegexOption.DOT_MATCHES_ALL)
        private val SEPARATOR_LINE_REGEX = Regex("(?m)^[-=]{3,}\\s*$")
        private val MARKDOWN_THINK_HEADER = Regex("###\\s*思考过程.*", RegexOption.IGNORE_CASE)
        private val MARKDOWN_ANSWER_HEADER = Regex("###\\s*回答.*", RegexOption.IGNORE_CASE)

        fun isAudioMimeType(contentType: String?): Boolean {
            if (contentType.isNullOrBlank()) return false
            return contentType.lowercase(Locale.getDefault()).startsWith("audio/")
        }

        private fun isProbablyEmulator(): Boolean {
            return (Build.FINGERPRINT.startsWith("generic")
                    || Build.FINGERPRINT.lowercase(Locale.getDefault()).contains("vbox")
                    || Build.FINGERPRINT.lowercase(Locale.getDefault()).contains("test-keys")
                    || Build.MODEL.contains("Emulator")
                    || Build.MODEL.contains("Android SDK built for x86")
                    || Build.MANUFACTURER.contains("Genymotion")
                    || Build.BRAND.startsWith("generic")
                    || Build.DEVICE.startsWith("generic"))
        }

        /** Clean up stream output - normalize escapes, remove blank lines */
        fun sanitizeContent(text: String): String {
            // Fix escape order: process \\\\ before \\n to avoid double-escaping issues
            val normalized = text
                .replace("\\\\", "\\")  // \\ → \ (must be first to prevent \\n from being consumed by \\n rule)
                .replace("\\r\\n", "\n")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\"", "\"")
                .replace(UNICODE_ESCAPE_PATTERN) { match ->
                    match.groupValues[1].toInt(16).toChar().toString()
                }

            // Remove noisy stream markers and metadata payloads before rendering
            val cleaned = normalized
                .replace(PHASE_PAYLOAD_PATTERN, "")
                .replace(META_JSON_LINE_PATTERN, "")
                .lines()
                .joinToString("\n") { line ->
                    line.replace(STEP_MARKER_PREFIX_PATTERN, "")
                }

            return cleaned
                .replace(BLANK_LINE_PATTERN, "")
                .trim()
        }

        /**
         * Extract MCP/tool call markers from text into thinking content.
         * Returns (markerText, cleanedText).
         * Step markers (<步骤N>, 步骤N:) are NOT extracted - they remain for structural separation.
         */
        private fun extractMCPMarkers(text: String): Pair<String, String> {
            val markers = mutableListOf<String>()
            var cleaned = text

            // Extract MCP/TOOL line markers (e.g. "MCP_START search", "TOOL_END calc")
            val lineMatches = MCP_LINE_PATTERN.findAll(cleaned).toList()
            for (match in lineMatches) {
                markers.add(match.value.trim())
            }
            cleaned = cleaned.replace(MCP_LINE_PATTERN, "")

            // Extract <tool_call>...</tool_call> and <function_call>...</function_call> blocks
            val blockMatches = TOOL_CALL_BLOCK_PATTERN.findAll(cleaned).toList()
            for (match in blockMatches) {
                markers.add(match.value.trim())
            }
            cleaned = cleaned.replace(TOOL_CALL_BLOCK_PATTERN, "")

            // Extract [MCP_*] bracket markers
            val bracketMatches = MCP_BRACKET_PATTERN.findAll(cleaned).toList()
            for (match in bracketMatches) {
                markers.add(match.value.trim())
            }
            cleaned = cleaned.replace(MCP_BRACKET_PATTERN, "")

            // Extract metadata JSON lines (step_number, token_threshold, etc.)
            val metaMatches = META_JSON_LINE_PATTERN.findAll(cleaned).toList()
            for (match in metaMatches) {
                markers.add(match.value.trim())
            }
            cleaned = cleaned.replace(META_JSON_LINE_PATTERN, "")

            return Pair(
                markers.joinToString("\n"),
                cleaned.replace(BLANK_LINE_PATTERN, "").trim()
            )
        }

        /**
         * Separate thinking content from answer using structural markers.
         * Returns (thinkingContent, answerContent).
         *
         * Processing order:
         * 0. Extract MCP/tool call markers into thinkingContent
         * 1-8. Structural separation on remaining text
         */
        fun separateThinkingAnswer(fullText: String, isDeepThink: Boolean): Pair<String, String> {
            if (fullText.isBlank()) return Pair("", fullText)

            // Step 0: Extract MCP/tool call markers into thinking content
            val (mcpMarkers, textWithoutMCP) = extractMCPMarkers(fullText)

            // Run structural separation on text without MCP markers
            val (thinkFromStructure, answer) = separateByStructure(textWithoutMCP, isDeepThink)

            // Combine MCP markers with structural thinking content
            val combinedThink = when {
                mcpMarkers.isNotBlank() && thinkFromStructure.isNotBlank() ->
                    "$mcpMarkers\n\n$thinkFromStructure"
                mcpMarkers.isNotBlank() -> mcpMarkers
                else -> thinkFromStructure
            }

            return Pair(combinedThink, answer)
        }

        /**
         * Core separation logic using structural markers.
         * Step markers (<步骤N>, 步骤N:) are preserved and used for separation.
         */
        private fun separateByStructure(fullText: String, isDeepThink: Boolean): Pair<String, String> {
            // 1)  thinking... response XML tags
            THINK_TAG_REGEX.find(fullText)?.let { m ->
                val think = m.groupValues[1].trim()
                val answer = fullText.replace(THINK_TAG_REGEX, "").trim()
                return Pair(think, answer)
            }

            // 2) <thinking>...</thinking> XML tags
            THINKING_TAG_REGEX.find(fullText)?.let { m ->
                val think = m.groupValues[1].trim()
                val answer = fullText.replace(THINKING_TAG_REGEX, "").trim()
                return Pair(think, answer)
            }

            // 3) ```thinking ... ``` code block
            THINK_BLOCK_REGEX.find(fullText)?.let { m ->
                val think = m.groupValues[1].trim()
                val answer = fullText.replace(THINK_BLOCK_REGEX, "").trim()
                return Pair(think, answer)
            }

            // 4) [think]...[/think]
            THINK_BRACKET_REGEX.find(fullText)?.let { m ->
                val think = m.groupValues[1].trim()
                val answer = fullText.replace(THINK_BRACKET_REGEX, "").trim()
                return Pair(think, answer)
            }

            // 5) ### 思考过程 / ### 回答 markdown headers
            val thinkHeader = MARKDOWN_THINK_HEADER.find(fullText)
            val answerHeader = MARKDOWN_ANSWER_HEADER.find(fullText)
            if (thinkHeader != null && answerHeader != null) {
                val think = fullText.substring(thinkHeader.range.last + 1, answerHeader.range.first).trim()
                val answer = fullText.substring(answerHeader.range.last + 1).trim()
                return Pair(think, answer)
            }

            // 6) --- or === separator line
            SEPARATOR_LINE_REGEX.find(fullText)?.let { m ->
                val think = fullText.substring(0, m.range.first).trim()
                val answer = fullText.substring(m.range.last + 1).trim()
                if (think.isNotBlank()) return Pair(think, answer)
            }

            // 7) <步骤N> step markers (chain-of-thought planning)
            // Step markers are preserved in the text (not removed by sanitizeContent)
            if (fullText.startsWith("<步骤") || fullText.startsWith("步骤") || fullText.contains("\n<步骤") || fullText.contains("\n步骤")) {
                val lines = fullText.split("\n")
                val stepLines = mutableListOf<String>()
                val answerLines = mutableListOf<String>()
                var inSteps = true
                for (line in lines) {
                    val trimmed = line.trim()
                    if (inSteps) {
                        if (trimmed.startsWith("<步骤") || trimmed.startsWith("步骤")) {
                            stepLines.add(line)
                        } else if (trimmed.isEmpty()) {
                            stepLines.add(line)
                        } else {
                            inSteps = false
                            answerLines.add(line)
                        }
                    } else {
                        answerLines.add(line)
                    }
                }
                if (stepLines.isNotEmpty() && answerLines.isNotEmpty()) {
                    val think = stepLines.joinToString("\n").trim()
                    val answer = answerLines.joinToString("\n").trim()
                    if (answer.isNotBlank()) return Pair(think, answer)
                }
                // All lines matched steps — put everything in answer
                if (stepLines.isNotEmpty() && answerLines.isEmpty()) {
                    return Pair("", fullText)
                }
            }

            // 8) Deep think mode: try heuristic split at natural transition phrases
            if (isDeepThink && fullText.length > 80) {
                // Each entry: (phrase, isHighFrequency)
                // High-frequency words ("因此", "所以") require preceding punctuation to avoid false splits
                val transitions = listOf(
                    "综上所述" to false,
                    "基于以上分析" to false,
                    "因此" to true,
                    "所以" to true,
                    "答案是" to false,
                    "回答：" to false,
                    "最终答案" to false,
                    "总结" to false,
                    "结论" to false
                )
                for ((phrase, isHighFrequency) in transitions) {
                    val idx = fullText.indexOf(phrase)
                    // Only split if phrase appears after some meaningful content
                    if (idx in 21 until fullText.length - 10) {
                        // For high-frequency words, require preceding punctuation or newline
                        if (isHighFrequency) {
                            val preceding = if (idx > 0) fullText[idx - 1] else ' '
                            if (preceding != '。' && preceding != '！' && preceding != '？' && preceding != '\n' && preceding != '，') {
                                continue
                            }
                        }
                        val think = fullText.substring(0, idx).trim()
                        val answer = fullText.substring(idx).trim()
                        return Pair(think, answer)
                    }
                }
            }

            // No separation found — everything is answer
            return Pair("", fullText)
        }
    }
}