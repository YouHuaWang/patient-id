package com.example.patientid.speechtext

import com.example.patientid.core.PatientInfo

object SpeechText {
    fun build(info: PatientInfo, isEnglish: Boolean): String {
        val unkZh = "未辨識"; val unkEn = "Unknown"
        val nameKnown = info.name.isNotBlank() && info.name != unkZh && info.name != unkEn
        val birthKnown = info.birthDate.isNotBlank() && info.birthDate != unkZh && info.birthDate != unkEn
        val idKnown = info.medicalId.isNotBlank() && info.medicalId != unkZh && info.medicalId != unkEn

        return if (isEnglish) {
            val greeting = if (nameKnown) "Hello ${info.name}." else "Hello."
            val examText = if (info.examType.isNotEmpty()) "${info.examType} examination" else "examination"
            buildList {
                add("$greeting You will have an $examText shortly. Now let me verify your medical information.")
                if (nameKnown) add("Name: ${info.name}.")
                if (birthKnown) add("Date of birth: ${info.birthDate}.")
                if (idKnown) add("Medical ID: ${info.medicalId}.")
                add("If the information is correct, please say 'yes' or 'correct'.")
            }.joinToString(" ")
        } else {
            val greetName = if (nameKnown) {
                when {
                    info.name.contains("先生") || info.name.contains("小姐") || info.name.contains("女士") -> info.name
                    else -> "${info.name}先生"
                }
            } else "您好"
            val examText = if (info.examType.isNotEmpty()) "${info.examType}檢查" else "檢查"
            buildList {
                add("$greetName，等一下將進行$examText。現在向您核對病歷資訊。")
                if (nameKnown) add("姓名為：${info.name}。")
                if (birthKnown) add("出生年月日為：${info.birthDate}。")
                if (idKnown) add("病歷號為：${info.medicalId}。")
                add("如果資料正確，請說「是」或「正確」。")
            }.joinToString("")
        }
    }
}
