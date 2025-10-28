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
import android.view.ViewGroup

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "PatientID"
        private const val RECORD_AUDIO_PERMISSION_REQUEST = 1
        private const val TTS_UTTERANCE_ID = "patient_verification"
        private const val PREFS_NAME = "PatientIDPrefs"
        private const val KEY_LANGUAGE = "selected_language"
        private const val LANG_CHINESE = "zh"
        private const val LANG_ENGLISH = "en"
        private const val LANG_KOREAN = "ko"
    }



    // === 新增：在 UI Components 區塊加入新元件 ===
    private lateinit var llVerifyPanel: LinearLayout
    private lateinit var etName: EditText
    private lateinit var etBirth: EditText
    private lateinit var etMedicalId: EditText
    private lateinit var btnConfirmOK: Button
    private lateinit var btnEditToggle: Button

    // === 新增：常數旗標，全面停用「語音回覆確認」流程 ===
    private val USE_VOICE_CONFIRM = false


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

    // 2) 新增：在 UI Components 區塊加入（性別＋標題）
    private lateinit var tvNameLabel: TextView
    private lateinit var tvGenderLabel: TextView
    private lateinit var tvMedicalIdLabel: TextView
    private lateinit var tvBirthLabel: TextView

    private lateinit var tvExamLabel: TextView
    private lateinit var etExam: EditText

    private lateinit var rgGender: RadioGroup
    private lateinit var rbMale: RadioButton
    private lateinit var rbFemale: RadioButton
    private lateinit var rbOther: RadioButton

    private fun getSelectedGenderText(): String {
        val id = rgGender.checkedRadioButtonId
        val text = when (id) {
            R.id.rbMale -> rbMale.text?.toString() ?: ""
            R.id.rbFemale -> rbFemale.text?.toString() ?: ""
            R.id.rbOther -> rbOther.text?.toString() ?: ""
            else -> ""
        }
        return text
    }

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
        // 讓鍵盤彈出時，內容區自動 resize（不會被鍵盤蓋住）
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

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
        return PatientParsing.extractPatientInfo(text, currentLanguage)
    }
    private fun buildSpeechText(info: PatientInfo): String {
        return SpeechText.build(info, currentLanguage)
    }


    // ====== 其餘內容：完全沿用你現有 MainActivity（未改動邏輯） ======

    private fun initializeUI() {
        // --- 原有 findViewById ---
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

        // --- 身份確認區塊與欄位 ---
        llVerifyPanel = findViewById(R.id.llVerifyPanel)
        etName = findViewById(R.id.etName)
        etBirth = findViewById(R.id.etBirth)
        etMedicalId = findViewById(R.id.etMedicalId)
        btnConfirmOK = findViewById(R.id.btnConfirmOK)
        btnEditToggle = findViewById(R.id.btnEditToggle)

        // --- 欄位標題與性別元件 ---
        tvNameLabel = findViewById(R.id.tvNameLabel)
        tvGenderLabel = findViewById(R.id.tvGenderLabel)
        tvMedicalIdLabel = findViewById(R.id.tvMedicalIdLabel)
        tvBirthLabel = findViewById(R.id.tvBirthLabel)
        rgGender = findViewById(R.id.rgGender)
        rbMale = findViewById(R.id.rbMale)
        rbFemale = findViewById(R.id.rbFemale)
        rbOther = findViewById(R.id.rbOther)

        tvExamLabel = findViewById(R.id.tvExamLabel)
        etExam = findViewById(R.id.etExam)

        // --- 初始 UI 狀態 ---
        // 文字區塊保留並可捲動（由外層 ScrollView 處理），啟用可選取與多行換行
        textResult.isClickable = true
        textResult.isFocusable = true
        textResult.setTextIsSelectable(true)
        textResult.setHorizontallyScrolling(false)

        // 身份確認面板預設隱藏；欄位先鎖定（顯示時可按「修改」再開）
        llVerifyPanel.visibility = View.GONE
        etName.isEnabled = false
        etBirth.isEnabled = false
        etMedicalId.isEnabled = false

        etExam.isEnabled = false

        rgGender.clearCheck()
        for (i in 0 until rgGender.childCount) rgGender.getChildAt(i).isEnabled = false

        // --- 依需求調整面板內的顯示順序：病歷號 → 姓名 → 性別 → 出生年月日 ---
        // 先把按鈕列抓出來（同一列有 確認／修改）
        val actionRow = (btnConfirmOK.parent as? View)
        if (actionRow != null) llVerifyPanel.removeView(actionRow)

        // 先移除要重排的元素，避免重複加入
        listOf(
            tvMedicalIdLabel, etMedicalId,
            tvNameLabel, etName,
            tvGenderLabel, rgGender,
            tvBirthLabel, etBirth,
            tvExamLabel, etExam               // ← 新增
        ).forEach { v ->
            (v.parent as? ViewGroup)?.removeView(v)
        }

        // 依指定順序加入
        llVerifyPanel.addView(tvMedicalIdLabel)
        llVerifyPanel.addView(etMedicalId)
        llVerifyPanel.addView(tvNameLabel)
        llVerifyPanel.addView(etName)
        llVerifyPanel.addView(tvGenderLabel)
        llVerifyPanel.addView(rgGender)
        llVerifyPanel.addView(tvBirthLabel)
        llVerifyPanel.addView(etBirth)

        llVerifyPanel.addView(tvExamLabel)   // ← 新增
        llVerifyPanel.addView(etExam)        // ← 新增

        // 最後把按鈕列加回去
        if (actionRow != null) llVerifyPanel.addView(actionRow)

        // --- 行為：確認＝取用欄位 → 覆寫 currentPatientInfo → 成功流程 ---
        btnConfirmOK.setOnClickListener {
            val nameIn = etName.text?.toString()?.trim().orEmpty()
            val birthIn = etBirth.text?.toString()?.trim().orEmpty()
            val midIn = etMedicalId.text?.toString()?.trim().orEmpty()
            val examIn = etExam.text?.toString()?.trim().orEmpty()



            val unknown = if (currentLanguage == LANG_ENGLISH) "Unknown" else "未辨識"
            val name = if (nameIn.isEmpty()) unknown else nameIn
            val birth = if (birthIn.isEmpty()) unknown else birthIn
            val mid = if (midIn.isEmpty()) unknown else midIn
            val exam = if (examIn.isEmpty()) unknown else examIn

            currentPatientInfo = com.example.patientid.core.PatientInfo(
                name, birth, mid, exam
            )
            handleVerificationSuccess()
        }

        // --- 行為：修改鍵＝切換欄位可編輯狀態與文字 ---
        btnEditToggle.setOnClickListener {
            val enable = !etName.isEnabled
            etName.isEnabled = enable
            etBirth.isEnabled = enable
            etMedicalId.isEnabled = enable
            etExam.isEnabled = enable
            for (i in 0 until rgGender.childCount) rgGender.getChildAt(i).isEnabled = enable

            if (enable) {
                // 進入可編輯：若欄位是「未辨識/Unknown」→ 自動清空，避免殘留
                val zh = "未辨識"; val en = "Unknown"
                if (etName.text?.toString() == zh || etName.text?.toString() == en) etName.setText("")
                if (etBirth.text?.toString() == zh || etBirth.text?.toString() == en) etBirth.setText("")
                if (etMedicalId.text?.toString() == zh || etMedicalId.text?.toString() == en) etMedicalId.setText("")
                if (etExam.text?.toString() == zh || etExam.text?.toString() == en) etExam.setText("")
            }

            btnEditToggle.text = if (enable) {
                if (currentLanguage == LANG_ENGLISH) "✏️ Editing…" else "✏️ 編輯中…"
            } else {
                if (currentLanguage == LANG_ENGLISH) "✏️ Edit" else "✏️ 修改"
            }
        }

        // --- 其他按鈕（原行為保留） ---
        btnTakePhoto.setOnClickListener { handleTakePhoto() }
        btnSelectImage.setOnClickListener { handleSelectImage() }
        btnReprocessImage.setOnClickListener { reprocessCurrentImage() }
        btnLanguage.setOnClickListener { showLanguageDialog() }

        // 依你原本邏輯，預設先隱藏重新分析鈕
        btnReprocessImage.visibility = View.GONE

        // 根據目前語系套用字串（會同步更新標題/選項/按鈕與 hint）
        updateUITexts()
    }






    // 4) 語系文字（Label / Hint / 性別）一起更新
    private fun updateUITexts() {
        when (currentLanguage) {
            LANG_ENGLISH -> {
                btnTakePhoto.text = "Take Photo"
                btnSelectImage.text = "Select Image"
                btnReprocessImage.text = "Reprocess"
                btnLanguage.text = "🌐 Language"   // 不寫死中或英，三語都通用
                tvTitle.text = "Patient Verification System"
                tvResultHeader.text = "Recognition Result"
                tvPlaceholder.text = "Please take or select medical order photo"

                btnConfirmOK.text = "✅ Confirm"
                btnEditToggle.text = if (etName.isEnabled) "✏️ Editing…" else "✏️ Edit"

                tvNameLabel.text = "Name"
                tvGenderLabel.text = "Gender"
                tvMedicalIdLabel.text = "Medical ID"
                tvBirthLabel.text = "Date of Birth"
                etName.hint = "Unknown"
                etMedicalId.hint = "Unknown"
                etBirth.hint = "Unknown (YYYY/MM/DD)"

                rbMale.text = "Male"; rbFemale.text = "Female"; rbOther.text = "Other"
                if (textResult.text.isNullOrBlank()) textResult.text = "Waiting for image recognition..."

                tvExamLabel.text = "Examination Items"
                etExam.hint = "Unknown"
            }
            LANG_KOREAN -> {
                btnTakePhoto.text = "사진 촬영"
                btnSelectImage.text = "이미지 선택"
                btnReprocessImage.text = "재분석"
                btnLanguage.text = "🌐 언어"

                tvTitle.text = "환자 신원 확인 시스템"
                tvResultHeader.text = "인식 결과"
                tvPlaceholder.text = "의뢰서 사진을 촬영하거나 선택하세요"

                btnConfirmOK.text = "✅ 확인"
                btnEditToggle.text = if (etName.isEnabled) "✏️ 편집 중…" else "✏️ 편집"

                tvNameLabel.text = "이름"
                tvGenderLabel.text = "성별"
                tvMedicalIdLabel.text = "환자 번호"
                tvBirthLabel.text = "생년월일"
                etName.hint = "인식되지 않음"
                etMedicalId.hint = "인식되지 않음"
                etBirth.hint = "인식되지 않음 (YYYY/MM/DD)"

                rbMale.text = "남"; rbFemale.text = "여"; rbOther.text = "기타"
                if (textResult.text.isNullOrBlank()) textResult.text = "이미지 인식을 기다리는 중…"

                tvExamLabel.text = "검사 항목"
                etExam.hint = "인식되지 않음"
            }
            else -> { // 中文
                btnTakePhoto.text = "拍攝醫令單"
                btnSelectImage.text = "選擇圖片"
                btnReprocessImage.text = "重新分析"
                btnLanguage.text = "🌐 語言"
                tvTitle.text = "病患身份驗證系統"
                tvResultHeader.text = "識別結果"
                tvPlaceholder.text = "請拍攝或選擇醫令單"

                btnConfirmOK.text = "✅ 確認"
                btnEditToggle.text = if (etName.isEnabled) "✏️ 編輯中…" else "✏️ 修改"

                tvNameLabel.text = "姓名"
                tvGenderLabel.text = "性別"
                tvMedicalIdLabel.text = "病歷號"
                tvBirthLabel.text = "出生年月日"
                etName.hint = "未辨識"
                etMedicalId.hint = "未辨識"
                etBirth.hint = "未辨識（YYYY/MM/DD）"

                rbMale.text = "男"; rbFemale.text = "女"; rbOther.text = "其他"
                if (textResult.text.isNullOrBlank()) textResult.text = "等待圖片識別..."

                tvExamLabel.text = "檢查項目"
                etExam.hint = "未辨識"

            }
        }
    }



    private fun showLanguageDialog() {
        // 依目前語系，決定對話框標題語言
        val title = when (currentLanguage) {
            LANG_ENGLISH -> "Select Language"
            LANG_KOREAN  -> "언어 선택"
            else         -> "選擇語言"
        }

        // 顯示三語選項（名稱本身各自使用該語言的自稱，較直覺）
        val languages = arrayOf("繁體中文", "English", "한국어")

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(languages) { dialog, which ->
                val newLanguage = when (which) {
                    0 -> LANG_CHINESE
                    1 -> LANG_ENGLISH
                    2 -> LANG_KOREAN
                    else -> currentLanguage
                }
                if (newLanguage != currentLanguage) {
                    currentLanguage = newLanguage
                    prefs.edit().putString(KEY_LANGUAGE, currentLanguage).apply()
                    updateLocale(currentLanguage)
                    updateUITexts()
                    initializeServices()
                }
                dialog.dismiss()
            }
            .show()
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
        // 【修改】停用語音辨識初始化
        // initializeSpeechRecognizer()  // <- 移除或註解掉
    }


    private fun initializeTTS() {
        try {
            tts?.shutdown()
            // 可選：強制使用 Google TTS 引擎（若你確定裝置有）
            // tts = TextToSpeech(this, { status -> ... }, "com.google.android.tts")
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val targetLocale = when (currentLanguage) {
                        LANG_ENGLISH -> Locale.US
                        LANG_KOREAN  -> Locale.KOREAN
                        else          -> Locale.TRADITIONAL_CHINESE
                    }

                    // 先嘗試挑選符合語系的 Voice（通常比 setLanguage 更精準）
                    val koVoice = tts?.voices?.firstOrNull { v ->
                        // 只要語言為 ko（或完整地區碼如 ko_KR），且品質與延遲達到一般水準
                        v.locale?.language?.equals(targetLocale.language, ignoreCase = true) == true &&
                                v.quality >= android.speech.tts.Voice.QUALITY_NORMAL &&
                                v.latency <= android.speech.tts.Voice.LATENCY_NORMAL
                    }

                    val langResult = if (koVoice != null && currentLanguage == LANG_KOREAN) {
                        tts?.voice = koVoice
                        TextToSpeech.LANG_AVAILABLE // 代表我們用 voice 直接設定，視為可用
                    } else {
                        tts?.setLanguage(targetLocale)
                    }

                    if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                        // 嘗試引導使用者安裝對應語音資料
                        try {
                            val installIntent = Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA)
                            startActivity(installIntent)
                            showToast(
                                when (currentLanguage) {
                                    LANG_ENGLISH -> "Korean TTS data not installed. Please install and retry."
                                    LANG_KOREAN  -> "한국어 TTS 데이터가 설치되어 있지 않습니다. 설치 후 다시 시도하세요."
                                    else         -> "尚未安裝韓文語音資料，請安裝後再試。"
                                }
                            )
                        } catch (_: Exception) {
                            showToast(
                                when (currentLanguage) {
                                    LANG_ENGLISH -> "Korean TTS not supported on this device."
                                    LANG_KOREAN  -> "이 기기에서 한국어 TTS를 지원하지 않습니다."
                                    else         -> "此裝置不支援韓文語音。"
                                }
                            )
                        }
                    }

                    tts?.setSpeechRate(0.8f)
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onDone(utteranceId: String?) {}
                        override fun onError(utteranceId: String?) {}
                        override fun onStart(utteranceId: String?) {}
                    })
                    isTtsInitialized = true
                } else {
                    runOnUiThread {
                        showToast(
                            when (currentLanguage) {
                                LANG_ENGLISH -> "TTS initialization failed"
                                LANG_KOREAN  -> "TTS 초기화 실패"
                                else         -> "語音系統初始化失敗"
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {
            showToast(
                when (currentLanguage) {
                    LANG_ENGLISH -> "TTS initialization failed"
                    LANG_KOREAN  -> "TTS 초기화 실패"
                    else         -> "語音系統初始化失敗"
                }
            )
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
            val nameSmart = OcrExtractors.extractNameSmart(visionText, fullText)
            // ★ 新增：把智慧抓到的姓名記起來，給後續 handleOCRSuccess 覆蓋使用
            if (!nameSmart.isNullOrBlank()) {
                lastSmartName = nameSmart
            }

            lastOcrFullText = fullText

            val fieldsLine = extractMedicalFormFields(visionText)
            val fieldsBlock = extractMedicalFormFieldsByBlock(visionText)
            val fieldsCustom = extractMedicalFormFieldsByCustom(fullText)
            val examItems = extractExamItems(fullText)
            val unifiedExamItems = extractExamItemsUnified(fullText)

            val sb = StringBuilder()
            val examFirstLine = (unifiedExamItems["檢查項目"] ?: "")
                .lineSequence().firstOrNull()?.trim().orEmpty()
            if (examFirstLine.isNotEmpty()) {
                etExam.setText(examFirstLine)
            }

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



    // 將「民國069/01/29」轉為「069/01/29」；也能把「69/1/29」補零為「069/01/29」
    private fun rocForUi(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val s = input.trim()
        // 先抓出年/月/日，不論是否有「民國」前綴或不同分隔符
        val m = Regex("""(?:民國)?\s*(\d{1,3})[./\-年]?(\d{1,2})[./\-月]?(\d{1,2})""").find(s) ?: return s
        val y = m.groupValues[1].toIntOrNull() ?: return s
        val mm = m.groupValues[2].toIntOrNull() ?: return s
        val dd = m.groupValues[3].toIntOrNull() ?: return s
        return "%03d/%02d/%02d".format(y, mm, dd)
    }



    // 5) OCR 成功：塞欄位時若值等於占位詞 → 以 hint 呈現、EditText 內容保持空白
    // =========================================
// 1) 生日轉民國年 (YYYY/MM/DD -> 民國YYY/MM/DD)
//    - 若已含「民國」字樣或看起來像民國年，會盡量保留/修正成標準格式
// =========================================
// 生日 → 民國年
    private fun toRocDateString(input: String?): String {
        if (input.isNullOrBlank()) return ""
        val raw = input.trim()

        // 已寫「民國」
        Regex("""民國\s*(\d{1,3})[./\-年]?(\d{1,2})[./\-月]?(\d{1,2})""").find(raw)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return raw
            val mm = m.groupValues[2].toIntOrNull() ?: return raw
            val dd = m.groupValues[3].toIntOrNull() ?: return raw
            return "民國%03d/%02d/%02d".format(y, mm, dd)
        }

        // 西元 4 位年
        Regex("""(\d{4})[./\-年]?(\d{1,2})[./\-月]?(\d{1,2})""").find(raw)?.let { m ->
            val yyyy = m.groupValues[1].toIntOrNull() ?: return raw
            val mm = m.groupValues[2].toIntOrNull() ?: return raw
            val dd = m.groupValues[3].toIntOrNull() ?: return raw
            val roc = yyyy - 1911
            return if (roc > 0) "民國%03d/%02d/%02d".format(roc, mm, dd) else raw
        }

        // 2~3 位年（視為民國年，例如 069/01/29）
        Regex("""(\d{2,3})[./\-年]?(\d{1,2})[./\-月]?(\d{1,2})""").find(raw)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return raw
            val mm = m.groupValues[2].toIntOrNull() ?: return raw
            val dd = m.groupValues[3].toIntOrNull() ?: return raw
            return "民國%03d/%02d/%02d".format(y, mm, dd)
        }

        return raw
    }

    // =========================================
// 2) 從整段 OCR 結果字串推斷性別並勾選 RadioGroup
//    - 支援中英常見字樣
// =========================================
    private fun selectGenderFromText(fullText: String) {
        val t = fullText.lowercase()
        // 常見關鍵字（含：性別:男 / 女；male/female；M/F）
        val isMale = Regex("""(性別[:：]?\s*男)\b|(^|[^a-z])male([^a-z]|$)|\bsex[:：]?\s*m\b""").containsMatchIn(t)
                || Regex("""\b\s*m\b""").containsMatchIn(t) && t.contains("sex") // e.g., Sex: M

        val isFemale = Regex("""(性別[:：]?\s*女)\b|(^|[^a-z])female([^a-z]|$)|\bsex[:：]?\s*f\b""").containsMatchIn(t)
                || Regex("""\b\s*f\b""").containsMatchIn(t) && t.contains("sex") // e.g., Sex: F

        when {
            isMale -> rgGender.check(R.id.rbMale)
            isFemale -> rgGender.check(R.id.rbFemale)
            else -> {
                // 偵測不到→保留原狀（不強制選擇）
                rgGender.clearCheck()
            }
        }
    }

    // 從「原始 OCR 全文」抓生日：先找含 生日/出生 的行，再抓日期
    private fun extractBirthFromFullText(fullText: String): String? {
        if (fullText.isBlank()) return null
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        // 先找帶標籤的行
        val labelLines = lines.filter { it.contains("生日") || it.contains("出生") || it.contains("DOB", true) }
        val datePattern = Regex("""(民國\s*\d{1,3}[./\-]\d{1,2}[./\-]\d{1,2}|\d{4}[./\-]\d{1,2}[./\-]\d{1,2}|\d{2,3}[./\-]\d{1,2}[./\-]\d{1,2})""")

        labelLines.forEach { line ->
            datePattern.find(line.replace("＝","=").replace("：",":"))?.let { m ->
                return toRocDateString(m.value)
            }
        }

        // 沒抓到就全域找一次日期
        datePattern.find(fullText)?.let { m -> return toRocDateString(m.value) }
        return null
    }



    private var lastSmartName: String? = null

    // OCR 成功：用原始全文抓生日/性別；欄位顯示在「人工核對面板」上，並把生日(民國)回寫到 currentPatientInfo
    private fun handleOCRSuccess(recognizedText: String) {
        hideProgressDialog()
        resetProcessingState()
        textResult.text = recognizedText

        val rawBirth = extractBirthFromFullText(lastOcrFullText)
        val rocBirth = if (!rawBirth.isNullOrBlank()) toRocDateString(rawBirth) else ""

        // 用原始全文建立 patientInfo
        val parsed0 = extractPatientInfo(lastOcrFullText)

        // ★ 覆蓋姓名：若 smart 版本比較可信（非空、非「未辨識/Unknown」），就取代
        val betterName = lastSmartName?.takeIf { it.isNotBlank() } ?: ""
        val parsed = if (parsed0 != null) {
            parsed0.copy(name = if (betterName.isNotBlank()) betterName else parsed0.name)
        } else null

        fun putField(value: String?, et: EditText, zhHint: String, enHint: String) {
            val v = (value ?: "").trim()
            val isPlaceholder = v.isEmpty() || v == "未辨識" || v.equals("Unknown", ignoreCase = true)
            if (isPlaceholder) { et.setText(""); et.hint = if (currentLanguage == LANG_ENGLISH) enHint else zhHint }
            else et.setText(v)
        }

        llVerifyPanel.visibility = View.VISIBLE

        if (parsed != null) {
            currentPatientInfo = com.example.patientid.core.PatientInfo(
                name = if (betterName.isNotBlank()) betterName else parsed.name,
                birthDate = if (rocBirth.isNotBlank()) rocBirth else parsed.birthDate,
                medicalId = parsed.medicalId,
                examType = parsed.examType
            )

            etName.isEnabled = false; etBirth.isEnabled = false; etMedicalId.isEnabled = false
            for (i in 0 until rgGender.childCount) rgGender.getChildAt(i).isEnabled = false

            putField(currentPatientInfo?.medicalId, etMedicalId, "未辨識", "Unknown")
            putField(currentPatientInfo?.name,      etName,      "未辨識", "Unknown")

            val uiBirth = rocForUi(currentPatientInfo?.birthDate ?: "")
            putField(uiBirth, etBirth, "未辨識（民國YYY/MM/DD）", "Unknown (ROC YYY/MM/DD)")

            selectGenderFromText(lastOcrFullText)

            // MainActivity.kt（handleOCRSuccess 內，準備語音之前）
            val uiExam = etExam.text?.toString()?.trim().orEmpty()
            if (uiExam.isNotEmpty()) {
                currentPatientInfo = currentPatientInfo?.copy(examType = uiExam)
            }
            currentPatientInfo?.let { info ->
                val speechText = com.example.patientid.speechtext.SpeechText.build(info, currentLanguage)
                speakText(speechText)
            }
        } else {
            currentPatientInfo = com.example.patientid.core.PatientInfo("","","","")
            etName.isEnabled = true; etBirth.isEnabled = true; etMedicalId.isEnabled = true
            for (i in 0 until rgGender.childCount) rgGender.getChildAt(i).isEnabled = true

            putField("", etMedicalId, "未辨識", "Unknown")
            putField(betterName, etName, "未辨識", "Unknown")
            val uiBirth = rocForUi(rocBirth)
            putField(uiBirth, etBirth, "未辨識（民國YYY/MM/DD）", "Unknown (ROC YYY/MM/DD)")
            rgGender.clearCheck()

            val fallback = if (currentLanguage == LANG_CHINESE)
                "目前無法從影像中明確辨識姓名、生日或病歷號，請手動輸入後按「確認」。"
            else
                "We couldn't clearly recognize your name, date of birth, or medical ID. Please enter them manually and press Confirm."
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
        if (!isTtsInitialized) return
        lastSpeechText = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), "patient_verification")
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

    // 取代原本會 append 到 textResult 的版本：改為叫出彈窗
    private fun handleVerificationSuccess() {
        currentPatientInfo?.let { info ->
            showVerificationSuccessDialog(info)
        }
    }

    // 新增：驗證成功彈出視窗（中英支援）
    private fun showVerificationSuccessDialog(info: PatientInfo) {
        val title = if (currentLanguage == LANG_ENGLISH) "Verification Successful" else "驗證成功"
        val ok = if (currentLanguage == LANG_ENGLISH) "OK" else "知道了"

        // 生日已在 currentPatientInfo 內是民國格式（例如 民國069/01/29）
        // 顯示時若你想去掉「民國」兩字，可用你現有的 rocForUi(info.birthDate)
        val birthForDialog = info.birthDate

        val msg = if (currentLanguage == LANG_ENGLISH) {
            buildString {
                append("Patient verification completed\n")
                append("Name: ${info.name}\n")
                append("Date of birth: ${info.birthDate}\n")
                append("Medical ID: ${info.medicalId}\n")
                append("Examination: ${info.examType}")
            }
        } else if (currentLanguage == LANG_KOREAN) {
            buildString {
                append("환자 신원 확인 완료\n")
                append("이름: ${info.name}\n")
                append("생년월일: ${info.birthDate}\n")
                append("환자 번호: ${info.medicalId}\n")
                append("검사 항목: ${info.examType}")
            }
        } else {
            buildString {
                append("病患身份驗證完成\n")
                append("姓名：${info.name}\n")
                append("出生日期：${info.birthDate}\n")
                append("病歷號：${info.medicalId}\n")
                append("檢查項目：${info.examType}")
            }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(msg)
            .setCancelable(true)
            .setPositiveButton(ok) { dialog, _ -> dialog.dismiss() }
            .show()
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
