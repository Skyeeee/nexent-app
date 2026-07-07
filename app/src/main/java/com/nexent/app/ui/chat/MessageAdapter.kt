package com.nexent.app.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexent.app.databinding.ItemMessageAiBinding
import com.nexent.app.databinding.ItemMessageUserBinding
import io.noties.markwon.Markwon
import com.bumptech.glide.Glide
import java.net.HttpURLConnection
import java.net.URL
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.load.DataSource
import android.graphics.drawable.Drawable

class MessageAdapter : ListAdapter<ChatMessage, RecyclerView.ViewHolder>(MessageDiffCallback()) {

    companion object {
        private const val VIEW_TYPE_USER = 0
        private const val VIEW_TYPE_AI = 1
    }

    override fun getItemViewType(position: Int): Int {
        return if (getItem(position).isUser) VIEW_TYPE_USER else VIEW_TYPE_AI
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return if (viewType == VIEW_TYPE_USER) {
            val binding = ItemMessageUserBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            UserMessageViewHolder(binding)
        } else {
            val binding = ItemMessageAiBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            AiMessageViewHolder(binding)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val item = getItem(position)
        when (holder) {
            is UserMessageViewHolder -> holder.bind(item)
            is AiMessageViewHolder -> holder.bind(item)
        }
    }

    class UserMessageViewHolder(private val binding: ItemMessageUserBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(message: ChatMessage) {
            binding.tvMessage.text = message.content
            if (!message.imageUri.isNullOrBlank()) {
                binding.ivAttachment.visibility = View.VISIBLE
                binding.ivAttachment.adjustViewBounds = true

                // Log the URI so we can inspect what's being loaded at runtime
                android.util.Log.d("MessageAdapter", "Loading image: ${message.imageUri}")

                // For HTTP URLs, do a quick HEAD probe on a background thread to log response code.
                if (message.imageUri.startsWith("http", true)) {
                    Thread {
                        try {
                            val url = URL(message.imageUri)
                            val conn = url.openConnection() as HttpURLConnection
                            conn.requestMethod = "HEAD"
                            conn.connectTimeout = 5000
                            conn.readTimeout = 5000
                            val code = try { conn.responseCode } catch (e: Exception) { -1 }
                            android.util.Log.d("MessageAdapter", "HEAD $code for ${message.imageUri}")
                            conn.disconnect()
                        } catch (e: Exception) {
                            android.util.Log.e("MessageAdapter", "HEAD failed for ${message.imageUri}: ${e.message}", e)
                        }
                    }.start()
                }

                // Use Glide with placeholder/error.
                Glide.with(binding.ivAttachment.context)
                    .load(message.imageUri)
                    .centerCrop()
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.drawable.stat_notify_error)
                    .into(binding.ivAttachment)
            } else {
                binding.ivAttachment.visibility = View.GONE
                binding.ivAttachment.setImageDrawable(null)
            }
        }
    }

    class AiMessageViewHolder(private val binding: ItemMessageAiBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val markwon = Markwon.create(binding.root.context)

        fun bind(message: ChatMessage) {
            markwon.setMarkdown(binding.tvMessage, message.content)
            binding.tvStreamingIndicator.visibility =
                if (message.isStreaming) View.VISIBLE else View.GONE

            // Show thinking content if available (deep think mode)
            if (message.thinkingContent.isNotBlank()) {
                binding.thinkingContainer.visibility = View.VISIBLE
                markwon.setMarkdown(binding.tvThinking, message.thinkingContent)
            } else {
                binding.thinkingContainer.visibility = View.GONE
            }
        }
    }

    private class MessageDiffCallback : DiffUtil.ItemCallback<ChatMessage>() {
        override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
            oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage) =
            oldItem == newItem
    }
}
