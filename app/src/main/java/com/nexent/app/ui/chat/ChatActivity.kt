package com.nexent.app.ui.chat

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

data class SpeechRecognitionConfig(
    val language: String,
    val prompt: String,
    val maxResults: Int,
)

class ChatActivity : AppCompatActivity() {

    private lateinit var binding: ActivityChatBinding
    private val viewModel: ChatViewModel by viewModels()
    private lateinit var messageAdapter: MessageAdapter

    private var pendingCameraUri: Uri? = null
    private var shouldAutoScrollToBottom = true

    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechRecognizerIntent: Intent
    private var isVoiceButtonPressed: Boolean = false
    private val recordAudioPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            Log.d("ChatActivity", "RECORD_AUDIO permission granted")
            // Only start recognition if the user is still pressing the voice button
            if (isVoiceButtonPressed) startVoiceRecognition()
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
            val mimeType = contentResolver.getType(it) ?: ""
            val isAudio = mimeType.lowercase(Locale.getDefault()).startsWith("audio/")
            if (isAudio) {
                viewModel.sendVoiceMessage(it, "")
            } else {
                viewModel.sendImageMessage(it, "")
            }
        }
    }

    // Camera
    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) {
            pendingCameraUri?.let { uri ->
                viewModel.sendImageMessage(uri, "")
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

    // Voice input
    private val voiceLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult

        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val spokenText = matches?.firstOrNull()?.trim().orEmpty()
        if (spokenText.isNotBlank()) {
            binding.etMessage.setText(spokenText)
            binding.etMessage.setSelection(spokenText.length)
        } else {
            Toast.makeText(this, "未识别到语音内容", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val agentName = intent.getStringExtra(EXTRA_AGENT_NAME) ?: "AI Assistant"
        val agentTitle = intent.getStringExtra(EXTRA_AGENT_TITLE) ?: agentName

        setupHeader(agentTitle)
        setupRecyclerView()
        setupSpeechRecognizer()
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
        private val GOOGLE_VOICE_PACKAGES = listOf(
            "com.google.android.googlequicksearchbox",
            "com.google.android.apps.googlevoiceassistant"
        )

        fun createSpeechRecognitionConfig(): SpeechRecognitionConfig = SpeechRecognitionConfig(
            language = "zh-CN",
            prompt = "说出你想问的问题...",
            maxResults = 1,
        )

        fun createSpeechRecognitionIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            val config = createSpeechRecognitionConfig()
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.language)
            putExtra(RecognizerIntent.EXTRA_PROMPT, config.prompt)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, config.maxResults)
        }

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
            val text = binding.etMessage.text?.toString()?.trim() ?: return@setOnClickListener
            if (text.isNotBlank()) {
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
                        startVoiceRecognition()
                    } else {
                        recordAudioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    isVoiceButtonPressed = false
                    stopVoiceRecognition()
                    true
                }
                else -> false
            }
        }

        // Voice input via the add button
        binding.btnAdd.setOnClickListener {
            launchVoiceRecognition()
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

        // Mode dropdown selector
        binding.modeSelector.setOnClickListener { showModePopup() }
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

    private fun setupSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Log.d("ChatActivity", "onReadyForSpeech")
                }
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {
                    // update visualizer based on rms
                    Log.d("ChatActivity", "onRmsChanged: $rmsdB")
                    runOnUiThread {
                        binding.voiceLevelView.visibility = View.VISIBLE
                        updateVoiceLevel(rmsdB)
                    }
                }
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {
                    binding.btnVoice.alpha = 1f
                    Log.d("ChatActivity", "onEndOfSpeech")
                    runOnUiThread {
                        binding.voiceLevelView.visibility = View.GONE
                    }
                }
                override fun onError(error: Int) {
                    binding.btnVoice.alpha = 1f
                    Log.d("ChatActivity", "onError: $error")
                    val message = when (error) {
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络错误，请稍后重试"
                        SpeechRecognizer.ERROR_AUDIO -> "录音发生错误"
                        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "未识别到语音"
                        else -> "语音识别失败"
                    }
                    Toast.makeText(this@ChatActivity, message, Toast.LENGTH_SHORT).show()
                    runOnUiThread {
                        binding.voiceLevelView.visibility = View.GONE
                    }
                }
                override fun onPartialResults(partialResults: Bundle?) {
                    val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val partialText = matches?.firstOrNull()
                    if (!partialText.isNullOrBlank()) {
                        binding.etMessage.setText(partialText)
                        binding.etMessage.setSelection(partialText.length)
                        Log.d("ChatActivity", "onPartialResults: $partialText")
                    }
                }
                override fun onResults(results: Bundle?) {
                    binding.btnVoice.alpha = 1f
                    Log.d("ChatActivity", "onResults")
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val spokenText = matches?.firstOrNull().orEmpty()
                    if (spokenText.isNotBlank()) {
                        binding.etMessage.setText(spokenText)
                        binding.etMessage.setSelection(spokenText.length)
                        runOnUiThread {
                            binding.voiceLevelView.visibility = View.GONE
                        }
                    } else {
                        Toast.makeText(this@ChatActivity, "未识别到语音内容", Toast.LENGTH_SHORT).show()
                    }
                }
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
        }

        speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
    }

    private fun startVoiceRecognition() {
        binding.btnVoice.alpha = 0.6f
        Toast.makeText(this, "按住说话，松开结束录音", Toast.LENGTH_SHORT).show()
        launchVoiceRecognition()
    }

    private fun launchVoiceRecognition() {
        val intent = createSpeechRecognitionIntent()
        val availablePackage = GOOGLE_VOICE_PACKAGES.firstOrNull { packageName ->
            packageManager.resolveActivity(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).setPackage(packageName),
                PackageManager.MATCH_DEFAULT_ONLY
            ) != null
        }
        if (availablePackage != null) {
            intent.setPackage(availablePackage)
        }

        try {
            voiceLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            binding.etMessage.requestFocus()
            Toast.makeText(this, "当前设备没有可用的语音输入服务，请直接输入文字", Toast.LENGTH_LONG).show()
        }
    }

    private fun stopVoiceRecognition() {
        binding.btnVoice.alpha = 1f
    }

    private fun selectMode(mode: String) {
        if (mode == "deep") {
            binding.tvModeLabel.text = getString(R.string.mode_deep)
        } else {
            binding.tvModeLabel.text = getString(R.string.mode_quick)
        }
        viewModel.setThinkMode(mode)
    }

    private fun updateVoiceLevel(rmsdB: Float) {
        // Normalize rmsdB to 0..1 (typical Android RMS is small negative to positive)
        val level = ((rmsdB + 10f) / 20f).coerceIn(0f, 1f)
        val dots = listOf(binding.voiceDot1, binding.voiceDot2, binding.voiceDot3)
        for ((i, dot) in dots.withIndex()) {
            val thresholdStart = i * (1f / dots.size)
            val thresholdEnd = (i + 1) * (1f / dots.size)
            val factor = ((level - thresholdStart) / (thresholdEnd - thresholdStart)).coerceIn(0f, 1f)
            val scale = 1f + factor * 1.4f
            dot.scaleY = scale
            dot.alpha = 0.5f + 0.5f * factor
        }
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
        speechRecognizer?.destroy()
        speechRecognizer = null
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
