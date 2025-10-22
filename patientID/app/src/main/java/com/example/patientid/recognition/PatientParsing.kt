package com.example.patientid.recognition

import android.util.Log
import com.example.patientid.core.PatientInfo

object PatientParsing {
    fun extractPatientInfo(text: String, langCode: String): PatientInfo? {
        Log.d("PatientParsing", "namePatterns")
        val isEnglish = langCode == "en"
        val isKorean = langCode == "ko"

        // 原 namePatterns 改為「含 lookahead 截斷」並支援全形分隔符 + 「名:」
        val namePatterns = when {
            isEnglish -> listOf(
                Regex("""Name[:：＝=]?\s*([A-Za-z ·・\-]{2,30})\s*(?=(DOB|Birth|ID|Patient|Exam|Test|Procedure|$))"""),
                Regex("""Patient(?:\s*Name)?[:：＝=]?\s*([A-Za-z ·・\-]{2,30})\s*(?=(DOB|Birth|ID|Name|Exam|Test|Procedure|$))""", RegexOption.IGNORE_CASE)
            )
            isKorean -> listOf(
                Regex("""이름[:：＝=]?\s*([가-힣·\s\-]{1,20})\s*(?=(성별|생년월일|ID|검사|항목|$))"""),
                Regex("""환자[:：＝=]?\s*([가-힣·\s\-]{1,20})\s*(?=(성별|생년월일|ID|검사|항목|$))""")
            )
            else -> listOf(
                Regex("""姓名[:：＝=]?\s*([^\s\n\r:：=]{1,20}?)\s*(?=(性別|出生|生日|病歷號|編號|ID|檢查|項目|$))"""),
                Regex("""名[:：＝=]?\s*([^\s\n\r:：=]{1,20}?)\s*(?=(性別|出生|生日|病歷號|編號|ID|檢查|項目|$))"""),
                Regex("""病患[:：＝=]?\s*([^\s\n\r:：=]{1,20}?)\s*(?=(性別|出生|生日|病歷號|編號|ID|檢查|項目|$))"""),
                Regex("""患者[:：＝=]?\s*([^\s\n\r:：=]{1,20}?)\s*(?=(性別|出生|生日|病歷號|編號|ID|檢查|項目|$))""")
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

        val examPatterns = if (isEnglish)
            listOf(Regex("""Exam[:：]?\s*([^\s\n\r]{2,20})"""), Regex("""Test[:：]?\s*([^\s\n\r]{2,20})"""), Regex("""Procedure[:：]?\s*([^\s\n\r]{2,20})"""))
        else
            listOf(Regex("""檢查[:：]?\s*([^\s\n\r]{2,20})"""), Regex("""項目[:：]?\s*([^\s\n\r]{2,20})"""))

        var name = ""
        for (p in namePatterns) {
            val m = p.find(text)
            if (m != null) {
                name = m.groupValues[1].trim()
                // 移除姓名中的特殊符號（如 = 等）
                name = cleanupNameField(name)
                break
            }
        }

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

    /**
     * 清理姓名欄位中的特殊符號
     * 移除可能影響語音播放的符號，如 =、#、*、& 等
     */
    // 清理姓名欄位中的特殊符號（但保留人名常見的「·」「-」「空白」）
    private fun cleanupNameField(name: String): String {
        return name
            .replace(Regex("""[^ \u4e00-\u9fffA-Za-z·・\-]"""), "") // 只保留 中文/英文/空白/·/-/・
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}