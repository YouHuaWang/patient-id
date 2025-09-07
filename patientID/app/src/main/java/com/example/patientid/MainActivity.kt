package com.example.patientid

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.TextRecognition
import java.util.*
import android.util.Log
import android.view.View
import android.app.ProgressDialog
import androidx.core.app.ActivityCompat

data class PatientInfo(
    val name: String,
    val birthDate: String,
    val medicalId: String,
    val examType: String = ""
)

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PatientID"
        private const val RECORD_AUDIO_PERMISSION_REQUEST = 1
        private const val CAMERA_PERMISSION_REQUEST = 2
        private const val TTS_UTTERANCE_ID = "patient_verification"
    }

    // UI Components
    private lateinit var imageView: ImageView
    private lateinit var textResult: TextView
    private lateinit var btnTakePhoto: Button
    private lateinit var btnSelectImage: Button
    private lateinit var btnReprocessImage: Button

    // Speech & Recognition
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent
    private var isTtsInitialized = false
    private var currentPatientInfo: PatientInfo? = null

    // UI State
    private var progressDialog: ProgressDialog? = null
    private var isProcessing = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initializeUI()
        requestPermissions()
        initializeServices()
    }

    private fun initializeUI() {
        imageView = findViewById(R.id.imageView)
        textResult = findViewById(R.id.textResult)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnSelectImage = findViewById(R.id.btnSelectImage)
        btnReprocessImage = findViewById(R.id.btnReprocessImage)

        btnTakePhoto.setOnClickListener { handleTakePhoto() }
        btnSelectImage.setOnClickListener { handleSelectImage() }
        btnReprocessImage.setOnClickListener { reprocessCurrentImage() }

        // 初始狀態下隱藏重新處理按鈕
        btnReprocessImage.visibility = View.GONE
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissionsToRequest.toTypedArray(),
                RECORD_AUDIO_PERMISSION_REQUEST
            )
        }
    }

    private fun initializeServices() {
        initializeTTS()
        initializeSpeechRecognizer()
    }

    private fun initializeTTS() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.TRADITIONAL_CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Log.w(TAG, "繁體中文不支援，嘗試使用簡體中文")
                    tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                }

                tts?.setSpeechRate(0.8f) // 稍微慢一點以確保清晰度

                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == TTS_UTTERANCE_ID) {
                            runOnUiThread {
                                startSpeechRecognition()
                            }
                        }
                    }

                    override fun onError(utteranceId: String?) {
                        Log.e(TAG, "TTS 錯誤: $utteranceId")
                        runOnUiThread {
                            showToast("語音播放錯誤，請重新嘗試")
                            resetProcessingState()
                        }
                    }

                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS 開始播放")
                    }
                })

                isTtsInitialized = true
                Log.i(TAG, "TTS 初始化成功")
            } else {
                Log.e(TAG, "TTS 初始化失敗")
                showToast("語音系統初始化失敗")
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-TW")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            Log.i(TAG, "語音識別器初始化成功")
        } else {
            Log.e(TAG, "設備不支援語音識別")
            showToast("此設備不支援語音識別功能")
        }
    }

    private fun handleTakePhoto() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED) {
            requestPermissions()
            return
        }

        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) != null) {
            takePhotoLauncher.launch(intent)
        } else {
            showToast("找不到相機應用程式")
        }
    }

    private fun handleSelectImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        if (intent.resolveActivity(packageManager) != null) {
            selectImageLauncher.launch(intent)
        } else {
            showToast("找不到圖片選擇應用程式")
        }
    }

    private fun reprocessCurrentImage() {
        val drawable = imageView.drawable
        if (drawable != null) {
            // 從ImageView獲取當前圖片並重新處理
            imageView.buildDrawingCache()
            val bitmap = imageView.drawingCache
            if (bitmap != null) {
                processImage(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false))
            }
        } else {
            showToast("請先選擇或拍攝圖片")
        }
    }

    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                try {
                    val bitmap = result.data?.extras?.get("data") as? Bitmap
                    if (bitmap != null) {
                        imageView.setImageBitmap(bitmap)
                        btnReprocessImage.visibility = View.VISIBLE
                        processImage(bitmap)
                    } else {
                        showToast("無法獲取拍攝的圖片")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "處理拍攝圖片時發生錯誤", e)
                    showToast("處理拍攝圖片時發生錯誤")
                }
            }
        }

    private val selectImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        val bitmap = getBitmapFromUri(uri)
                        imageView.setImageBitmap(bitmap)
                        btnReprocessImage.visibility = View.VISIBLE
                        processImage(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "處理選擇的圖片時發生錯誤", e)
                        showToast("處理選擇的圖片時發生錯誤：${e.message}")
                    }
                }
            }
        }

    private fun getBitmapFromUri(uri: Uri): Bitmap {
        return if (Build.VERSION.SDK_INT < 28) {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.getBitmap(this.contentResolver, uri)
        } else {
            val source = ImageDecoder.createSource(this.contentResolver, uri)
            ImageDecoder.decodeBitmap(source)
        }
    }

    private fun processImage(bitmap: Bitmap) {
        if (isProcessing) {
            Log.w(TAG, "圖片處理中，忽略重複請求")
            return
        }

        isProcessing = true
        showProgressDialog("正在分析圖片...")

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    handleOCRSuccess(visionText.text)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "OCR 處理失敗", exception)
                    handleOCRFailure(exception.message ?: "未知錯誤")
                }
        } catch (e: Exception) {
            Log.e(TAG, "圖片處理發生異常", e)
            handleOCRFailure("圖片處理異常：${e.message}")
        }
    }

    private fun handleOCRSuccess(recognizedText: String) {
        hideProgressDialog()
        resetProcessingState()

        textResult.text = recognizedText
        Log.d(TAG, "OCR 結果: $recognizedText")

        val patientInfo = extractPatientInfo(recognizedText)
        if (patientInfo != null) {
            currentPatientInfo = patientInfo
            val speechText = buildSpeechText(patientInfo)
            Log.i(TAG, "準備播放語音: $speechText")
            speakText(speechText)
        } else {
            showToast("無法識別病患資訊，請確認圖片包含完整的病歷資料")
            Log.w(TAG, "無法從OCR結果提取病患資訊")
        }
    }

    private fun handleOCRFailure(errorMessage: String) {
        hideProgressDialog()
        resetProcessingState()
        showToast("圖片識別失敗：$errorMessage")
        textResult.text = "識別失敗，請重新嘗試"
    }

    private fun extractPatientInfo(text: String): PatientInfo? {
        Log.d(TAG, "開始解析病患資訊: $text")

        // 更靈活的正則表達式匹配
        val namePatterns = listOf(
            Regex("""姓名[:：]?\s*([^\s\n\r]{2,10})"""),
            Regex("""病患[:：]?\s*([^\s\n\r]{2,10})"""),
            Regex("""患者[:：]?\s*([^\s\n\r]{2,10})""")
        )

        val birthPatterns = listOf(
            Regex("""出生[:：]?\s*(\d{4}[年/-]\d{1,2}[月/-]\d{1,2}[日]?)"""),
            Regex("""生日[:：]?\s*(\d{4}[年/-]\d{1,2}[月/-]\d{1,2}[日]?)"""),
            Regex("""(\d{4}年\d{1,2}月\d{1,2}日)"""),
            Regex("""(\d{4}/\d{1,2}/\d{1,2})"""),
            Regex("""(\d{4}-\d{1,2}-\d{1,2})""")
        )

        val idPatterns = listOf(
            Regex("""病歷號[:：]?\s*([A-Za-z0-9]{4,15})"""),
            Regex("""病號[:：]?\s*([A-Za-z0-9]{4,15})"""),
            Regex("""編號[:：]?\s*([A-Za-z0-9]{4,15})"""),
            Regex("""ID[:：]?\s*([A-Za-z0-9]{4,15})""")
        )

        val examPatterns = listOf(
            Regex("""檢查[:：]?\s*([^\s\n\r]{2,20})"""),
            Regex("""項目[:：]?\s*([^\s\n\r]{2,20})""")
        )

        var name = ""
        var birthDate = ""
        var medicalId = ""
        var examType = ""

        // 嘗試匹配姓名
        for (pattern in namePatterns) {
            val match = pattern.find(text)
            if (match != null) {
                name = match.groupValues[1].trim()
                Log.d(TAG, "找到姓名: $name")
                break
            }
        }

        // 嘗試匹配出生日期
        for (pattern in birthPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                birthDate = match.groupValues[1].trim()
                Log.d(TAG, "找到出生日期: $birthDate")
                break
            }
        }

        // 嘗試匹配病歷號
        for (pattern in idPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                medicalId = match.groupValues[1].trim()
                Log.d(TAG, "找到病歷號: $medicalId")
                break
            }
        }

        // 嘗試匹配檢查項目
        for (pattern in examPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                examType = match.groupValues[1].trim()
                Log.d(TAG, "找到檢查項目: $examType")
                break
            }
        }

        return if (name.isNotEmpty() && birthDate.isNotEmpty() && medicalId.isNotEmpty()) {
            PatientInfo(name, birthDate, medicalId, examType)
        } else {
            Log.w(TAG, "缺少必要資訊 - 姓名: $name, 出生日期: $birthDate, 病歷號: $medicalId")
            null
        }
    }

    private fun buildSpeechText(info: PatientInfo): String {
        val greeting = when {
            info.name.contains("先生") -> info.name
            info.name.contains("小姐") || info.name.contains("女士") -> info.name
            else -> "${info.name}先生"
        }

        val examText = if (info.examType.isNotEmpty()) {
            "${info.examType}檢查"
        } else {
            "檢查"
        }

        return "${greeting}您好，等一下將進行${examText}。現在向您核對病歷資訊。" +
                "姓名為：${info.name}。出生年月日為：${info.birthDate}。病歷號為：${info.medicalId}。" +
                "如果資料正確，請說「是」或「正確」。"
    }

    private fun speakText(text: String) {
        if (!isTtsInitialized) {
            showToast("語音系統尚未準備就緒，請稍候再試")
            return
        }

        tts?.let { ttsEngine ->
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, TTS_UTTERANCE_ID)
            }

            val result = ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, params, TTS_UTTERANCE_ID)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS 播放失敗")
                showToast("語音播放失敗")
            } else {
                Log.i(TAG, "開始播放語音")
            }
        }
    }

    private fun startSpeechRecognition() {
        if (speechRecognizer == null) {
            Log.e(TAG, "語音識別器未初始化")
            showToast("語音識別功能不可用")
            return
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            showToast("缺少錄音權限")
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "準備接收語音")
                runOnUiThread {
                    showToast("請說出「是」或「正確」確認資料")
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "語音識別結果: $matches")

                if (matches != null && matches.isNotEmpty()) {
                    val userResponse = matches[0].trim().lowercase()
                    val isConfirmed = listOf("是", "正確", "對", "沒錯", "確認", "yes", "correct")
                        .any { userResponse.contains(it) }

                    if (isConfirmed) {
                        runOnUiThread {
                            showToast("病患資料核對成功！")
                            // 這裡可以添加成功後的後續操作
                            handleVerificationSuccess()
                        }
                    } else {
                        runOnUiThread {
                            showToast("請重新確認資料或重新掃描")
                        }
                    }
                } else {
                    runOnUiThread {
                        showToast("未能識別語音，請再次嘗試")
                    }
                }
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音訊錯誤"
                    SpeechRecognizer.ERROR_CLIENT -> "客戶端錯誤"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "權限不足"
                    SpeechRecognizer.ERROR_NETWORK -> "網路錯誤"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "網路超時"
                    SpeechRecognizer.ERROR_NO_MATCH -> "無法匹配"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "識別器忙碌"
                    SpeechRecognizer.ERROR_SERVER -> "伺服器錯誤"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "語音超時"
                    else -> "未知錯誤 ($error)"
                }
                Log.e(TAG, "語音識別錯誤: $errorMessage")
                runOnUiThread {
                    showToast("語音識別失敗: $errorMessage，請重新嘗試")
                }
            }

            override fun onBeginningOfSpeech() {
                Log.d(TAG, "開始接收語音")
            }

            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Log.d(TAG, "語音接收結束")
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {
                // 可以顯示部分識別結果
                val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "部分識別結果: $partials")
            }
            override fun onRmsChanged(rmsdB: Float) {}
        })

        try {
            speechRecognizer?.startListening(speechIntent)
        } catch (e: Exception) {
            Log.e(TAG, "啟動語音識別失敗", e)
            showToast("啟動語音識別失敗")
        }
    }

    private fun handleVerificationSuccess() {
        currentPatientInfo?.let { info ->
            // 這裡可以添加驗證成功後的邏輯
            // 例如：保存驗證記錄、跳轉到下一個畫面等
            Log.i(TAG, "病患 ${info.name} 身份驗證成功")

            // 顯示成功訊息
            val successMessage = "病患身份驗證完成\n" +
                    "姓名：${info.name}\n" +
                    "出生日期：${info.birthDate}\n" +
                    "病歷號：${info.medicalId}"

            textResult.append("\n\n=== 驗證成功 ===\n$successMessage")
        }
    }

    private fun showProgressDialog(message: String) {
        hideProgressDialog()
        progressDialog = ProgressDialog(this).apply {
            setMessage(message)
            setCancelable(false)
            show()
        }
    }

    private fun hideProgressDialog() {
        progressDialog?.dismiss()
        progressDialog = null
    }

    private fun resetProcessingState() {
        isProcessing = false
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            RECORD_AUDIO_PERMISSION_REQUEST -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    Log.i(TAG, "所有權限已獲得")
                    initializeServices()
                } else {
                    showToast("應用程式需要相關權限才能正常運作")
                }
            }
        }
    }

    override fun onDestroy() {
        try {
            tts?.stop()
            tts?.shutdown()
            speechRecognizer?.destroy()
            hideProgressDialog()
        } catch (e: Exception) {
            Log.e(TAG, "清理資源時發生錯誤", e)
        }
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        // 停止語音相關服務以節省資源
        tts?.stop()
        speechRecognizer?.stopListening()
    }
}