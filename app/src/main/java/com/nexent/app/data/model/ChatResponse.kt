package com.nexent.app.data.model

import com.google.gson.annotations.SerializedName

data class ChatStreamChunk(
    @SerializedName("content") val content: String = "",
    @SerializedName("conversation_id") val conversationId: Int? = null,
    @SerializedName("done") val done: Boolean = false,
    @SerializedName("type") val type: String? = null
)
