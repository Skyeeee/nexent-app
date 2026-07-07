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

    private var conversationId: Int? = null
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

            try {
                RetrofitClient.streamChat(baseUrl, request, prefHelper.apikey)
                    .flowOn(Dispatchers.IO)
                    .collect { chunk ->
                        val chunkText = sanitizeContent(chunk.content)
                        fullRaw.append(chunkText)
                        chunk.conversationId?.let { conversationId = it }

                        val rendered = fullRaw.toString().trim()
                        updateStreamingMessage(rendered.ifBlank { "…" }, "")

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
                val sanitizedPartial = sanitizeContent(partialContent)
                errMsgs[errMsgs.lastIndex] = errMsgs.last().copy(
                    content = sanitizedPartial.ifBlank { partialContent },
                    thinkingContent = "",
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
        val sanitizedText = sanitizeContent(fullText)
        finalMsgs[last] = finalMsgs[last].copy(
            content = sanitizedText.ifBlank { fullText },
            thinkingContent = "",
            isStreaming = false
        )
        _messages.value = finalMsgs.toList()
    }

    companion object {
        fun isAudioMimeType(contentType: String?): Boolean {
            if (contentType.isNullOrBlank()) return false
            return contentType.lowercase(Locale.getDefault()).startsWith("audio/")
        }

        fun sanitizeContent(text: String): String {
            var sanitized = text

            sanitized = sanitized.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            sanitized = sanitized.replace("\\n", "\n")
            sanitized = sanitized.replace("\\r\\n", "\n")
            sanitized = sanitized.replace("\\r", "\n")
            sanitized = sanitized.replace("\\\"", "\"")

            sanitized = sanitized
                .replace(Regex("MCP_START.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("MCP_END.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("MCP_CALL.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("TOOL_START.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("TOOL_END.*", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<tool_call>.*?</tool_call>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("<function_call>.*?</function_call>", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("\\[MCP_.*?\\]", RegexOption.IGNORE_CASE), "")
                .replace(Regex("<\\s*步骤\\d+\\s*>", RegexOption.IGNORE_CASE), "")
                .replace(Regex("(?m)(^|\\s)步骤\\d+[:：]"), " ")
                .replace(Regex("\\{[^{}]*(phase|step_number|token_threshold|thinking|reasoning|evaluation|metadata)[^{}]*\\}", RegexOption.IGNORE_CASE), "")
                .replace(Regex("(?m)^\\s*$\\n?", RegexOption.MULTILINE), "")
                .trim()

            return sanitized
        }
    }
}