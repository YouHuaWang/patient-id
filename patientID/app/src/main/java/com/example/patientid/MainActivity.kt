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
import android.widget.LinearLayout
import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AlertDialog

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
        private const val PREFS_NAME = "PatientIDPrefs"
        private const val KEY_LANGUAGE = "selected_language"
        private const val LANG_CHINESE = "zh"
        private const val LANG_ENGLISH = "en"
    }

    // UI Components
    private lateinit var imageView: ImageView
    private lateinit var textResult: TextView
    private lateinit var btnTakePhoto: Button
    private lateinit var btnSelectImage: Button
    private lateinit var btnReprocessImage: Button
    private lateinit var llPlaceholder: LinearLayout
    private lateinit var btnLanguage: Button

    // Speech & Recognition
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent
    private var isTtsInitialized = false
    private var currentPatientInfo: PatientInfo? = null

    // UI State
    private var progressDialog: ProgressDialog? = null
    private var isProcessing = false

    // Language Support
    private lateinit var prefs: SharedPreferences
    private var currentLanguage: String = LANG_CHINESE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化語言設定
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentLanguage = prefs.getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE
        updateLocale(currentLanguage)

        setContentView(R.layout.activity_main)

        initializeUI()
        requestPermissions()
        initializeServices()
    }

    private fun updateLocale(language: String) {
        val locale = when (language) {
            LANG_ENGLISH -> Locale.ENGLISH
            else -> Locale.TRADITIONAL_CHINESE
        }

        val config = resources.configuration
        config.setLocale(locale)
        resources.updateConfiguration(config, resources.displayMetrics)
    }

    private fun initializeUI() {
        imageView = findViewById(R.id.imageView)
        textResult = findViewById(R.id.textResult)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnSelectImage = findViewById(R.id.btnSelectImage)
        btnReprocessImage = findViewById(R.id.btnReprocessImage)
        llPlaceholder = findViewById(R.id.llPlaceholder)
        btnLanguage = findViewById(R.id.btnLanguage)

        btnTakePhoto.setOnClickListener { handleTakePhoto() }
        btnSelectImage.setOnClickListener { handleSelectImage() }
        btnReprocessImage.setOnClickListener { reprocessCurrentImage() }
        btnLanguage.setOnClickListener { showLanguageDialog() }

        // 初始狀態下隱藏重新處理按鈕
        btnReprocessImage.visibility = View.GONE

        // 更新UI文字
        updateUITexts()
    }

    private fun updateUITexts() {
        when (currentLanguage) {
            LANG_ENGLISH -> {
                btnTakePhoto.text = "📷 Take Photo"
                btnSelectImage.text = "🖼️ Select Image"
                btnReprocessImage.text = "🔄 Reprocess"
                btnLanguage.text = "🌐 中文"
                textResult.text = "Waiting for image recognition..."
                findViewById<TextView>(R.id.tvTitle).text = "Patient Verification System"
                findViewById<TextView>(R.id.tvResultHeader).text = "Recognition Result"
            }
            else -> {
                btnTakePhoto.text = "📷 拍攝醫令單"
                btnSelectImage.text = "🖼️ 選擇圖片"
                btnReprocessImage.text = "🔄 重新分析"
                btnLanguage.text = "🌐 English"
                textResult.text = "等待圖片識別..."
                findViewById<TextView>(R.id.tvTitle).text = "病患身份驗證系統"
                findViewById<TextView>(R.id.tvResultHeader).text = "識別結果"
            }
        }
    }

    private fun showLanguageDialog() {
        val languages = if (currentLanguage == LANG_CHINESE) {
            arrayOf("繁體中文", "English")
        } else {
            arrayOf("Traditional Chinese", "English")
        }

        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (currentLanguage == LANG_CHINESE) "選擇語言" else "Select Language")
        builder.setItems(languages) { _, which ->
            val newLanguage = if (which == 0) LANG_CHINESE else LANG_ENGLISH
            if (newLanguage != currentLanguage) {
                currentLanguage = newLanguage
                prefs.edit().putString(KEY_LANGUAGE, currentLanguage).apply()
                updateLocale(currentLanguage)
                updateUITexts()
                initializeServices() // 重新初始化語音服務以更新語言
            }
        }
        builder.show()
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
        tts?.shutdown() // 停止舊的TTS實例

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val locale = when (currentLanguage) {
                    LANG_ENGLISH -> Locale.US
                    else -> Locale.TRADITIONAL_CHINESE
                }

                val result = tts?.setLanguage(locale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    if (currentLanguage == LANG_CHINESE) {
                        Log.w(TAG, "繁體中文不支援，嘗試使用簡體中文")
                        tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    }
                }

                tts?.setSpeechRate(0.8f)

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
                            showToast(if (currentLanguage == LANG_CHINESE) "語音播放錯誤，請重新嘗試" else "TTS error, please try again")
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
                showToast(if (currentLanguage == LANG_CHINESE) "語音系統初始化失敗" else "TTS initialization failed")
            }
        }
    }

    private fun initializeSpeechRecognizer() {
        speechRecognizer?.destroy() // 清理舊的實例

        if (SpeechRecognizer.isRecognitionAvailable(this)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (currentLanguage == LANG_ENGLISH) "en-US" else "zh-TW")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            }
            Log.i(TAG, "語音識別器初始化成功")
        } else {
            Log.e(TAG, "設備不支援語音識別")
            showToast(if (currentLanguage == LANG_CHINESE) "此設備不支援語音識別功能" else "Speech recognition not supported")
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
            showToast(if (currentLanguage == LANG_CHINESE) "找不到相機應用程式" else "Camera app not found")
        }
    }

    private fun handleSelectImage() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        if (intent.resolveActivity(packageManager) != null) {
            selectImageLauncher.launch(intent)
        } else {
            showToast(if (currentLanguage == LANG_CHINESE) "找不到圖片選擇應用程式" else "Image picker not found")
        }
    }

    private fun reprocessCurrentImage() {
        val drawable = imageView.drawable
        if (drawable != null) {
            imageView.isDrawingCacheEnabled = true
            imageView.buildDrawingCache()
            val bitmap = imageView.drawingCache
            if (bitmap != null) {
                processImage(bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false))
            }
            imageView.isDrawingCacheEnabled = false
        } else {
            showToast(if (currentLanguage == LANG_CHINESE) "請先選擇或拍攝圖片" else "Please select or take a photo first")
        }
    }

    private val takePhotoLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                try {
                    val bitmap = result.data?.extras?.get("data") as? Bitmap
                    if (bitmap != null) {
                        setImageAndHidePlaceholder(bitmap)
                        btnReprocessImage.visibility = View.VISIBLE
                        processImage(bitmap)
                    } else {
                        showToast(if (currentLanguage == LANG_CHINESE) "無法獲取拍攝的圖片" else "Unable to get captured image")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "處理拍攝圖片時發生錯誤", e)
                    showToast(if (currentLanguage == LANG_CHINESE) "處理拍攝圖片時發生錯誤" else "Error processing captured image")
                }
            }
        }

    private val selectImageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data?.let { uri ->
                    try {
                        val bitmap = getBitmapFromUri(uri)
                        setImageAndHidePlaceholder(bitmap)
                        btnReprocessImage.visibility = View.VISIBLE
                        processImage(bitmap)
                    } catch (e: Exception) {
                        Log.e(TAG, "處理選擇的圖片時發生錯誤", e)
                        showToast(if (currentLanguage == LANG_CHINESE) "處理選擇的圖片時發生錯誤：${e.message}" else "Error processing selected image: ${e.message}")
                    }
                }
            }
        }

    private fun setImageAndHidePlaceholder(bitmap: Bitmap) {
        imageView.setImageBitmap(bitmap)
        llPlaceholder.visibility = View.GONE // 隱藏佔位符
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
        showProgressDialog(if (currentLanguage == LANG_CHINESE) "正在分析圖片..." else "Analyzing image...")

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    handleOCRSuccess(visionText.text)
                }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "OCR 處理失敗", exception)
                    handleOCRFailure(exception.message ?: if (currentLanguage == LANG_CHINESE) "未知錯誤" else "Unknown error")
                }
        } catch (e: Exception) {
            Log.e(TAG, "圖片處理發生異常", e)
            handleOCRFailure(if (currentLanguage == LANG_CHINESE) "圖片處理異常：${e.message}" else "Image processing error: ${e.message}")
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
            val message = if (currentLanguage == LANG_CHINESE) {
                "無法識別病患資訊，請確認圖片包含完整的病歷資料"
            } else {
                "Unable to recognize patient information. Please ensure the image contains complete medical record data."
            }
            showToast(message)
            Log.w(TAG, "無法從OCR結果提取病患資訊")
        }
    }

    private fun handleOCRFailure(errorMessage: String) {
        hideProgressDialog()
        resetProcessingState()
        val message = if (currentLanguage == LANG_CHINESE) {
            "圖片識別失敗：$errorMessage"
        } else {
            "Image recognition failed: $errorMessage"
        }
        showToast(message)
        textResult.text = if (currentLanguage == LANG_CHINESE) "識別失敗，請重新嘗試" else "Recognition failed, please try again"
    }

    private fun extractPatientInfo(text: String): PatientInfo? {
        Log.d(TAG, "開始解析病患資訊: $text")

        val namePatterns = if (currentLanguage == LANG_ENGLISH) {
            listOf(
                Regex("""Name[:：]?\s*([A-Za-z\s]{2,30})"""),
                Regex("""Patient[:：]?\s*([A-Za-z\s]{2,30})""")
            )
        } else {
            listOf(
                Regex("""姓名[:：]?\s*([^\s\n\r]{2,10})"""),
                Regex("""病患[:：]?\s*([^\s\n\r]{2,10})"""),
                Regex("""患者[:：]?\s*([^\s\n\r]{2,10})""")
            )
        }

        val birthPatterns = listOf(
            Regex("""出生[:：]?\s*(\d{4}[年/-]\d{1,2}[月/-]\d{1,2}[日]?)"""),
            Regex("""生日[:：]?\s*(\d{4}[年/-]\d{1,2}[月/-]\d{1,2}[日]?)"""),
            Regex("""Birth[:：]?\s*(\d{4}[年/-]\d{1,2}[月/-]\d{1,2}[日]?)"""),
            Regex("""DOB[:：]?\s*(\d{4}[年/-]\d{1,2}[月/-]\d{1,2}[日]?)"""),
            Regex("""(\d{4}年\d{1,2}月\d{1,2}日)"""),
            Regex("""(\d{4}/\d{1,2}/\d{1,2})"""),
            Regex("""(\d{4}-\d{1,2}-\d{1,2})""")
        )

        val idPatterns = listOf(
            Regex("""病歷號[:：]?\s*([A-Za-z0-9]{4,15})"""),
            Regex("""病號[:：]?\s*([A-Za-z0-9]{4,15})"""),
            Regex("""編號[:：]?\s*([A-Za-z0-9]{4,15})"""),
            Regex("""ID[:：]?\s*([A-Za-z0-9]{4,15})"""),
            Regex("""Medical ID[:：]?\s*([A-Za-z0-9]{4,15})"""),
            Regex("""Patient ID[:：]?\s*([A-Za-z0-9]{4,15})""")
        )

        val examPatterns = if (currentLanguage == LANG_ENGLISH) {
            listOf(
                Regex("""Exam[:：]?\s*([^\s\n\r]{2,20})"""),
                Regex("""Test[:：]?\s*([^\s\n\r]{2,20})"""),
                Regex("""Procedure[:：]?\s*([^\s\n\r]{2,20})""")
            )
        } else {
            listOf(
                Regex("""檢查[:：]?\s*([^\s\n\r]{2,20})"""),
                Regex("""項目[:：]?\s*([^\s\n\r]{2,20})""")
            )
        }

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
        return if (currentLanguage == LANG_ENGLISH) {
            val greeting = if (info.name.contains("Mr.") || info.name.contains("Ms.") || info.name.contains("Mrs.")) {
                info.name
            } else {
                "Mr. ${info.name}"
            }

            val examText = if (info.examType.isNotEmpty()) {
                "${info.examType} examination"
            } else {
                "examination"
            }

            "${greeting}, hello. You will have an ${examText} shortly. Now let me verify your medical information. " +
                    "Name: ${info.name}. Date of birth: ${info.birthDate}. Medical ID: ${info.medicalId}. " +
                    "If the information is correct, please say 'yes' or 'correct'."
        } else {
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

            "${greeting}您好，等一下將進行${examText}。現在向您核對病歷資訊。" +
                    "姓名為：${info.name}。出生年月日為：${info.birthDate}。病歷號為：${info.medicalId}。" +
                    "如果資料正確，請說「是」或「正確」。"
        }
    }

    private fun speakText(text: String) {
        if (!isTtsInitialized) {
            showToast(if (currentLanguage == LANG_CHINESE) "語音系統尚未準備就緒，請稍候再試" else "TTS system not ready, please try again later")
            return
        }

        tts?.let { ttsEngine ->
            val params = Bundle().apply {
                putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, TTS_UTTERANCE_ID)
            }

            val result = ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, params, TTS_UTTERANCE_ID)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS 播放失敗")
                showToast(if (currentLanguage == LANG_CHINESE) "語音播放失敗" else "TTS playback failed")
            } else {
                Log.i(TAG, "開始播放語音")
            }
        }
    }

    private fun startSpeechRecognition() {
        if (speechRecognizer == null) {
            Log.e(TAG, "語音識別器未初始化")
            showToast(if (currentLanguage == LANG_CHINESE) "語音識別功能不可用" else "Speech recognition unavailable")
            return
        }

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            showToast(if (currentLanguage == LANG_CHINESE) "缺少錄音權限" else "Missing audio recording permission")
            return
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Log.d(TAG, "準備接收語音")
                runOnUiThread {
                    val message = if (currentLanguage == LANG_CHINESE) {
                        "請說出「是」或「正確」確認資料"
                    } else {
                        "Please say 'yes' or 'correct' to confirm"
                    }
                    showToast(message)
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "語音識別結果: $matches")

                if (matches != null && matches.isNotEmpty()) {
                    val userResponse = matches[0].trim().lowercase()
                    val confirmWords = if (currentLanguage == LANG_ENGLISH) {
                        listOf("yes", "correct", "right", "ok", "confirm")
                    } else {
                        listOf("是", "正確", "對", "沒錯", "確認", "yes", "correct")
                    }

                    val isConfirmed = confirmWords.any { userResponse.contains(it) }

                    if (isConfirmed) {
                        runOnUiThread {
                            val message = if (currentLanguage == LANG_CHINESE) {
                                "病患資料核對成功！"
                            } else {
                                "Patient information verified successfully!"
                            }
                            showToast(message)
                            handleVerificationSuccess()
                        }
                    } else {
                        runOnUiThread {
                            val message = if (currentLanguage == LANG_CHINESE) {
                                "請重新確認資料或重新掃描"
                            } else {
                                "Please reconfirm information or rescan"
                            }
                            showToast(message)
                        }
                    }
                } else {
                    runOnUiThread {
                        val message = if (currentLanguage == LANG_CHINESE) {
                            "未能識別語音，請再次嘗試"
                        } else {
                            "Unable to recognize speech, please try again"
                        }
                        showToast(message)
                    }
                }
            }

            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> if (currentLanguage == LANG_CHINESE) "音訊錯誤" else "Audio error"
                    SpeechRecognizer.ERROR_CLIENT -> if (currentLanguage == LANG_CHINESE) "客戶端錯誤" else "Client error"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> if (currentLanguage == LANG_CHINESE) "權限不足" else "Insufficient permissions"
                    SpeechRecognizer.ERROR_NETWORK -> if (currentLanguage == LANG_CHINESE) "網路錯誤" else "Network error"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> if (currentLanguage == LANG_CHINESE) "網路超時" else "Network timeout"
                    SpeechRecognizer.ERROR_NO_MATCH -> if (currentLanguage == LANG_CHINESE) "無法匹配" else "No match"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> if (currentLanguage == LANG_CHINESE) "識別器忙碌" else "Recognizer busy"
                    SpeechRecognizer.ERROR_SERVER -> if (currentLanguage == LANG_CHINESE) "伺服器錯誤" else "Server error"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> if (currentLanguage == LANG_CHINESE) "語音超時" else "Speech timeout"
                    else -> if (currentLanguage == LANG_CHINESE) "未知錯誤 ($error)" else "Unknown error ($error)"
                }
                Log.e(TAG, "語音識別錯誤: $errorMessage")
                runOnUiThread {
                    val message = if (currentLanguage == LANG_CHINESE) {
                        "語音識別失敗: $errorMessage，請重新嘗試"
                    } else {
                        "Speech recognition failed: $errorMessage, please try again"
                    }
                    showToast(message)
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
                val partials = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Log.d(TAG, "部分識別結果: $partials")
            }
            override fun onRmsChanged(rmsdB: Float) {}
        })

        try {
            speechRecognizer?.startListening(speechIntent)
        } catch (e: Exception) {
            Log.e(TAG, "啟動語音識別失敗", e)
            showToast(if (currentLanguage == LANG_CHINESE) "啟動語音識別失敗" else "Failed to start speech recognition")
        }
    }

    private fun handleVerificationSuccess() {
        currentPatientInfo?.let { info ->
            Log.i(TAG, "病患 ${info.name} 身份驗證成功")

            val successMessage = if (currentLanguage == LANG_ENGLISH) {
                "Patient verification completed\n" +
                        "Name: ${info.name}\n" +
                        "Date of birth: ${info.birthDate}\n" +
                        "Medical ID: ${info.medicalId}"
            } else {
                "病患身份驗證完成\n" +
                        "姓名：${info.name}\n" +
                        "出生日期：${info.birthDate}\n" +
                        "病歷號：${info.medicalId}"
            }

            val header = if (currentLanguage == LANG_ENGLISH) {
                "\n\n=== Verification Successful ===\n"
            } else {
                "\n\n=== 驗證成功 ===\n"
            }

            textResult.append("$header$successMessage")
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
                    val message = if (currentLanguage == LANG_CHINESE) {
                        "應用程式需要相關權限才能正常運作"
                    } else {
                        "App requires permissions to function properly"
                    }
                    showToast(message)
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
        tts?.stop()
        speechRecognizer?.stopListening()
    }
}