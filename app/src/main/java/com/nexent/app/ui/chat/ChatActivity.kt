package com.nexent.app.ui.chat

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.nexent.app.R
import com.nexent.app.databinding.ActivityChatBinding
import java.io.File
import java.util.Locale

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter

    private var pendingCameraUri: Uri? = null
    private var shouldAutoScrollToBottom = true

    /** Currently selected attachment waiting to be sent */
    private var pendingAttachmentUri: Uri? = null
    /** MIME type of the pending attachment */
    private var pendingAttachmentMimeType: String? = null

    private var mediaRecorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var isRecording: Boolean = false
    private var isVoiceButtonPressed: Boolean = false
    private var recordingStartTime: Long = 0
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d("ChatActivity", "RECORD_AUDIO permission granted")
            // Only start recording if the user is still pressing the voice button
            if (isVoiceButtonPressed) startRecording()
        } else {
            Log.d("ChatActivity", "RECORD_AUDIO permission denied")
            Toast.makeText(this, "需要麦克风权限才能使用语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    // File picker for images or audio
    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            setPendingAttachment(it)
        }
    }

    // Camera
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraUri?.let { uri ->
                setPendingAttachment(uri)
            }
        }
    }

    // Camera permission
    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchCamera()
        else Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val agentName = intent.getStringExtra(EXTRA_AGENT_NAME) ?: "AI Assistant"
        val agentTitle = intent.getStringExtra(EXTRA_AGENT_TITLE) ?: agentName

        setupHeader(agentTitle)
        setupRecyclerView()
        setupInputArea()
        observeViewModel()

        viewModel.init(agentName)
    }

    private fun setupHeader(agentTitle: String) {
        binding.btnBack.setOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // Show Chinese title
        binding.tvAgentSubtitle.text = agentTitle

        // Assign avatar color based on agent name hash
        val avatarIndex = (agentTitle.hashCode().mod(avatarBgList.size).let {
            if (it < 0) it + avatarBgList.size else it
        })
        binding.flChatAvatar.setBackgroundResource(avatarBgList[avatarIndex])

        // Set dynamic input hint with Chinese name
        binding.etMessage.hint = getString(R.string.input_hint, agentTitle)
    }

    companion object {
        const val EXTRA_AGENT_NAME = "extra_agent_name"
        const val EXTRA_AGENT_TITLE = "extra_agent_title"
        const val EXTRA_AGENT_DESC = "extra_agent_desc"

        private val avatarBgList = intArrayOf(
            R.drawable.bg_avatar_01, R.drawable.bg_avatar_02,
            R.drawable.bg_avatar_03, R.drawable.bg_avatar_04,
            R.drawable.bg_avatar_05, R.drawable.bg_avatar_06,
            R.drawable.bg_avatar_07, R.drawable.bg_avatar_08
        )
    }

    private fun setupRecyclerView() {
        messageAdapter = MessageAdapter()
        binding.rvMessages.apply {
            layoutManager = LinearLayoutManager(this@ChatActivity).apply {
                stackFromEnd = true
            }
            adapter = messageAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                    val lastVisible = layoutManager.findLastVisibleItemPosition()
                    val totalCount = recyclerView.adapter?.itemCount ?: 0
                    shouldAutoScrollToBottom = lastVisible >= totalCount - 1
                }
            })
        }
    }

    private fun setupInputArea() {
        binding.btnSend.setOnClickListener {
            val text = binding.etMessage.text?.toString()?.trim() ?: ""
            val attachment = pendingAttachmentUri

            if (text.isBlank() && attachment == null) return@setOnClickListener

            if (attachment != null) {
                val mimeType = pendingAttachmentMimeType?.lowercase(Locale.getDefault()) ?: ""
                if (mimeType.startsWith("audio/")) {
                    viewModel.sendVoiceMessage(attachment, text = text)
                } else {
                    viewModel.sendImageMessage(attachment, text)
                }
                binding.etMessage.setText("")
                clearPendingAttachment()
            } else if (text.isNotBlank()) {
                viewModel.sendMessage(text)
                binding.etMessage.setText("")
            }
        }

        binding.btnVoice.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    isVoiceButtonPressed = true
                    if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED
                    ) {
                        startRecording()
                    } else {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isVoiceButtonPressed = false
                    stopRecordingAndUpload()
                    true
                }
                else -> false
            }
        }

        // Voice input via the add button (tap to record, tap again to stop & upload)
        binding.btnAdd.setOnClickListener {
            if (isRecording) {
                stopRecordingAndUpload()
            } else {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED
                ) {
                    startRecording()
                } else {
                    isVoiceButtonPressed = true
                    recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        // File picker via image button for both images and audio
        binding.btnImage.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        // Camera via edit button
        binding.btnEdit.setOnClickListener {
            checkCameraPermission()
        }

        // Emoji button - insert emoji at cursor (simple emoji picker)
        binding.btnEmoji.setOnClickListener {
            val current = binding.etMessage.text ?: return@setOnClickListener
            current.insert(current.length, "😊")
        }

        // Remove attachment button
        binding.btnRemoveAttachment.setOnClickListener {
            clearPendingAttachment()
        }

        // Mode dropdown selector
        binding.modeSelector.setOnClickListener { showModePopup() }
    }

    /**
     * Set the pending attachment and show the preview UI above the input field.
     */
    private fun setPendingAttachment(uri: Uri) {
        pendingAttachmentUri = uri
        pendingAttachmentMimeType = contentResolver.getType(uri)

        // Show thumbnail: decode image for image types, show icon for others
        val mimeType = pendingAttachmentMimeType?.lowercase(Locale.getDefault()) ?: ""
        if (mimeType.startsWith("image/")) {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bitmap = BitmapFactory.decodeStream(inputStream)
                inputStream?.close()
                binding.ivAttachmentThumbnail.setImageBitmap(bitmap)
            } catch (e: Exception) {
                binding.ivAttachmentThumbnail.setImageResource(R.drawable.ic_image)
            }
        } else if (mimeType.startsWith("audio/")) {
            binding.ivAttachmentThumbnail.setImageResource(R.drawable.ic_mic)
        } else {
            binding.ivAttachmentThumbnail.setImageResource(R.drawable.ic_image)
        }

        // Show file name
        val fileName = queryFileName(uri) ?: "附件"
        binding.tvAttachmentName.text = fileName

        binding.attachmentPreview.visibility = View.VISIBLE
    }

    /**
     * Clear the pending attachment and hide the preview UI.
     */
    private fun clearPendingAttachment() {
        pendingAttachmentUri = null
        binding.attachmentPreview.visibility = View.GONE
        binding.ivAttachmentThumbnail.setImageDrawable(null)
        binding.tvAttachmentName.text = ""
    }

    private fun queryFileName(uri: Uri): String? {
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(
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

    private fun showModePopup() {
        val popup = android.widget.PopupMenu(this, binding.modeSelector)
        popup.menu.add(0, 0, 0, getString(R.string.mode_quick))
        popup.menu.add(0, 1, 1, getString(R.string.mode_deep))
        popup.setOnMenuItemClickListener { item ->
            val mode = if (item.itemId == 1) "deep" else "quick"
            selectMode(mode)
            true
        }
        popup.show()
    }

    private fun startRecording() {
        if (isRecording) return
        try {
            val outputDir = externalCacheDir ?: cacheDir
            val file = File(outputDir, "nexent_voice_${System.currentTimeMillis()}.m4a")
            mediaRecorder = MediaRecorder(applicationContext).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recordingFile = file
            recordingStartTime = System.currentTimeMillis()
            isRecording = true
            binding.btnVoice.alpha = 0.6f
            binding.btnAdd.alpha = 0.6f
            binding.voiceLevelView.visibility = View.VISIBLE
            Toast.makeText(this, "正在录音，松开结束并发送", Toast.LENGTH_SHORT).show()
            Log.d("ChatActivity", "Recording started: ${file.absolutePath}")
        } catch (e: Exception) {
            Log.e("ChatActivity", "startRecording failed", e)
            isRecording = false
            recordingFile = null
            mediaRecorder?.release()
            mediaRecorder = null
            Toast.makeText(this, "无法开始录音: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecordingAndUpload() {
        if (!isRecording) return
        val recorder = mediaRecorder
        val file = recordingFile
        val duration = System.currentTimeMillis() - recordingStartTime
        isRecording = false
        binding.btnVoice.alpha = 1f
        binding.btnAdd.alpha = 1f
        binding.voiceLevelView.visibility = View.GONE
        try {
            recorder?.stop()
        } catch (e: Exception) {
            Log.e("ChatActivity", "stopRecording failed", e)
        } finally {
            recorder?.release()
            mediaRecorder = null
        }

        // 检查录音时长是否足够（至少1秒）
        if (duration < 1000) {
            file?.delete()
            Toast.makeText(this, "录音时间太短，请按住按钮录音", Toast.LENGTH_SHORT).show()
            return
        }

        if (file != null && file.exists() && file.length() > 0) {
            Log.d("ChatActivity", "Recording stopped, uploading: ${file.absolutePath}, size=${file.length()}")
            // 获取录音时长
            val audioDurationMs = getAudioDuration(file.absolutePath)
            // Hand the recorded audio to the same upload flow used for other attachments
            viewModel.sendVoiceMessage(Uri.fromFile(file), audioDuration = audioDurationMs)
        } else {
            Toast.makeText(this, "录音文件为空，已取消", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getAudioDuration(filePath: String): Int {
        return try {
            val player = MediaPlayer()
            player.setDataSource(filePath)
            player.prepare()
            val duration = player.duration.coerceAtLeast(0)
            player.release()
            duration
        } catch (e: Exception) {
            Log.e("ChatActivity", "getAudioDuration failed", e)
            0
        }
    }

    private fun selectMode(mode: String) {
        if (mode == "deep") {
            binding.tvModeLabel.text = getString(R.string.mode_deep)
        } else {
            binding.tvModeLabel.text = getString(R.string.mode_quick)
        }
        viewModel.setThinkMode(mode)
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            launchCamera()
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun launchCamera() {
        val photoFile = File(externalCacheDir, "nexent_photo_${System.currentTimeMillis()}.jpg")
        pendingCameraUri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            photoFile
        )
        takePictureLauncher.launch(pendingCameraUri!!)
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isRecording) {
            try {
                mediaRecorder?.stop()
            } catch (_: Exception) { }
            mediaRecorder?.release()
            mediaRecorder = null
            recordingFile?.delete()
            isRecording = false
        }
    }

    private fun observeViewModel() {
        viewModel.messages.observe(this) { messages ->
            val shouldScroll = shouldAutoScrollToBottom && messages.isNotEmpty()
            messageAdapter.submitList(messages.toList()) {
                if (shouldScroll) {
                    binding.rvMessages.post {
                        binding.rvMessages.scrollToPosition(messages.size - 1)
                    }
                }
            }
        }

        viewModel.isLoading.observe(this) { isLoading ->
            binding.btnSend.isEnabled = !isLoading
        }

        viewModel.error.observe(this) { error ->
            if (!error.isNullOrBlank()) {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show()
            }
        }
    }
}