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
    // SpeechText.kt 內
    fun build(info: PatientInfo, currentLanguage: String): String {
        // 共同旗標
        val idKnown = info.medicalId.isNotBlank() && !info.medicalId.equals("未辨識", true) && !info.medicalId.equals("unknown", true)
        val nameKnown = info.name.isNotBlank() && !info.name.equals("未辨識", true) && !info.name.equals("unknown", true)
        val birthKnown = info.birthDate.isNotBlank() && !info.birthDate.equals("未辨識", true) && !info.birthDate.equals("unknown", true)
        val examKnown = info.examType.isNotBlank() && !info.examType.equals("未辨識", true) && !info.examType.equals("unknown", true)

        // 病歷號一律逐字念
        val medicalIdSpeak = if (idKnown) digitByDigit(info.medicalId) else ""

        return when (currentLanguage.lowercase()) {
            "en" -> {
                val greeting = if (nameKnown) "Hello ${info.name}." else "Hello."
                val examSpeak = if (examKnown) info.examType else "examination"
                buildList {
                    // 將檢查項目插入到第一句
                    add("$greeting You will have the \"$examSpeak\" examination shortly. Now let me present your medical information.")
                    if (idKnown) add("Medical ID: $medicalIdSpeak.")
                    if (nameKnown) add("Name: ${info.name}.")
                    if (birthKnown) add("Date of birth: ${info.birthDate}.")
                    add("Please review on screen and press Confirm or Edit if something is incorrect.")
                }.joinToString(" ")
            }

            "ko" -> {
                // 韓文版問候與結構
                val greetName = if (nameKnown) "${info.name}님 안녕하세요" else "안녕하세요"
                val examSpeak = if (examKnown) info.examType else "검사"
                buildList {
                    add("$greetName. 잠시 후 \"$examSpeak\" 검사가 진행됩니다. 이제 진료 정보를 확인해 드리겠습니다.")
                    if (idKnown) add("환자 번호: $medicalIdSpeak.")
                    if (nameKnown) add("이름: ${info.name}.")
                    if (birthKnown) add("생년월일: ${info.birthDate}.")
                    add("화면의 정보를 확인하시고 이상이 있으면 ‘수정’, 맞다면 ‘확인’을 눌러 주세요.")
                }.joinToString(" ")
            }

            else -> { // zh
                val greetName = if (nameKnown) {
                    when {
                        info.name.contains("先生") || info.name.contains("小姐") || info.name.contains("女士") -> "${info.name}您好"
                        else -> "${info.name}先生您好"
                    }
                } else "您好"

                // 整理成口語：例如 "Lt左 Ankle踝 AP前後位 Lat側位" -> "左踝前後位和側位"
                val examSpeakRaw = if (examKnown) info.examType else "檢查"
                val examSpeak = normalizeZhExamForSpeech(examSpeakRaw)

                val birthSpeak = if (birthKnown) zhBirthForSpeech(info.birthDate) else ""
                buildList {
                    add("$greetName，等一下將進行「$examSpeak」之檢查，現在與您確認病歷資訊。")
                    if (idKnown) add("病歷號為：$medicalIdSpeak。")
                    if (nameKnown) add("姓名為：${info.name}。")
                    if (birthKnown) add("出生年月日為：$birthSpeak。")
                    add("請在畫面上核對並按「確認」，若有錯誤請點「修改」後更正。")
                }.joinToString("")
            }
        }
    }

    // 將 "434885" → "4 3 4 8 8 5"
    private fun digitByDigit(input: String): String {
        if (input.isBlank()) return input
        val sb = StringBuilder()
        input.forEach { ch ->
            if (ch.isDigit()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(ch)
            } else {
                // 非數字原樣帶過（避免把英數混合ID弄壞）
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(ch)
            }
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    // 把「Lt左 Ankle踝 AP前後位 Lat側位」變成「左踝前後位和側位」等更口語
    private fun normalizeZhExamForSpeech(raw: String): String {
        var s = raw
            .replace("Lt 左", "左")
            .replace("Rt 右", "右")
            .replace("Lt", "左")
            .replace("Rt", "右")
            .replace("Ankle 踝", "踝")
            .replace("Knee 膝", "膝")
            .replace("Wrist 腕", "腕")
            .replace("Elbow 肘", "肘")
            .replace("Shoulder 肩", "肩")
            .replace("Hip 髖", "髖")
            .replace("Foot 足", "足")
            .replace("Hand 手", "手")

        // 視角合併：AP + Lat → 前後位和側位；若中間以 + 或空白都處理
        s = s.replace(Regex("""AP\s*前後位\s*(\+|和|,)?\s*Lat\s*側位"""), "前後位和側位")
            .replace(Regex("""Lat\s*側位\s*(\+|和|,)?\s*AP\s*前後位"""), "前後位和側位")

        // 去掉多餘空格、標點
        s = s.replace(Regex("""\s+"""), "")
        return s
    }


}
