package com.example.patientid.speechtext

import com.example.patientid.core.PatientInfo

object SpeechText {

    // 將「民國069/01/29」或「069/01/29」等，轉成「69年1月29日」用於中文語音
    private fun zhBirthForSpeech(birth: String): String {
        if (birth.isBlank()) return birth
        // 可能出現：民國069/01/29、069/1/29、民國 69-01-29、069.01.29 等
        val regex = Regex("""(?:民國)?\s*(\d{1,3})[./\-年]?(\d{1,2})[./\-月]?(\d{1,2})""")
        val m = regex.find(birth) ?: return birth
        val y = m.groupValues[1].toIntOrNull() ?: return birth
        val mm = m.groupValues[2].toIntOrNull() ?: return birth
        val dd = m.groupValues[3].toIntOrNull() ?: return birth
        // 去除前導 0：例如 069 -> 69；01 -> 1
        val yStr = y.toString()
        val mStr = mm.toString()
        val dStr = dd.toString()
        return "${yStr}年${mStr}月${dStr}日"
    }

    fun build(info: PatientInfo, isEnglish: Boolean): String {
        val unkZh = "未辨識"; val unkEn = "Unknown"
        val nameKnown = info.name.isNotBlank() && info.name != unkZh && info.name != unkEn
        val birthKnown = info.birthDate.isNotBlank() && info.birthDate != unkZh && info.birthDate != unkEn
        val idKnown = info.medicalId.isNotBlank() && info.medicalId != unkZh && info.medicalId != unkEn

        return if (isEnglish) {
            val greeting = if (nameKnown) "Hello ${info.name}." else "Hello."
            val examText = if (info.examType.isNotEmpty()) "${info.examType} examination" else "examination"
            buildList {
                add("$greeting You will have an $examText shortly. Now let me present your medical information.")
                if (idKnown) add("Medical ID: ${info.medicalId}.")
                if (nameKnown) add("Name: ${info.name}.")
                if (birthKnown) add("Date of birth: ${info.birthDate}.")
                add("Please review on screen and press Confirm or Edit if something is incorrect.")
            }.joinToString(" ")
        } else {
            val greetName = if (nameKnown) {
                when {
                    info.name.contains("先生") || info.name.contains("小姐") || info.name.contains("女士") -> info.name
                    else -> "${info.name}先生"
                }
            } else "您好"
            val examText = if (info.examType.isNotEmpty()) "${info.examType}檢查" else "檢查"
            val birthSpeak = if (birthKnown) zhBirthForSpeech(info.birthDate) else ""
            buildList {
                add("$greetName，等一下將進行$examText。現在呈現您的病歷資訊。")
                if (idKnown) add("病歷號為：${info.medicalId}。")
                if (nameKnown) add("姓名為：${info.name}。")
                if (birthKnown) add("出生年月日為：$birthSpeak。")
                add("請在畫面上核對並按「確認」，若有錯誤請點「修改」後更正。")
            }.joinToString("")
        }
    }

}