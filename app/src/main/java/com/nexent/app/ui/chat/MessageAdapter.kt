package com.nexent.app.ui.chat

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.nexent.app.databinding.ItemMessageAiBinding
import com.nexent.app.databinding.ItemMessageUserBinding
import io.noties.markwon.Markwon
import io.noties.markwon.ext.tables.TablePlugin
import com.bumptech.glide.Glide
import java.net.HttpURLConnection
import java.net.URL
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.graphics.Typeface
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.SeekBar
import kotlin.math.roundToInt

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

        private var mediaPlayer: MediaPlayer? = null
        private val handler = Handler(Looper.getMainLooper())
        private val progressRunnable = object : Runnable {
            override fun run() {
                val mp = mediaPlayer ?: return
                if (mp.isPlaying) {
                    val duration = mp.duration.coerceAtLeast(1)
                    binding.audioProgress.progress = (mp.currentPosition * 100 / duration)
                    binding.tvAudioDuration.text = formatDuration(mp.currentPosition)
                    handler.postDelayed(this, 200)
                }
            }
        }

        fun bind(message: ChatMessage) {
            releasePlayer()
            binding.tvMessage.text = message.content
            if (!message.audioUrl.isNullOrBlank()) {
                binding.audioContainer.visibility = View.VISIBLE
                binding.ivAttachment.visibility = View.GONE
                binding.ivAttachment.setImageDrawable(null)
                binding.audioProgress.progress = 0
                binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
                // 使用 ChatMessage 中预存的音频时长，避免异步获取
                binding.tvAudioDuration.text = if (message.audioDuration > 0) {
                    formatDuration(message.audioDuration)
                } else {
                    "00:00"
                }
                binding.btnPlayAudio.setOnClickListener {
                    togglePlay(message.audioUrl)
                }
            } else if (!message.imageUri.isNullOrBlank()) {
                binding.ivAttachment.visibility = View.VISIBLE
                binding.ivAttachment.adjustViewBounds = true

                android.util.Log.d("MessageAdapter", "Loading image: ${message.imageUri}")

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

                Glide.with(binding.ivAttachment.context)
                    .load(message.imageUri)
                    .centerCrop()
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.drawable.stat_notify_error)
                    .into(binding.ivAttachment)
            } else {
                binding.ivAttachment.visibility = View.GONE
                binding.ivAttachment.setImageDrawable(null)
                binding.audioContainer.visibility = View.GONE
            }
        }

        private fun togglePlay(url: String) {
            val mp = mediaPlayer
            if (mp != null && mp.isPlaying) {
                mp.pause()
                binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
                return
            }
            if (mp != null) {
                // Resume
                try {
                    mp.start()
                    binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause)
                    handler.post(progressRunnable)
                } catch (e: Exception) {
                    android.util.Log.e("MessageAdapter", "resume audio failed: ${e.message}")
                    releasePlayer()
                    showPlayError()
                }
                return
            }
            // Start new playback
            try {
                val player = MediaPlayer()
                if (url.startsWith("http", true)) {
                    player.setDataSource(url)
                } else if (url.startsWith("file://", true)) {
                    player.setDataSource(url.substring(7))
                } else {
                    player.setDataSource(binding.root.context, android.net.Uri.parse(url))
                }
                player.setOnPreparedListener {
                    binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_pause)
                    binding.tvAudioDuration.text = formatDuration(player.duration)
                    player.start()
                    handler.post(progressRunnable)
                }
                player.setOnCompletionListener {
                    binding.btnPlayAudio.setImageResource(android.R.drawable.ic_media_play)
                    binding.audioProgress.progress = 0
                    binding.tvAudioDuration.text = formatDuration(player.duration)
                    releasePlayer()
                }
                player.setOnErrorListener { _, _, _ ->
                    releasePlayer()
                    showPlayError()
                    true
                }
                player.prepareAsync()
                mediaPlayer = player
            } catch (e: Exception) {
                android.util.Log.e("MessageAdapter", "play audio failed: ${e.message}")
                releasePlayer()
                showPlayError()
            }
        }

        private fun showPlayError() {
            binding.tvAudioDuration.text = "加载失败"
            handler.postDelayed({
                binding.tvAudioDuration.text = "00:00"
            }, 2000)
        }

        private fun releasePlayer() {
            handler.removeCallbacks(progressRunnable)
            mediaPlayer?.let {
                try {
                    if (it.isPlaying) it.stop()
                } catch (_: Exception) { }
                it.release()
            }
            mediaPlayer = null
        }

        private fun formatDuration(ms: Int): String {
            val totalSec = ms / 1000
            val min = totalSec / 60
            val sec = totalSec % 60
            return String.format("%02d:%02d", min, sec)
        }
    }

    class AiMessageViewHolder(private val binding: ItemMessageAiBinding) :
        RecyclerView.ViewHolder(binding.root) {
        private val markwon = Markwon.builder(binding.root.context)
            .usePlugin(TablePlugin.create(binding.root.context))
            .build()

        fun bind(message: ChatMessage) {
            // Step-by-step thinking process (TaskWindow equivalent)
            bindThinkingProcess(message)

            // Main content (final answer) with Markdown rendering
            val displayContent = if (message.finalAnswer.isNotBlank()) {
                message.finalAnswer
            } else {
                message.content
            }
            markwon.setMarkdown(binding.tvMessage, displayContent)
            bindContentWidthControl(displayContent)

            // Search results citations (sources button)
            bindSearchResults(message)

            // Web images (from picture_web SSE events)
            bindWebImages(message)

            // Streaming indicator
            binding.tvStreamingIndicator.visibility =
                if (message.isStreaming) View.VISIBLE else View.GONE

            // Token metrics (if available)
            bindTokenMetrics(message)
        }

        private fun bindContentWidthControl(content: String) {
            val looksLikeTable = content.contains("|") && content.lines().any { it.contains("|") }
            if (!looksLikeTable) {
                binding.seekContentWidth.visibility = View.GONE
                binding.tvContentWidthHint.visibility = View.GONE
                return
            }

            binding.seekContentWidth.visibility = View.VISIBLE
            binding.tvContentWidthHint.visibility = View.VISIBLE

            val minWidthPx = dpToPx(180)
            val maxWidthPx = (binding.root.resources.displayMetrics.widthPixels - dpToPx(96)).coerceAtLeast(minWidthPx)
            val defaultWidthPx = minOf(maxWidthPx, dpToPx(260))

            fun updateWidth(progress: Int) {
                val ratio = progress / 100f
                val targetWidth = (minWidthPx + (maxWidthPx - minWidthPx) * ratio).roundToInt()
                val scrollParams = binding.messageScrollView.layoutParams
                scrollParams.width = targetWidth
                binding.messageScrollView.layoutParams = scrollParams

                val textParams = binding.tvMessage.layoutParams
                textParams.width = targetWidth
                binding.tvMessage.layoutParams = textParams

                binding.tvContentWidthHint.text = "宽度: ${targetWidth / binding.root.resources.displayMetrics.density}dp"
            }

            binding.seekContentWidth.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        updateWidth(progress)
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })

            if (binding.seekContentWidth.progress == 0) {
                binding.seekContentWidth.progress = ((defaultWidthPx - minWidthPx) * 100f / (maxWidthPx - minWidthPx)).roundToInt().coerceIn(0, 100)
            }
            updateWidth(binding.seekContentWidth.progress)
        }

        private fun dpToPx(dp: Int): Int =
            (dp * binding.root.resources.displayMetrics.density).roundToInt()

        private fun bindThinkingProcess(message: ChatMessage) {
            val steps = message.steps
            if (steps.isNotEmpty()) {
                binding.thinkingContainer.visibility = View.VISIBLE

                val sb = StringBuilder()
                for ((stepIndex, step) in steps.withIndex()) {
                    if (step.title.isNotBlank()) {
                        sb.appendLine(step.title)
                    }
                    for (content in step.contents) {
                        when (content.type) {
                            ChatViewModel.TYPE_MODEL_OUTPUT_THINKING -> {
                                sb.appendLine("🤔 ${content.content}")
                            }
                            ChatViewModel.TYPE_MODEL_OUTPUT_DEEP_THINKING -> {
                                sb.appendLine("🧠 ${content.content}")
                            }
                            ChatViewModel.TYPE_MODEL_OUTPUT_CODE -> {
                                sb.appendLine("💻 ```\n${content.content}\n```")
                            }
                            "executing" -> {
                                sb.appendLine("🔧 ${content.content}")
                            }
                            ChatViewModel.TYPE_MEMORY_SEARCH -> {
                                sb.appendLine("📂 ${content.content}")
                            }
                            ChatViewModel.TYPE_AGENT_NEW_RUN -> {
                                sb.appendLine("💭 ${content.content}")
                            }
                            ChatViewModel.TYPE_CARD -> {
                                sb.appendLine("📋 ${content.content.take(80)}")
                            }
                            ChatViewModel.TYPE_ERROR -> {
                                sb.appendLine("❌ ${content.content}")
                            }
                        }
                    }
                    step.metrics?.let { metrics ->
                        sb.appendLine("   ⚡ ${metrics.duration}s | input: ${metrics.stepInputTokens ?: "?"} | output: ${metrics.stepOutputTokens ?: "?"} tokens")
                    }
                    if (stepIndex < steps.size - 1) {
                        sb.appendLine("---")
                    }
                }
                binding.tvThinking.text = sb.toString().trim()
            } else {
                binding.thinkingContainer.visibility = View.GONE
            }
        }

        private fun bindSearchResults(message: ChatMessage) {
            val results = message.searchResults
            if (results.isNotEmpty()) {
                binding.searchResultsContainer.visibility = View.VISIBLE

                val sb = SpannableStringBuilder()
                sb.append("📚 Sources (${results.size}): ")

                val groupedByTool = results.groupBy { it.toolSign }
                for ((toolSign, items) in groupedByTool) {
                    for (item in items) {
                        val start = sb.length
                        val label = "[${toolSign}${item.citeIndex}]"
                        sb.append(label)
                        sb.setSpan(
                            ForegroundColorSpan(0xFF1A73E8.toInt()),
                            start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        sb.setSpan(
                            StyleSpan(Typeface.BOLD),
                            start, start + label.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                        )
                        sb.append(" ")
                    }
                }
                binding.tvSearchResults.text = sb

                binding.searchResultsContainer.setOnClickListener {
                    val expanded = binding.tvSearchResultsDetail.visibility == View.VISIBLE
                    binding.tvSearchResultsDetail.visibility =
                        if (expanded) View.GONE else View.VISIBLE
                    if (!expanded) {
                        val detailSb = StringBuilder()
                        for ((i, r) in results.withIndex()) {
                            detailSb.appendLine("${i + 1}. [${r.toolSign}${r.citeIndex}] ${r.title}")
                            if (r.url.isNotBlank()) {
                                detailSb.appendLine("   ${r.url}")
                            }
                            detailSb.appendLine("   ${r.text.take(200)}")
                            detailSb.appendLine()
                        }
                        binding.tvSearchResultsDetail.text = detailSb.toString().trim()
                    }
                }
            } else {
                binding.searchResultsContainer.visibility = View.GONE
                binding.tvSearchResultsDetail.visibility = View.GONE
            }
        }

        private fun bindWebImages(message: ChatMessage) {
            val images = message.images
            if (images.isNotEmpty()) {
                binding.imagesContainer.visibility = View.VISIBLE
                binding.tvImagesLabel.text = "🖼️ Images (${images.size})"

                Glide.with(binding.ivImagePreview.context)
                    .load(images.firstOrNull())
                    .centerCrop()
                    .placeholder(android.R.color.darker_gray)
                    .error(android.R.drawable.stat_notify_error)
                    .into(binding.ivImagePreview)

                binding.imagesContainer.setOnClickListener {
                    val expanded = binding.imagesGrid.visibility == View.VISIBLE
                    binding.imagesGrid.visibility =
                        if (expanded) View.GONE else View.VISIBLE
                    if (!expanded && images.size > 1) {
                        binding.tvImagesMore.text = "+${images.size - 1} more"
                        binding.tvImagesMore.visibility = View.VISIBLE
                    }
                }
            } else {
                binding.imagesContainer.visibility = View.GONE
                binding.imagesGrid.visibility = View.GONE
            }
        }

        private fun bindTokenMetrics(message: ChatMessage) {
            val allMetrics = message.steps.mapNotNull { it.metrics }
            if (allMetrics.isNotEmpty()) {
                val totalTokens = allMetrics.sumOf { it.totalOutputTokens }
                val totalDuration = allMetrics.sumOf { it.duration }
                binding.tvTokenMetrics.visibility = View.VISIBLE
                binding.tvTokenMetrics.text = "⚡ ${String.format("%.1f", totalDuration)}s | $totalTokens tokens"
            } else {
                binding.tvTokenMetrics.visibility = View.GONE
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