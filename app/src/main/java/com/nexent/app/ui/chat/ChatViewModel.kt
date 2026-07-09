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
import com.nexent.app.data.model.AgentStep
import com.nexent.app.data.model.StepContent
import com.nexent.app.data.model.TokenMetrics
import com.nexent.app.data.model.SearchResult
import com.nexent.app.data.model.ApiMessageItem
import com.nexent.app.data.model.ApiConversationDetail
import com.nexent.app.data.model.ApiMessage
import com.nexent.app.data.model.ConversationDetailResponse
import com.nexent.app.data.network.RetrofitClient
import com.nexent.app.util.PreferenceHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import android.os.Build
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Represents a single chat message in the UI.
 * - For user messages: content is the user's text.
 * - For AI messages: content is the final answer text, and steps hold the thinking process.
 */
data class ChatMessage(
    val id: Long = System.nanoTime(),
    val content: String,
    val isUser: Boolean,
    val imageUri: String? = null,
    val imageUrl: String? = null,       // resolved image URL from upload
    val audioUrl: String? = null,       // local or remote audio file URL (voice message)
    val audioDuration: Int = 0,         // audio duration in milliseconds (0 = unknown)
    // AI-specific fields (like nexent-web ChatMessageType)
    val steps: List<AgentStep> = emptyList(),
    val searchResults: List<SearchResult> = emptyList(),
    val images: List<String> = emptyList(),
    val finalAnswer: String = "",
    val isStreaming: Boolean = false,
    val isDeepThink: Boolean = false,
    val tokenMetrics: TokenMetrics? = null,
    val messageId: Int? = null
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val prefHelper = PreferenceHelper(application)
    private val gson = Gson()

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

    companion object {
        private const val TAG = "ChatViewModel"

        // SSE message type constants (mirrors nexent-web chatConfig.messageTypes)
        const val TYPE_STEP_COUNT = "step_count"
        const val TYPE_MODEL_OUTPUT = "model_output"
        const val TYPE_MODEL_OUTPUT_THINKING = "model_output_thinking"
        const val TYPE_MODEL_OUTPUT_DEEP_THINKING = "model_output_deep_thinking"
        const val TYPE_MODEL_OUTPUT_CODE = "model_output_code"
        const val TYPE_FINAL_ANSWER = "final_answer"
        const val TYPE_SEARCH_CONTENT = "search_content"
        const val TYPE_PICTURE_WEB = "picture_web"
        const val TYPE_TOOL = "tool"
        const val TYPE_TOKEN_COUNT = "token_count"
        const val TYPE_ERROR = "error"
        const val TYPE_AGENT_NEW_RUN = "agent_new_run"
        const val TYPE_MEMORY_SEARCH = "memory_search"
        const val TYPE_CARD = "card"
        const val TYPE_EXECUTION_LOGS = "execution_logs"
        const val TYPE_PARSE = "parse"
        const val TYPE_SKILL_FILES = "skill_files"

        fun isAudioMimeType(contentType: String?): Boolean {
            if (contentType.isNullOrBlank()) return false
            return contentType.lowercase(Locale.getDefault()).startsWith("audio/")
        }

        /**
         * Sanitize raw content: unescape JSON escape sequences,
         * strip tool invocation markers like MCP_START, TOOL_START, <tool_call>, etc.
         */
        fun sanitizeContent(text: String): String {
            var sanitized = text

            // Unescape JSON escape sequences
            sanitized = sanitized.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { match ->
                match.groupValues[1].toInt(16).toChar().toString()
            }
            sanitized = sanitized.replace("\\n", "\n")
            sanitized = sanitized.replace("\\r\\n", "\n")
            sanitized = sanitized.replace("\\r", "\n")
            sanitized = sanitized.replace("\\\"", "\"")

            // Strip tool invocation markers
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
                .replace(Regex("(?m)^\\s*$\\n?", RegexOption.MULTILINE), "")
                .trim()

            return sanitized
        }

        /**
         * Convert custom code tags to standard markdown code fences.
         * Mirrors nexent-web's convertCustomCodeTags().
         * - <code>...</code> → ```\n...\n```
         * - <DISPLAY:language>...</DISPLAY> → ```language\n...\n```
         */
        fun convertCodeTags(content: String): String {
            var result = content
            // Complete <DISPLAY:language>...</DISPLAY>
            result = result.replace(Regex("<DISPLAY:(\\w+)>([\\s\\S]*?)</DISPLAY>")) { match ->
                val lang = match.groupValues[1]
                val code = match.groupValues[2].trim()
                "```$lang\n$code\n```"
            }
            // Complete <code>...</code>
            result = result.replace(Regex("<code>([\\s\\S]*?)</code>")) { match ->
                val code = match.groupValues[1].trim()
                "```\n$code\n```"
            }
            // Incomplete tags during streaming
            result = result.replace(Regex("<DISPLAY:(\\w+)>(?![\\s\\S]*</DISPLAY>)")) { match ->
                "```${match.groupValues[1]}\n"
            }
            result = result.replace(Regex("<code>(?![\\s\\S]*</code>)")) {
                "```\n"
            }
            return result
        }
    }

    fun init(agentName: String) {
        currentAgentName = agentName
    }

    fun setThinkMode(mode: String) {
        _thinkMode.value = mode
        deepThinkEnabled = mode == "deep"
    }

    fun sendMessage(text: String, attachments: List<String>? = null) {
        if (text.isBlank() && attachments.isNullOrEmpty()) return

        viewModelScope.launch(Dispatchers.Main) {
            val currentMessages = _messages.value.orEmpty().toMutableList()
            currentMessages.add(ChatMessage(content = text, isUser = true))
            _messages.value = currentMessages.toList()

            startStreamRequest(text, attachments)
        }
    }

    fun sendAttachmentOnlyMessage(attachments: List<String>, imageUri: String? = null, audioUrl: String? = null, audioDuration: Int = 0) {
        viewModelScope.launch(Dispatchers.Main) {
            val currentMessages = _messages.value.orEmpty().toMutableList()
            currentMessages.add(ChatMessage(content = "", isUser = true, imageUri = imageUri, audioUrl = audioUrl, audioDuration = audioDuration))
            _messages.value = currentMessages.toList()

            startStreamRequest("", attachments)
        }
    }

    private fun startStreamRequest(text: String, attachments: List<String>?) {
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

        // Add a placeholder AI message for streaming
        val aiMsg = ChatMessage(
            content = "…",
            isUser = false,
            isStreaming = true,
            isDeepThink = deepThinkEnabled
        )
        val updated = _messages.value.orEmpty().toMutableList()
        updated.add(aiMsg)
        _messages.value = updated.toList()

        // Accumulated state during streaming
        var finalAnswer = ""
        var currentSteps = mutableListOf<AgentStep>()
        currentStep = null  // Use class member, reset for new stream
        var searchResults = mutableListOf<SearchResult>()
        var images = mutableListOf<String>()
        val pendingMetrics = mutableMapOf<String, TokenMetrics>()
        var stepCounter = 0

        viewModelScope.launch(Dispatchers.Main) {
            try {
                RetrofitClient.streamChat(baseUrl, request, prefHelper.apikey)
                    .flowOn(Dispatchers.IO)
                    .collect { chunk ->
                        Log.d(TAG, "chunk: type=${chunk.type}, content=\"${chunk.content.take(100)}\", conversationId=${chunk.conversationId}, done=${chunk.done}")

                        chunk.conversationId?.let { conversationId = it }

                        when (chunk.type) {
                            TYPE_STEP_COUNT -> {
                                // Start a new step
                                stepCounter++
                                val title = chunk.content.trim()
                                currentStep = AgentStep(
                                    id = "step-$stepCounter",
                                    title = title,
                                    contents = mutableListOf(),
                                    expanded = true
                                )
                            }

                            TYPE_MODEL_OUTPUT -> {
                                val step = currentStep ?: createFallbackStep(stepCounter)
                                currentStep = step
                                appendToStepContent(step, TYPE_MODEL_OUTPUT, chunk.content)
                            }

                            TYPE_MODEL_OUTPUT_THINKING -> {
                                val step = currentStep ?: createFallbackStep(stepCounter)
                                currentStep = step
                                appendToStepContent(step, TYPE_MODEL_OUTPUT_THINKING, chunk.content, "thinking")
                            }

                            TYPE_MODEL_OUTPUT_DEEP_THINKING -> {
                                val step = currentStep ?: createFallbackStep(stepCounter)
                                currentStep = step
                                appendToStepContent(step, TYPE_MODEL_OUTPUT_DEEP_THINKING, chunk.content, "deep_thinking")
                            }

                            TYPE_MODEL_OUTPUT_CODE -> {
                                val step = currentStep ?: createFallbackStep(stepCounter)
                                currentStep = step
                                appendToStepContent(step, TYPE_MODEL_OUTPUT_CODE, chunk.content)
                            }

                            TYPE_FINAL_ANSWER -> {
                                val sanitized = processUserBreakTag(chunk.content)
                                finalAnswer += sanitized
                            }

                            TYPE_SEARCH_CONTENT -> {
                                try {
                                    val resultsList: List<Map<String, Any>> = gson.fromJson(
                                        chunk.content,
                                        object : TypeToken<List<Map<String, Any>>>() {}.type
                                    )
                                    for (item in resultsList) {
                                        val sr = SearchResult(
                                            title = item["title"]?.toString() ?: "",
                                            url = item["url"]?.toString() ?: "",
                                            text = item["text"]?.toString() ?: "",
                                            sourceType = item["source_type"]?.toString() ?: "",
                                            toolSign = item["tool_sign"]?.toString() ?: "",
                                            citeIndex = (item["cite_index"] as? Double)?.toInt() ?: -1,
                                            publishedDate = item["published_date"]?.toString() ?: "",
                                            filename = item["filename"]?.toString(),
                                            score = item["score"] as? Double
                                        )
                                        // Deduplicate by text
                                        if (searchResults.none { it.text == sr.text }) {
                                            searchResults.add(sr)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse search_content", e)
                                }
                            }

                            TYPE_PICTURE_WEB -> {
                                try {
                                    val map: Map<String, Any> = gson.fromJson(
                                        chunk.content,
                                        object : TypeToken<Map<String, Any>>() {}.type
                                    )
                                    val urls = (map["images_url"] as? List<*>)?.mapNotNull { it?.toString() } ?: emptyList()
                                    for (url in urls) {
                                        if (images.none { it == url }) {
                                            images.add(url)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse picture_web", e)
                                }
                            }

                            TYPE_TOOL -> {
                                // Show tool execution indicator
                                val step = currentStep ?: createFallbackStep(stepCounter)
                                currentStep = step
                                val toolContent = StepContent(
                                    id = "tool-${System.nanoTime()}",
                                    type = "executing",
                                    content = chunk.content,
                                    isLoading = true,
                                    timestamp = System.currentTimeMillis()
                                )
                                val contents = step.contents.toMutableList()
                                contents.add(toolContent)
                                currentStep = step.copy(contents = contents)
                            }

                            TYPE_TOKEN_COUNT -> {
                                try {
                                    val metrics = gson.fromJson(chunk.content, TokenMetrics::class.java)
                                    val stepId = "step-${metrics.stepNumber}"
                                    // Try to apply to existing step, or save as pending
                                    val stepIndex = currentSteps.indexOfFirst { it.id == stepId }
                                    if (stepIndex >= 0) {
                                        currentSteps[stepIndex] = currentSteps[stepIndex].copy(metrics = metrics)
                                    } else {
                                        pendingMetrics[stepId] = metrics
                                    }
                                } catch (e: Exception) {
                                    Log.e(TAG, "Failed to parse token_count", e)
                                }
                            }

                            TYPE_ERROR -> {
                                val step = currentStep ?: createFallbackStep(stepCounter)
                                currentStep = step
                                val errorContent = StepContent(
                                    id = "error-${System.nanoTime()}",
                                    type = "error",
                                    content = chunk.content,
                                    timestamp = System.currentTimeMillis()
                                )
                                val contents = step.contents.toMutableList()
                                contents.add(errorContent)
                                currentStep = step.copy(contents = contents)
                            }

                            TYPE_AGENT_NEW_RUN -> {
                                // Show "Thinking..." indicator
                                val step = currentStep ?: createFallbackStep(stepCounter)
                                currentStep = step
                                val thinkingText = if (chunk.content.contains("MCP_START")) {
                                    "Connecting to MCP server..."
                                } else {
                                    "Thinking..."
                                }
                                val runContent = StepContent(
                                    id = "agent-run-${System.nanoTime()}",
                                    type = TYPE_AGENT_NEW_RUN,
                                    content = thinkingText,
                                    timestamp = System.currentTimeMillis()
                                )
                                val contents = step.contents.toMutableList()
                                contents.add(runContent)
                                currentStep = step.copy(contents = contents)
                            }

                            TYPE_MEMORY_SEARCH -> {
                                val step = currentStep ?: createFallbackStep(stepCounter)
                                currentStep = step
                                val memMsg = translateMemoryMessage(chunk.content)
                                val memContent = StepContent(
                                    id = "memory-search-${System.nanoTime()}",
                                    type = TYPE_MEMORY_SEARCH,
                                    content = memMsg,
                                    timestamp = System.currentTimeMillis()
                                )
                                val contents = step.contents.toMutableList()
                                contents.add(memContent)
                                currentStep = step.copy(contents = contents)
                            }

                            TYPE_CARD -> {
                                val step = currentStep ?: createFallbackStep(stepCounter)
                                currentStep = step
                                val cardContent = StepContent(
                                    id = "card-${System.nanoTime()}",
                                    type = TYPE_CARD,
                                    content = chunk.content,
                                    timestamp = System.currentTimeMillis()
                                )
                                val contents = step.contents.toMutableList()
                                contents.add(cardContent)
                                currentStep = step.copy(contents = contents)
                            }

                            // Skip these types (like nexent-web does)
                            TYPE_EXECUTION_LOGS, TYPE_PARSE, TYPE_SKILL_FILES -> { /* skip */ }

                            null, "" -> {
                                // If no type, treat as content (legacy fallback)
                                val chunkText = sanitizeContent(chunk.content)
                                if (chunkText.isNotBlank()) {
                                    finalAnswer += chunkText
                                }
                            }

                            else -> {
                                // Unknown type - skip
                                Log.d(TAG, "Unknown chunk type: ${chunk.type}")
                            }
                        }

                        // Accumulate current step into steps list
                        currentStep?.let { step ->
                            val existingIdx = currentSteps.indexOfFirst { it.id == step.id }
                            if (existingIdx >= 0) {
                                currentSteps[existingIdx] = step
                            } else if (step.contents.isNotEmpty()) {
                                currentSteps.add(step)
                            }
                        }

                        // Apply any pending metrics
                        for ((stepId, metrics) in pendingMetrics) {
                            val idx = currentSteps.indexOfFirst { it.id == stepId }
                            if (idx >= 0) {
                                currentSteps[idx] = currentSteps[idx].copy(metrics = metrics)
                                pendingMetrics.remove(stepId)
                            }
                        }

                        // Build the rendered content for streaming display
                        val displayContent = if (finalAnswer.isNotBlank()) {
                            convertCodeTags(sanitizeContent(finalAnswer))
                        } else {
                            // Show a compact summary of current step activity
                            buildStepSummary(currentSteps)
                        }

                        updateStreamingMessage(
                            content = displayContent.ifBlank { "…" },
                            steps = currentSteps.toList(),
                            searchResults = searchResults.toList(),
                            images = images.toList(),
                            finalAnswer = convertCodeTags(sanitizeContent(finalAnswer))
                        )

                        if (chunk.done) {
                            finalizeStreamingMessage(
                                finalAnswer = finalAnswer,
                                steps = currentSteps.toList(),
                                searchResults = searchResults.toList(),
                                images = images.toList()
                            )
                            return@collect
                        }
                    }
            } catch (e: Exception) {
                Log.e(TAG, "streamChat error", e)
                handleStreamError(finalAnswer, currentSteps, searchResults, images, e)
            } finally {
                _isLoading.value = false
                // Ensure the streaming message is finalized even if the server
                // did not send a [DONE] signal (e.g. stream ended naturally)
                val msgs = _messages.value.orEmpty().toMutableList()
                val last = msgs.lastOrNull()
                if (last != null && last.isStreaming) {
                    finalizeStreamingMessage(
                        finalAnswer = finalAnswer,
                        steps = currentSteps.toList(),
                        searchResults = searchResults.toList(),
                        images = images.toList()
                    )
                }
            }
        }
    }

    // ---- Streaming state helpers ----

    private fun createFallbackStep(stepCounter: Int): AgentStep {
        return AgentStep(
            id = "step-${stepCounter + 1}",
            title = "",
            contents = mutableListOf(),
            expanded = true
        )
    }

    private fun appendToStepContent(step: AgentStep, type: String, content: String, subType: String? = null) {
        val contents = step.contents.toMutableList()
        val lastContent = contents.lastOrNull()
        // If same type, append to existing block (for streaming chunks)
        if (lastContent != null && lastContent.type == type && lastContent.subType == subType) {
            contents[contents.lastIndex] = lastContent.copy(
                content = lastContent.content + content
            )
        } else {
            contents.add(StepContent(
                id = "model-${System.nanoTime()}",
                type = type,
                content = content,
                subType = subType,
                timestamp = System.currentTimeMillis()
            ))
        }
        // Update the currentStep reference
        currentStep = step.copy(contents = contents)
    }

    private var currentStep: AgentStep? = null

    private fun buildStepSummary(currentSteps: List<AgentStep>): String {
        if (currentSteps.isNotEmpty()) {
            val lastStep = currentSteps.last()
            val lastContent = lastStep.contents.lastOrNull()
            if (lastContent != null) {
                return when (lastContent.type) {
                    TYPE_MODEL_OUTPUT_THINKING -> "🤔 ${lastContent.content.take(100)}..."
                    TYPE_MODEL_OUTPUT_DEEP_THINKING -> "🧠 ${lastContent.content.take(100)}..."
                    TYPE_MODEL_OUTPUT_CODE -> "💻 Generating code..."
                    "executing" -> "🔧 ${lastContent.content.take(80)}..."
                    TYPE_MEMORY_SEARCH -> "📂 ${lastContent.content}"
                    TYPE_AGENT_NEW_RUN -> "💭 ${lastContent.content}"
                    TYPE_SEARCH_CONTENT -> "🔍 Searching..."
                    else -> lastContent.content.take(100)
                }
            }
            return "⚙️ Processing..."
        }
        return "…"
    }

    private fun translateMemoryMessage(content: String): String {
        return try {
            val map: Map<String, Any> = gson.fromJson(content, object : TypeToken<Map<String, Any>>() {}.type)
            val msg = map["message"]?.toString() ?: content
            when (msg) {
                "<MEM_START>" -> "📂 Retrieving memory..."
                "<MEM_DONE>" -> "✅ Memory retrieved"
                "<MEM_FAILED>" -> "❌ Memory retrieval failed"
                else -> content
            }
        } catch (e: Exception) {
            content
        }
    }

    private fun processUserBreakTag(content: String): String {
        if (content == "<user_break>") {
            return "[User interrupted]"
        }
        return content
    }

    private fun updateStreamingMessage(
        content: String,
        steps: List<AgentStep>,
        searchResults: List<SearchResult>,
        images: List<String>,
        finalAnswer: String
    ) {
        val msgs = _messages.value.orEmpty().toMutableList()
        val lastIdx = msgs.lastIndex
        if (lastIdx >= 0 && msgs[lastIdx].isStreaming) {
            msgs[lastIdx] = msgs[lastIdx].copy(
                content = content,
                steps = steps,
                searchResults = searchResults,
                images = images,
                finalAnswer = finalAnswer
            )
            _messages.value = msgs.toList()
        }
    }

    private fun finalizeStreamingMessage(
        finalAnswer: String,
        steps: List<AgentStep>,
        searchResults: List<SearchResult>,
        images: List<String>
    ) {
        val finalMsgs = _messages.value.orEmpty().toMutableList()
        val last = finalMsgs.lastIndex
        if (last < 0 || !finalMsgs[last].isStreaming) return

        val sanitizedFinal = sanitizeContent(finalAnswer)
        val displayContent = if (sanitizedFinal.isNotBlank()) {
            convertCodeTags(sanitizedFinal)
        } else if (steps.isNotEmpty()) {
            buildStepSummary(steps)
        } else {
            finalAnswer
        }

        // Deduplicate steps by title (like nexent-web)
        val uniqueSteps = mutableListOf<AgentStep>()
        val seenTitles = mutableSetOf<String>()
        for (step in steps) {
            val title = step.title.trim()
            if (step.contents.isNotEmpty() && title !in seenTitles) {
                seenTitles.add(title)
                uniqueSteps.add(step)
            }
        }

        finalMsgs[last] = finalMsgs[last].copy(
            content = displayContent.ifBlank { finalAnswer },
            steps = uniqueSteps,
            searchResults = searchResults,
            images = images,
            finalAnswer = convertCodeTags(sanitizedFinal),
            isStreaming = false
        )
        _messages.value = finalMsgs.toList()
    }

    private fun handleStreamError(
        finalAnswer: String,
        steps: List<AgentStep>,
        searchResults: List<SearchResult>,
        images: List<String>,
        e: Exception
    ) {
        val errMsgs = _messages.value.orEmpty().toMutableList()
        if (errMsgs.isNotEmpty() && errMsgs.last().isStreaming) {
            val sanitizedFinal = sanitizeContent(finalAnswer)
            if (sanitizedFinal.isNotBlank()) {
                errMsgs[errMsgs.lastIndex] = errMsgs.last().copy(
                    content = convertCodeTags(sanitizedFinal),
                    steps = steps,
                    searchResults = searchResults,
                    images = images,
                    finalAnswer = convertCodeTags(sanitizedFinal),
                    isStreaming = false
                )
            } else {
                errMsgs.removeLast()
            }
            _messages.value = errMsgs.toList()
        }
        val msg = e.message ?: e::class.java.name
        _error.value = "发送失败: $msg"
        Log.e(TAG, "handleStreamError: $msg\n${e.stackTraceToString()}")
    }

    // ---- Historical conversation loading ----

    fun loadConversation(conversationId: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // TODO: implement API call to load conversation detail
                // For now, just set conversation ID
                this@ChatViewModel.conversationId = conversationId
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load conversation", e)
            }
        }
    }

    // ---- Attachment handling ----

    fun sendImageMessage(imageUri: Uri, description: String) {
        uploadAttachmentAndSendMessage(imageUri, description, isVoice = false)
    }

    fun sendVoiceMessage(audioUri: Uri, description: String, audioDuration: Int = 0) {
        uploadAttachmentAndSendMessage(audioUri, description, isVoice = true, audioDuration = audioDuration)
    }

    private fun uploadAttachmentAndSendMessage(uri: Uri, description: String, isVoice: Boolean, audioDuration: Int = 0) {
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
                Log.d(TAG, "uploadAttachment result: $uploadedUrl, filename=$name, mime=$mime")

                if (uploadedUrl == null) {
                    _error.postValue(if (isVoice) "语音上传失败" else "图片上传失败")
                } else {
                    // extract presigned_url from the returned URL and use it as the attachment URL
                    val meta = extractAttachmentMetadata(uploadedUrl)
                    Log.d(TAG, "attachment meta: $meta")
                    val backendPath = meta["backend_path"]
                    val presigned = meta["presigned_url"]

                    // Prefer backend-friendly path, then presigned URL, then raw URL
                    val attachmentToSend = when {
                        !backendPath.isNullOrBlank() -> backendPath
                        !presigned.isNullOrBlank() -> presigned
                        else -> uploadedUrl
                    }

                    // 不替换 audioUrl：保留本地文件路径用于播放，远端 URL 仅用于 API 发送
                    sendAttachmentOnlyMessage(listOf(attachmentToSend), imageUri = if (isVoice) null else uri.toString(), audioUrl = if (isVoice) uri.toString() else null, audioDuration = audioDuration)
                }
            } catch (e: Exception) {
                _error.postValue(if (isVoice) "语音上传失败: ${e.message}" else "图片上传失败: ${e.message}")
            } finally {
                _isLoading.postValue(false)
            }
        }
    }

    private fun updateMessageAudioUrl(messageId: Long, audioUrl: String) {
        val msgs = _messages.value.orEmpty().toMutableList()
        val idx = msgs.indexOfFirst { it.id == messageId }
        if (idx >= 0) {
            msgs[idx] = msgs[idx].copy(audioUrl = audioUrl)
            _messages.postValue(msgs.toList())
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

    private fun extractAttachmentMetadata(uploadedUrl: String): Map<String, String> {
        try {
            val normalized = uploadedUrl.replace("\\u003d", "=").replace("\\u0026", "&")
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

            var backendPath = ""
            Regex("s3://[^\\s/\"]+/attachments/[^?\\s\"]+").find(presignedDecoded)?.let {
                backendPath = it.value
            }
            if (backendPath.isBlank() && presignedDecoded.isNotBlank()) {
                try {
                    val uriObj = URI(presignedDecoded)
                    val path = uriObj.rawPath ?: ""
                    if (path.isNotBlank()) {
                        val attachmentsIdx = path.indexOf("/attachments/")
                        if (attachmentsIdx >= 0) {
                            val startIdx = path.lastIndexOf('/', attachmentsIdx - 1).let { if (it >= 0) it else 0 }
                            backendPath = path.substring(startIdx)
                        } else {
                            val m = Regex("/[^/]+/attachments/[^?\\s]+").find(path)
                            if (m != null) backendPath = m.value
                            else if (path.contains("attachments/")) {
                                val rel = path.substringAfter("attachments/")
                                backendPath = "attachments/" + rel
                            } else {
                                backendPath = path
                            }
                        }
                    }
                } catch (_: Exception) { }
            }
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
}