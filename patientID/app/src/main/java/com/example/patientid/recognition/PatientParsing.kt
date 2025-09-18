package com.example.patientid.recognition

import android.util.Log
import com.example.patientid.core.PatientInfo

object PatientParsing {
    fun extractPatientInfo(text: String, isEnglish: Boolean): PatientInfo? {
        Log.d("PatientParsing", "開始解析病患資訊")

        val namePatterns = if (isEnglish)
            listOf(Regex("""Name[:：]?\s*([A-Za-z\s]{2,30})"""), Regex("""Patient[:：]?\s*([A-Za-z\s]{2,30})"""))
        else
            listOf(Regex("""姓名[:：]?\s*([^\s\n\r]{2,10})"""), Regex("""病患[:：]?\s*([^\s\n\r]{2,10})"""), Regex("""患者[:：]?\s*([^\s\n\r]{2,10})"""))

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

        val examPatterns = if (isEnglish)
            listOf(Regex("""Exam[:：]?\s*([^\s\n\r]{2,20})"""), Regex("""Test[:：]?\s*([^\s\n\r]{2,20})"""), Regex("""Procedure[:：]?\s*([^\s\n\r]{2,20})"""))
        else
            listOf(Regex("""檢查[:：]?\s*([^\s\n\r]{2,20})"""), Regex("""項目[:：]?\s*([^\s\n\r]{2,20})"""))

        var name = ""
        for (p in namePatterns) { val m = p.find(text); if (m != null) { name = m.groupValues[1].trim(); break } }

        var birth = ""
        for (p in birthPatterns) { val m = p.find(text); if (m != null) { birth = m.groupValues[1].trim(); break } }

        var id = ""
        for (p in idPatterns) { val m = p.find(text); if (m != null) { id = m.groupValues[1].trim(); break } }

        var exam = ""
        for (p in examPatterns) { val m = p.find(text); if (m != null) { exam = m.groupValues[1].trim(); break } }

        val hasAny = name.isNotEmpty() || birth.isNotEmpty() || id.isNotEmpty() || exam.isNotEmpty()
        return if (hasAny) {
            PatientInfo(
                name = if (name.isNotEmpty()) name else if (isEnglish) "Unknown" else "未辨識",
                birthDate = if (birth.isNotEmpty()) birth else if (isEnglish) "Unknown" else "未辨識",
                medicalId = if (id.isNotEmpty()) id else if (isEnglish) "Unknown" else "未辨識",
                examType = exam
            )
        } else null
    }
}
