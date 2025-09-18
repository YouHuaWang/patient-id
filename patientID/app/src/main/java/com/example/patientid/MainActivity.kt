package com.example.patientid

import android.app.Activity
import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.Text
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*
import com.example.patientid.utils.MedDict
import com.example.patientid.utils.ExamTranslator

// === 新增 import：引用分檔後的模組 ===
import com.example.patientid.core.PatientInfo
import com.example.patientid.core.CombinedOCRResult
import com.example.patientid.core.LocaleSupport
import com.example.patientid.media.ImageKit
import com.example.patientid.recognition.OcrExtractors
import com.example.patientid.recognition.PatientParsing
import com.example.patientid.speechtext.SpeechText
// ===================================

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PatientID"
        private const val RECORD_AUDIO_PERMISSION_REQUEST = 1
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
    private lateinit var tvTitle: TextView
    private lateinit var tvResultHeader: TextView
    private lateinit var tvPlaceholder: TextView

    // Core variables
    private var photoUri: Uri? = null
    private var tts: TextToSpeech? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var speechIntent: Intent
    private var isTtsInitialized = false
    private var currentPatientInfo: PatientInfo? = null
    private var progressDialog: ProgressDialog? = null
    private var isProcessing = false
    private var lastOcrFullText: String = ""
    private var currentBitmap: Bitmap? = null

    // Language & Speech Recognition Dialog
    private lateinit var prefs: SharedPreferences
    private var currentLanguage: String = LANG_CHINESE
    private var speechDialog: AlertDialog? = null

    // 觸發防抖與狀態旗標
    private var didStartRecognition = false
    private var lastSpeechText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize language settings
        prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        currentLanguage = prefs.getString(KEY_LANGUAGE, LANG_CHINESE) ?: LANG_CHINESE
        updateLocale(currentLanguage) // wrapper -> LocaleSupport

        setContentView(R.layout.activity_main)

        initializeUI()
        requestPermissions()
        initializeServices()
    }

    // ====== 以下是「同名 wrapper」：僅換到新檔執行，呼叫點不變 ======

    private fun updateLocale(language: String) {
        LocaleSupport.updateLocale(this, language)
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        return ImageKit.createImageFile(this)
    }

    private fun getBitmapFromUri(uri: Uri): Bitmap {
        return ImageKit.getBitmapFromUri(this, uri)
    }

    private fun extractExamItemsUnified(fullText: String): Map<String, String> {
        return OcrExtractors.extractExamItemsUnified(fullText)
    }

    private fun extractMedicalFormFields(visionText: Text): Map<String, String> {
        return OcrExtractors.extractMedicalFormFields(visionText)
    }

    private fun extractMedicalFormFieldsByBlock(visionText: Text): Map<String, String> {
        return OcrExtractors.extractMedicalFormFieldsByBlock(visionText)
    }

    private fun extractMedicalFormFieldsByCustom(fullText: String): Map<String, String> {
        return OcrExtractors.extractMedicalFormFieldsByCustom(fullText)
    }

    private fun extractExamItems(fullText: String): List<String> {
        return OcrExtractors.extractExamItems(fullText)
    }

    private fun extractPatientInfo(text: String): PatientInfo? {
        val isEnglish = currentLanguage == LANG_ENGLISH
        return PatientParsing.extractPatientInfo(text, isEnglish)
    }

    private fun buildSpeechText(info: PatientInfo): String {
        val isEnglish = currentLanguage == LANG_ENGLISH
        return SpeechText.build(info, isEnglish)
    }

    // ====== 其餘內容：完全沿用你現有 MainActivity（未改動邏輯） ======

    private fun initializeUI() {
        imageView = findViewById(R.id.imageView)
        textResult = findViewById(R.id.textResult)
        btnTakePhoto = findViewById(R.id.btnTakePhoto)
        btnSelectImage = findViewById(R.id.btnSelectImage)
        btnReprocessImage = findViewById(R.id.btnReprocessImage)
        llPlaceholder = findViewById(R.id.llPlaceholder)
        btnLanguage = findViewById(R.id.btnLanguage)
        tvTitle = findViewById(R.id.tvTitle)
        tvResultHeader = findViewById(R.id.tvResultHeader)
        tvPlaceholder = findViewById(R.id.tvPlaceholder)

        btnTakePhoto.setOnClickListener { handleTakePhoto() }
        btnSelectImage.setOnClickListener { handleSelectImage() }
        btnReprocessImage.setOnClickListener { reprocessCurrentImage() }
        btnLanguage.setOnClickListener { showLanguageDialog() }

        btnReprocessImage.visibility = View.GONE
        updateUITexts()
    }

    private fun updateUITexts() {
        when (currentLanguage) {
            LANG_ENGLISH -> {
                btnTakePhoto.text = "Take Photo"
                btnSelectImage.text = "Select Image"
                btnReprocessImage.text = "Reprocess"
                btnLanguage.text = "中文"
                textResult.text = "Waiting for image recognition..."
                tvTitle.text = "Patient Verification System"
                tvResultHeader.text = "Recognition Result"
                tvPlaceholder.text = "Please take or select medical order photo"
            }
            else -> {
                btnTakePhoto.text = "拍攝醫令單"
                btnSelectImage.text = "選擇圖片"
                btnReprocessImage.text = "重新分析"
                btnLanguage.text = "English"
                textResult.text = "等待圖片識別..."
                tvTitle.text = "病患身份驗證系統"
                tvResultHeader.text = "識別結果"
                tvPlaceholder.text = "請拍攝或選擇醫令單"
            }
        }
    }

    private fun showLanguageDialog() {
        val languages = if (currentLanguage == LANG_CHINESE) arrayOf("繁體中文", "English") else arrayOf("Traditional Chinese", "English")
        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (currentLanguage == LANG_CHINESE) "選擇語言" else "Select Language")
        builder.setItems(languages) { dialog, which ->
            val newLanguage = if (which == 0) LANG_CHINESE else LANG_ENGLISH
            if (newLanguage != currentLanguage) {
                currentLanguage = newLanguage
                prefs.edit().putString(KEY_LANGUAGE, currentLanguage).apply()
                updateLocale(currentLanguage)
                updateUITexts()
                initializeServices()
            }
            dialog.dismiss()
        }
        builder.show()
    }

    private fun requestPermissions() {
        val permissionsToRequest = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.CAMERA)
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
        }
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                    permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) != PackageManager.PERMISSION_GRANTED)
                    permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED)
                    permissionsToRequest.add(android.Manifest.permission.READ_MEDIA_IMAGES)
            }
            else -> {
                if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED)
                    permissionsToRequest.add(android.Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest.toTypedArray(), RECORD_AUDIO_PERMISSION_REQUEST)
        }
    }

    private fun initializeServices() {
        initializeTTS()
        initializeSpeechRecognizer()
    }

    private fun initializeTTS() {
        try {
            tts?.shutdown()
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val locale = when (currentLanguage) { LANG_ENGLISH -> Locale.US; else -> Locale.TRADITIONAL_CHINESE }
                    val result = tts?.setLanguage(locale)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        if (currentLanguage == LANG_CHINESE) tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
                    }
                    tts?.setSpeechRate(0.8f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onDone(utteranceId: String?) {
                            if (utteranceId == TTS_UTTERANCE_ID) {
                                runOnUiThread {
                                    if (!didStartRecognition) {
                                        didStartRecognition = true
                                        android.os.Handler(mainLooper).postDelayed({
                                            showSpeechRecognitionDialog()
                                            startSpeechRecognition()
                                        }, 400)
                                    }
                                }
                            }
                        }
                        override fun onError(utteranceId: String?) {
                            Log.e(TAG, "TTS 錯誤: $utteranceId")
                            runOnUiThread {
                                dismissSpeechDialog()
                                showToast(if (currentLanguage == LANG_CHINESE) "語音播放錯誤，請重新嘗試" else "TTS error, please try again")
                                resetProcessingState()
                            }
                        }
                        override fun onStart(utteranceId: String?) { Log.d(TAG, "TTS 開始播放") }
                    })
                    isTtsInitialized = true
                } else {
                    runOnUiThread { showToast(if (currentLanguage == LANG_CHINESE) "語音系統初始化失敗" else "TTS initialization failed") }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "TTS 初始化異常", e)
            showToast(if (currentLanguage == LANG_CHINESE) "語音系統初始化失敗" else "TTS initialization failed")
        }
    }

    private fun initializeSpeechRecognizer() {
        try {
            speechRecognizer?.destroy()
            if (SpeechRecognizer.isRecognitionAvailable(this)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
                speechIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, if (currentLanguage == LANG_ENGLISH) "en-US" else "zh-TW")
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                    putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
                }
            } else {
                showToast(if (currentLanguage == LANG_CHINESE) "此設備不支援語音識別功能" else "Speech recognition not supported")
            }
        } catch (e: Exception) {
            Log.e(TAG, "語音識別器初始化異常", e)
        }
    }

    private fun handleTakePhoto() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(); return
        }
        try {
            val photoFile = createImageFile() // wrapper -> ImageKit
            photoUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
            val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, photoUri) }
            if (intent.resolveActivity(packageManager) != null) takePhotoLauncher.launch(intent)
            else showToast(if (currentLanguage == LANG_CHINESE) "找不到相機應用程式" else "Camera app not found")
        } catch (e: Exception) {
            Log.e(TAG, "拍照處理異常", e)
            showToast(if (currentLanguage == LANG_CHINESE) "相機啟動失敗" else "Camera launch failed")
        }
    }

    private fun handleSelectImage() {
        try {
            val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            if (intent.resolveActivity(packageManager) != null) selectImageLauncher.launch(intent)
            else showToast(if (currentLanguage == LANG_CHINESE) "找不到圖片選擇應用程式" else "Image picker not found")
        } catch (e: Exception) {
            Log.e(TAG, "圖片選擇處理異常", e)
            showToast(if (currentLanguage == LANG_CHINESE) "圖片選擇啟動失敗" else "Image picker launch failed")
        }
    }

    private fun reprocessCurrentImage() {
        currentBitmap?.let { bitmap ->
            if (!bitmap.isRecycled) processImage(bitmap)
            else showToast(if (currentLanguage == LANG_CHINESE) "圖片已被回收，請重新選擇" else "Image has been recycled, please select again")
        } ?: showToast(if (currentLanguage == LANG_CHINESE) "請先選擇或拍攝圖片" else "Please select or take a photo first")
    }

    private val takePhotoLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            try {
                photoUri?.let { uri ->
                    val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val source = ImageDecoder.createSource(contentResolver, uri)
                        ImageDecoder.decodeBitmap(source)
                    } else {
                        @Suppress("DEPRECATION")
                        MediaStore.Images.Media.getBitmap(contentResolver, uri)
                    }
                    currentBitmap?.recycle()
                    currentBitmap = bitmap
                    setImageAndHidePlaceholder(bitmap)
                    btnReprocessImage.visibility = View.VISIBLE
                    processImage(bitmap)
                } ?: showToast(if (currentLanguage == LANG_CHINESE) "無法獲取拍攝的圖片" else "Unable to get captured image")
            } catch (e: Exception) {
                Log.e(TAG, "處理拍攝圖片時發生錯誤", e)
                showToast(if (currentLanguage == LANG_CHINESE) "處理拍攝圖片時發生錯誤" else "Error processing captured image")
            }
        }
    }

    private val selectImageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri ->
                try {
                    val bitmap = getBitmapFromUri(uri) // wrapper -> ImageKit
                    currentBitmap?.recycle()
                    currentBitmap = bitmap
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
        llPlaceholder.visibility = View.GONE
    }

    private fun processImage(bitmap: Bitmap) {
        if (isProcessing) { Log.w(TAG, "圖片處理中，忽略重複請求"); return }
        if (bitmap.isRecycled) { showToast(if (currentLanguage == LANG_CHINESE) "圖片已被回收，請重新選擇" else "Image has been recycled, please select again"); return }

        isProcessing = true
        showProgressDialog(if (currentLanguage == LANG_CHINESE) "正在分析圖片..." else "Analyzing image...")

        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            recognizer.process(image)
                .addOnSuccessListener { visionText -> handleOCRResult(visionText) }
                .addOnFailureListener { exception ->
                    Log.e(TAG, "OCR 處理失敗", exception)
                    handleOCRFailure(exception.message ?: if (currentLanguage == LANG_CHINESE) "未知錯誤" else "Unknown error")
                }
        } catch (e: Exception) {
            Log.e(TAG, "圖片處理發生異常", e)
            handleOCRFailure(if (currentLanguage == LANG_CHINESE) "圖片處理異常：${e.message}" else "Image processing error: ${e.message}")
        }
    }

    private fun handleOCRResult(visionText: Text) {
        try {
            val fullText = visionText.text ?: ""
            lastOcrFullText = fullText

            val fieldsLine = extractMedicalFormFields(visionText)
            val fieldsBlock = extractMedicalFormFieldsByBlock(visionText)
            val fieldsCustom = extractMedicalFormFieldsByCustom(fullText)
            val examItems = extractExamItems(fullText)
            val unifiedExamItems = extractExamItemsUnified(fullText)

            val sb = StringBuilder()
            if (currentLanguage == LANG_ENGLISH) {
                sb.append("Examination Items（Combine Version）:\n")
                if (unifiedExamItems.isEmpty()) sb.append("（None）\n") else unifiedExamItems.forEach { sb.append(it).append("\n") }
                sb.append("\n")
                sb.append("OCR Full Text:\n").append(fullText.ifEmpty { "(No text recognition result)" }).append("\n\n")
                sb.append("Extracted Fields (Line by Line):\n")
                if (fieldsLine.isEmpty()) sb.append("(None)\n")
                fieldsLine.forEach { (k, v) -> sb.append("$k: $v\n") }
                sb.append("\n")
                sb.append("Extracted Fields (Block):\n")
                if (fieldsBlock.isEmpty()) sb.append("(None)\n")
                fieldsBlock.forEach { (k, v) -> sb.append("$k: $v\n") }
                sb.append("\n")
                sb.append("Examination Items (Custom Rules):\n")
                if (fieldsCustom["檢查部位"].isNullOrEmpty()) sb.append("(None)\n") else sb.append(fieldsCustom["檢查部位"]).append("\n\n")
                sb.append("Examination Items (Code Merged Version):\n")
                if (examItems.isEmpty()) sb.append("(None)\n") else examItems.forEach { sb.append(it).append("\n") }
            } else {
                sb.append("檢查部位（整合版）:\n")
                if (unifiedExamItems.isEmpty()) sb.append("（無）\n") else unifiedExamItems.forEach { sb.append(it).append("\n") }
                sb.append("\n")
                sb.append("OCR 全文:\n").append(fullText.ifEmpty { "(無文字辨識結果)" }).append("\n\n")
                sb.append("抽取欄位（逐行）:\n")
                if (fieldsLine.isEmpty()) sb.append("（無）\n") else fieldsLine.forEach { (k, v) -> sb.append("$k: $v\n") }
                sb.append("\n")
                sb.append("抽取欄位（區塊）:\n")
                if (fieldsBlock.isEmpty()) sb.append("（無）\n") else fieldsBlock.forEach { (k, v) -> sb.append("$k: $v\n") }
                sb.append("\n")
                sb.append("檢查部位（自訂規則）:\n")
                if (fieldsCustom["檢查部位"].isNullOrEmpty()) sb.append("（無）\n") else sb.append(fieldsCustom["檢查部位"]).append("\n\n")
                sb.append("檢查部位（代碼合併版）:\n")
                if (examItems.isEmpty()) sb.append("（無）\n") else examItems.forEach { sb.append(it).append("\n") }
            }
            handleOCRSuccess(sb.toString())
        } catch (e: Exception) {
            Log.e(TAG, "處理OCR結果時異常", e)
            handleOCRFailure(if (currentLanguage == LANG_CHINESE) "處理識別結果時異常" else "Error processing recognition result")
        }
    }

    private fun handleOCRSuccess(recognizedText: String) {
        hideProgressDialog()
        resetProcessingState()
        textResult.text = recognizedText
        val patientInfo = extractPatientInfo(recognizedText)
        if (patientInfo != null) {
            currentPatientInfo = patientInfo
            val speechText = buildSpeechText(patientInfo)
            speakText(speechText)
        } else {
            val fallback = if (currentLanguage == LANG_CHINESE)
                "目前無法從影像中明確辨識姓名、生日或病歷號。請在聽到提示音後，口頭說出您的姓名或病歷號以進行確認。"
            else
                "We couldn't clearly recognize your name, date of birth, or medical ID from the image. After the beep, please state your name or medical ID for verification."
            currentPatientInfo = PatientInfo("未辨識","未辨識","未辨識","")
            speakText(fallback)
        }
    }

    private fun handleOCRFailure(errorMessage: String) {
        hideProgressDialog()
        resetProcessingState()
        val message = if (currentLanguage == LANG_CHINESE) "圖片識別失敗：$errorMessage" else "Image recognition failed: $errorMessage"
        showToast(message)
        textResult.text = if (currentLanguage == LANG_CHINESE) "識別失敗，請重新嘗試" else "Recognition failed, please try again"
    }

    private fun speakText(text: String) {
        if (!isTtsInitialized) {
            showToast(if (currentLanguage == LANG_CHINESE) "語音系統尚未準備就緒，改為直接進入聆聽" else "TTS not ready, switching to listening")
            didStartRecognition = true
            showSpeechRecognitionDialog()
            startSpeechRecognition()
            return
        }
        didStartRecognition = false
        lastSpeechText = text
        tts?.let { ttsEngine ->
            val params = Bundle().apply { putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, TTS_UTTERANCE_ID) }
            val result = ttsEngine.speak(text, TextToSpeech.QUEUE_FLUSH, params, TTS_UTTERANCE_ID)
            if (result == TextToSpeech.ERROR) {
                Log.e(TAG, "TTS 播放失敗，直接進入聆聽")
                didStartRecognition = true
                showSpeechRecognitionDialog()
                startSpeechRecognition()
            }
        }
    }

    private fun showSpeechRecognitionDialog() {
        dismissSpeechDialog()
        val dialogMessage = if (currentLanguage == LANG_CHINESE) {
            currentPatientInfo?.let { info ->
                "正在播放病患資訊確認...\n\n姓名：${info.name}\n出生日期：${info.birthDate}\n病歷號：${info.medicalId}\n\n請聽完語音後回應「是」或「正確」進行確認"
            } ?: "正在進行語音確認..."
        } else {
            currentPatientInfo?.let { info ->
                "Playing patient information verification...\n\nName: ${info.name}\nDate of birth: ${info.birthDate}\nMedical ID: ${info.medicalId}\n\nPlease respond 'yes' or 'correct' after listening"
            } ?: "Voice verification in progress..."
        }
        val builder = AlertDialog.Builder(this)
        builder.setTitle(if (currentLanguage == LANG_CHINESE) "語音確認中" else "Voice Verification")
        builder.setMessage(dialogMessage)
        builder.setCancelable(true)
        builder.setNegativeButton(if (currentLanguage == LANG_CHINESE) "取消" else "Cancel") { dialog, _ ->
            dialog.dismiss(); speechRecognizer?.stopListening(); tts?.stop()
            showToast(if (currentLanguage == LANG_CHINESE) "語音確認已取消" else "Voice verification cancelled")
        }
        speechDialog = builder.create()
        speechDialog?.show()
    }

    private fun dismissSpeechDialog() { speechDialog?.dismiss(); speechDialog = null }

    private fun startSpeechRecognition() {
        if (speechRecognizer == null) {
            dismissSpeechDialog()
            showToast(if (currentLanguage == LANG_CHINESE) "語音識別功能不可用" else "Speech recognition unavailable")
            return
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            dismissSpeechDialog()
            showToast(if (currentLanguage == LANG_CHINESE) "缺少錄音權限" else "Missing audio recording permission")
            return
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                runOnUiThread {
                    speechDialog?.setMessage(
                        if (currentLanguage == LANG_CHINESE) {
                            currentPatientInfo?.let { info ->
                                "病患資訊：\n姓名：${info.name}\n出生日期：${info.birthDate}\n病歷號：${info.medicalId}\n\n🎤 正在聆聽您的回應...\n請說「是」或「正確」確認資料"
                            } ?: "🎤 正在聆聽您的回應..."
                        } else {
                            currentPatientInfo?.let { info ->
                                "Patient Information:\nName: ${info.name}\nDate of birth: ${info.birthDate}\nMedical ID: ${info.medicalId}\n\n🎤 Listening for your response...\nPlease say 'yes' or 'correct'"
                            } ?: "🎤 Listening for your response..."
                        }
                    )
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (matches != null && matches.isNotEmpty()) {
                    val userResponse = matches[0].trim().lowercase()
                    val confirmWords = if (currentLanguage == LANG_ENGLISH) listOf("yes","correct","right","ok","confirm")
                    else listOf("是","正確","對","沒錯","確認","yes","correct")
                    val isConfirmed = confirmWords.any { userResponse.contains(it) }
                    runOnUiThread {
                        dismissSpeechDialog()
                        if (isConfirmed) { showToast(if (currentLanguage == LANG_CHINESE) "病患資料核對成功！" else "Patient information verified successfully!"); handleVerificationSuccess() }
                        else { showToast(if (currentLanguage == LANG_CHINESE) "請重新確認資料或重新掃描" else "Please reconfirm information or rescan") }
                    }
                } else {
                    runOnUiThread {
                        dismissSpeechDialog()
                        val message = if (currentLanguage == LANG_CHINESE) "未能識別語音，請再次嘗試" else "Unable to recognize speech, please try again"
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
                runOnUiThread {
                    dismissSpeechDialog()
                    val msg = if (currentLanguage == LANG_CHINESE) "語音識別失敗: $errorMessage，請重新嘗試" else "Speech recognition failed: $errorMessage, please try again"
                    showToast(msg)
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onRmsChanged(rmsdB: Float) {}
        })
        try { speechRecognizer?.startListening(speechIntent) }
        catch (e: Exception) {
            Log.e(TAG, "啟動語音識別失敗", e)
            dismissSpeechDialog()
            showToast(if (currentLanguage == LANG_CHINESE) "啟動語音識別失敗" else "Failed to start speech recognition")
        }
    }

    private fun handleVerificationSuccess() {
        currentPatientInfo?.let { info ->
            val successMessage = if (currentLanguage == LANG_ENGLISH)
                "Patient verification completed\nName: ${info.name}\nDate of birth: ${info.birthDate}\nMedical ID: ${info.medicalId}"
            else
                "病患身份驗證完成\n姓名：${info.name}\n出生日期：${info.birthDate}\n病歷號：${info.medicalId}"
            val header = if (currentLanguage == LANG_ENGLISH) "\n\n=== Verification Successful ===\n" else "\n\n=== 驗證成功 ===\n"
            textResult.append("$header$successMessage")
        }
    }

    private fun showProgressDialog(message: String) { hideProgressDialog(); progressDialog = ProgressDialog(this).apply { setMessage(message); setCancelable(false); show() } }
    private fun hideProgressDialog() { progressDialog?.dismiss(); progressDialog = null }
    private fun resetProcessingState() { isProcessing = false }
    private fun showToast(message: String) { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RECORD_AUDIO_PERMISSION_REQUEST) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) initializeServices()
            else showToast(if (currentLanguage == LANG_CHINESE) "應用程式需要相關權限才能正常運作" else "App requires permissions to function properly")
        }
    }

    override fun onDestroy() {
        try { dismissSpeechDialog(); tts?.stop(); tts?.shutdown(); speechRecognizer?.destroy(); hideProgressDialog() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        tts?.stop()
        speechRecognizer?.stopListening()
        dismissSpeechDialog()
    }
}
