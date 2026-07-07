package com.nexent.app.data.model

import com.google.gson.annotations.SerializedName

// SSE stream chunk from the backend
// The backend sends: data: {"type":"...", "content":"...", ...}
data class ChatStreamChunk(
    @SerializedName("type") val type: String? = null,
    @SerializedName("content") val content: String = "",
    @SerializedName("conversation_id") val conversationId: Int? = null,
    @SerializedName("done") val done: Boolean = false,
    @SerializedName("unit_index") val unitIndex: Int? = null,
    @SerializedName("status") val status: String? = null
)

// Token metrics for an agent step
data class TokenMetrics(
    @SerializedName("step_number") val stepNumber: Int = 0,
    @SerializedName("duration") val duration: Double = 0.0,
    @SerializedName("step_input_tokens") val stepInputTokens: Int? = null,
    @SerializedName("step_output_tokens") val stepOutputTokens: Int? = null,
    @SerializedName("total_output_tokens") val totalOutputTokens: Int = 0,
    @SerializedName("estimated_context_tokens") val estimatedContextTokens: Int? = null,
    @SerializedName("token_threshold") val tokenThreshold: Int? = null
)

// A content block inside an agent step
data class StepContent(
    val id: String,
    val type: String,       // e.g. "model_output", "model_output_thinking", "model_output_code", "search_content", etc.
    val content: String = "",
    val subType: String? = null, // e.g. "thinking", "deep_thinking", "verification"
    val expanded: Boolean = true,
    val timestamp: Long = System.currentTimeMillis(),
    val isLoading: Boolean = false
)

// A single agent step (thinking process)
data class AgentStep(
    val id: String,
    val title: String = "",
    val contents: List<StepContent> = emptyList(),
    val metrics: TokenMetrics? = null,
    val expanded: Boolean = true
)

// Search result item for citations
data class SearchResult(
    @SerializedName("title") val title: String = "",
    @SerializedName("url") val url: String = "",
    @SerializedName("text") val text: String = "",
    @SerializedName("source_type") val sourceType: String = "",
    @SerializedName("tool_sign") val toolSign: String = "",
    @SerializedName("cite_index") val citeIndex: Int = -1,
    @SerializedName("published_date") val publishedDate: String = "",
    @SerializedName("filename") val filename: String? = null,
    @SerializedName("score") val score: Double? = null
)

// Historical conversation message from API
data class ApiMessageItem(
    @SerializedName("type") val type: String,
    @SerializedName("content") val content: String
)

data class ApiMessage(
    @SerializedName("role") val role: String,
    @SerializedName("message") val message: List<ApiMessageItem>? = null,
    @SerializedName("message_id") val messageId: Int = 0,
    @SerializedName("opinion_flag") val opinionFlag: String? = null,
    @SerializedName("search") val search: List<SearchResult>? = null,
    @SerializedName("picture") val picture: List<String>? = null
)

data class ApiConversationDetail(
    @SerializedName("conversation_id") val conversationId: Int = 0,
    @SerializedName("create_time") val createTime: Long = 0,
    @SerializedName("message") val message: List<ApiMessage>? = null
)

data class ConversationDetailResponse(
    @SerializedName("code") val code: Int = 0,
    @SerializedName("data") val data: List<ApiConversationDetail>? = null
)