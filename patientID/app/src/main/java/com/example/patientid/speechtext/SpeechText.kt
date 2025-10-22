package com.example.patientid.speechtext

import com.example.patientid.core.PatientInfo
import java.util.Locale

object SpeechText {

    // 將「民國069/01/29」或「069/01/29」等，轉成「69年1月29日」用於中文語音
    private fun zhBirthForSpeech(birth: String): String {
        if (birth.isBlank()) return birth
        val regex = Regex("""(?:民國)?\s*(\d{1,3})[./\-年]?(\d{1,2})[./\-月]?(\d{1,2})""")
        val m = regex.find(birth) ?: return birth
        val y = m.groupValues[1].toIntOrNull() ?: return birth
        val mm = m.groupValues[2].toIntOrNull() ?: return birth
        val dd = m.groupValues[3].toIntOrNull() ?: return birth
        return "${y}年${mm}月${dd}日"
    }

    // 將「民國069/01/29」「069/01/29」「69-1-29」等，轉成韓文語音友善「69년 1월 29일」
    private fun koBirthForSpeech(birth: String): String {
        if (birth.isBlank()) return birth
        val regex = Regex("""(?:민국|民國)?\s*(\d{1,3})[./\-年]?(\d{1,2})[./\-月]?(\d{1,2})""")
        val m = regex.find(birth) ?: return birth
        val y = m.groupValues[1].toIntOrNull() ?: return birth
        val mm = m.groupValues[2].toIntOrNull() ?: return birth
        val dd = m.groupValues[3].toIntOrNull() ?: return birth
        return "${y}년 ${mm}월 ${dd}일"
    }

    // 病歷號逐字輸出，確保 TTS 逐字唸（4 3 4 8 8 5 / A B 1 2 3）
    private fun idForTTS(id: String): String {
        if (id.isBlank()) return id
        // 僅保留英數、轉大寫，逐字加空白
        val alnum = id.filter { it.isLetterOrDigit() }.uppercase(Locale.getDefault())
        return alnum.map { it }.joinToString(" ")
    }

    // 讓 TTS 更穩定的簡單清理（避免奇怪引號、全形標點影響停頓）
    private fun normalizeForTTS(s: String): String {
        return s
            .replace('’', '\'')
            .replace('‘', '\'')
            .replace('“', '"')
            .replace('”', '"')
            .trim()
    }

    /**
     * @param langCode "zh" | "en" | "ko"
     */
    fun build(info: PatientInfo, langCode: String): String {
        val unkZh = "未辨識"
        val unkEn = "Unknown"
        val unkKo = "인식되지 않음"

        val nameKnown  = info.name.isNotBlank()      && info.name      !in listOf(unkZh, unkEn, unkKo)
        val birthKnown = info.birthDate.isNotBlank() && info.birthDate !in listOf(unkZh, unkEn, unkKo)
        val idKnown    = info.medicalId.isNotBlank() && info.medicalId !in listOf(unkZh, unkEn, unkKo)

        val out = when (langCode) {
            "en" -> {
                val greeting = if (nameKnown) "Hello ${info.name}." else "Hello."
                val examText = if (info.examType.isNotEmpty()) "${info.examType} examination" else "examination"
                buildList {
                    add("$greeting You will have an $examText shortly. Now let me present your medical information.")
                    if (idKnown)    add("Medical ID: ${idForTTS(info.medicalId)}.")
                    if (nameKnown)  add("Name: ${info.name}.")
                    if (birthKnown) add("Date of birth: ${info.birthDate}.")
                    add("Please review on screen and press Confirm or Edit if something is incorrect.")
                }.joinToString(" ")
            }

            "ko" -> {
                val greetName  = if (nameKnown) "${info.name}님" else "안녕하세요."
                val examText   = if (info.examType.isNotEmpty()) "${info.examType} 검사" else "검사"
                val birthSpeak = if (birthKnown) koBirthForSpeech(info.birthDate) else ""
                buildList {
                    add("$greetName 곧 $examText 을(를) 진행합니다. 지금 환자 정보를 안내드립니다.")
                    if (idKnown)    add("환자 번호: ${idForTTS(info.medicalId)}.")
                    if (nameKnown)  add("이름: ${info.name}.")
                    if (birthKnown) add("생년월일: $birthSpeak.")
                    add("화면의 정보를 확인하시고, 오류가 있으면 수정 버튼을 눌러 변경해 주세요.")
                }.joinToString(" ")
            }

            else -> { // zh
                val greetName = if (nameKnown) {
                    when {
                        info.name.contains("先生") || info.name.contains("小姐") || info.name.contains("女士") -> info.name
                        else -> "${info.name}先生"
                    }
                } else "您好"
                val examText   = if (info.examType.isNotEmpty()) "${info.examType}檢查" else "檢查"
                val birthSpeak = if (birthKnown) zhBirthForSpeech(info.birthDate) else ""
                buildList {
                    add("$greetName，等一下將進行$examText。現在呈現您的病歷資訊。")
                    if (idKnown)    add("病歷號為：${idForTTS(info.medicalId)}。")
                    if (nameKnown)  add("姓名為：${info.name}。")
                    if (birthKnown) add("出生年月日為：$birthSpeak。")
                    add("請在畫面上核對並按「確認」，若有錯誤請點「修改」後更正。")
                }.joinToString("")
            }
        }
        return normalizeForTTS(out)
    }
}
