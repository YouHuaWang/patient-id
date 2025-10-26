package com.example.patientid.recognition

import com.example.patientid.utils.ExamTranslator
import com.google.mlkit.vision.text.Text

object OcrExtractors {

    // 以「一般檢查 / Routine」為錨點，取其下一行開始的連續內容，直到遇到下一個段落標題或終止詞
    private fun collectRoutineSection(linesIn: List<String>): List<String> {
        val lines = linesIn.map { it.trim() }.filter { it.isNotEmpty() }
        val startIdx = lines.indexOfFirst { it.contains("一般檢查") || it.contains("Routine", ignoreCase = true) }
        if (startIdx < 0 || startIdx + 1 >= lines.size) return emptyList()

        val stopMarkers = listOf(
            "檢查說明","特殊檢查","說明","備註","影像",
            "Impression","Finding","Findings","Special","Note","Remark"
        )
        val noise = listOf("kV","mAs","診","斷","健保","醫師","科別","檢體")

        val out = mutableListOf<String>()
        for (i in (startIdx + 1) until lines.size) {
            val line = lines[i]
            if (stopMarkers.any { m -> line.contains(m, ignoreCase = true) }) break
            if (noise.any { n -> line.contains(n, ignoreCase = true) }) continue
            out.add(line)
        }
        return out
    }

    fun extractExamItemsUnified(fullText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        // 先抓基本欄位（沿用原本邏輯）
        for (line in lines) {
            if (!result.containsKey("病歷號"))
                Regex("病歷號[:：]?\\s*([A-Za-z0-9]+)").find(line)?.let { result["病歷號"] = it.groupValues[1] }
            if (!result.containsKey("姓名"))
                Regex("姓名[:：]?\\s*([\\u4e00-\\u9fffA-Za-z]{2,10})").find(line)?.let { result["姓名"] = it.groupValues[1] }
            if (!result.containsKey("性別"))
                Regex("性別[:：]?\\s*(男|女|男性|女性)").find(line)?.let { result["性別"] = it.groupValues[1] }
            if (!result.containsKey("生日") && (line.contains("生日") || line.contains("出生")))
                result["生日"] = line.replace(" ", "").replace("<<健保>>", "")
        }

        // 🔁 改抓「一般檢查 / Routine」底下的區段
        val section = collectRoutineSection(lines)

        val rawExamParts = mutableListOf<String>()
        var buffer: String? = null
        val codeRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*$")
        val codeWithDescRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*\\s*.*")
        val raRegex = Regex("^RA\\d+")

        for (line in section) {
            if (raRegex.matches(line)) continue
            when {
                codeWithDescRegex.matches(line) -> { buffer?.let { rawExamParts.add(it) }; buffer = null; rawExamParts.add(line) }
                codeRegex.matches(line)        -> { buffer?.let { rawExamParts.add(it) }; buffer = line }
                buffer != null                 -> { buffer += " $line"; rawExamParts.add(buffer!!); buffer = null }
                else -> {rawExamParts.add(line)}
            }
        }
        buffer?.let { rawExamParts.add(it) }

        val examParts = rawExamParts.filter {
            it.length > 5 && !Regex("^[右]+$").matches(it.trim())
        }

        if (examParts.isNotEmpty()) {
            val normalized = examParts.map { part ->
                val tokens = part.split(" ").filter { it.isNotBlank() }
                if (tokens.size >= 3) "${tokens[0]} ${tokens[1]} ${tokens.drop(2).joinToString(" ")}" else part
            }
            val sorted = normalized.sortedWith(compareBy(
                { if (it.startsWith("*")) 0 else 1 },
                { Regex("\\d+").find(it)?.value?.toIntOrNull() ?: Int.MAX_VALUE }
            ))
            val translated = sorted.map { part -> ExamTranslator.translateExamPart(part) }

            // 只保留翻譯結果中的中文部分（ExamTranslator 回傳形如 "... -> 中文描述"）
            val onlyChinese = translated.map { t ->
                if (t.contains("->")) {
                    t.substringAfter("->").trim()
                } else {
                    Regex("([\\u4e00-\\u9fff].+)").find(t)?.value ?: t
                }
            }
            result["檢查項目"] = onlyChinese.joinToString("\n")
        }
        return result
    }


    fun extractMedicalFormFields(visionText: Text): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = visionText.text.split("\n").map { it.trimEnd() }
        for (line in lines) {
            if (!result.containsKey("病歷號"))
                Regex("病歷號[:：]?\\s*([A-Za-z0-9]+)").find(line)?.let { result["病歷號"] = it.groupValues[1] }
            if (!result.containsKey("姓名"))
                Regex("姓名[:：]?\\s*([\\u4e00-\\u9fffA-Za-z]{2,10})").find(line)?.let { result["姓名"] = it.groupValues[1] }
            if (!result.containsKey("性別"))
                Regex("性別[:：]?\\s*(男|女|男性|女性)").find(line)?.let { result["性別"] = it.groupValues[1] }
            if (!result.containsKey("生日") && (line.contains("生日") || line.contains("出生")))
                result["生日"] = line.replace(" ", "")
        }
        return result
    }

    fun extractMedicalFormFieldsByBlock(visionText: Text): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (block in visionText.textBlocks) {
            val t = block.text.trim()
            if (t.contains("病歷號") && !result.containsKey("病歷號"))
                Regex("病歷號[:：]?([A-Za-z0-9]+)").find(t)?.let { result["病歷號"] = it.groupValues[1] }
            if (t.contains("姓名") && !result.containsKey("姓名"))
                Regex("姓名[:：]?([\\u4e00-\\u9fffA-Za-z]{2,10})").find(t)?.let { result["姓名"] = it.groupValues[1] }
            if (t.contains("性別") && !result.containsKey("性別"))
                Regex("性別[:：]?(男|女|男性|女性)").find(t)?.let { result["性別"] = it.groupValues[1] }
            if ((t.contains("生日") || t.contains("出生")) && !result.containsKey("生日"))
                result["生日"] = t.replace(" ", "")
        }
        return result
    }

    fun extractMedicalFormFieldsByCustom(fullText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

        // 🔁 改抓「一般檢查 / Routine」底下的區段
        val section = collectRoutineSection(lines)

        val examParts = mutableListOf<String>()
        var buffer: String? = null
        val codeRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*$")
        val codeWithDescRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*\\s+.+")
        val raRegex = Regex("^RA\\d+")

        for (line in section) {
            if (raRegex.matches(line)) continue
            when {
                codeWithDescRegex.matches(line) -> { buffer?.let { examParts.add(it) }; buffer = null; examParts.add(line) }
                codeRegex.matches(line)        -> { buffer?.let { examParts.add(it) }; buffer = line }
                buffer != null                 -> { buffer += " $line"; examParts.add(buffer!!); buffer = null }
            }
        }
        buffer?.let { examParts.add(it) }

        if (examParts.isNotEmpty()) {
            result["檢查部位"] = examParts.joinToString("\n")
        }
        return result
    }


    fun extractExamItems(fullText: String): List<String> {
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        // 🔁 改抓「一般檢查 / Routine」底下的區段
        val section = collectRoutineSection(lines)

        val examItems = mutableListOf<String>()
        var buffer: String? = null
        val codeRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*$")
        val codeWithDescRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*\\s+.+")
        val raRegex = Regex("^RA\\d+")

        for (line in section) {
            if (raRegex.matches(line)) continue
            when {
                codeWithDescRegex.matches(line) -> { buffer?.let { examItems.add(it) }; buffer = null; examItems.add(line) }
                codeRegex.matches(line)        -> { buffer?.let { examItems.add(it) }; buffer = line }
                buffer != null                 -> { buffer += " $line"; examItems.add(buffer!!); buffer = null }
            }
        }
        buffer?.let { examItems.add(it) }
        return examItems
    }







    // 版面感知的姓名抽取（強化版）：支援「姓名:」「名:」「Name:」「Patient Name:」等
    fun extractNameSmart(visionText: Text, fullText: String): String? {
        fun isLabelToken(t: String): Boolean {
            val s = t.replace("＝","=").replace("：",":")
            return s.equals("姓名", true) || s.equals("名", true) ||
                    s.equals("Name", true) || s.equals("Patient", true) ||
                    s.equals("Patient Name", true) || s.startsWith("PatientName", true)
        }

        // 1) 先用版面（同一行、標籤右側）
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val rawLine = line.text.replace("＝", "=").replace("：", ":").trim()
                val hasNameLabel = rawLine.contains("姓名") || rawLine.contains("名") ||
                        rawLine.contains("Name", true) || rawLine.contains("Patient Name", true)
                if (!hasNameLabel) continue

                val elements = line.elements
                val labelIdx = elements.indexOfFirst { e ->
                    val t = e.text.trim()
                    isLabelToken(t) || t.contains("姓名") || t == "名"
                }
                if (labelIdx >= 0) {
                    val labelRight = elements[labelIdx].boundingBox?.right ?: Int.MIN_VALUE

                    val sb = StringBuilder()
                    for (i in labelIdx until elements.size) {
                        val e = elements[i]
                        val x = e.boundingBox?.left ?: Int.MAX_VALUE
                        val t = e.text.trim()

                        // 跳過標點
                        if (t == ":" || t == "：" || t == "=" || t == "＝") continue
                        // 只收集位於標籤右側
                        if (x > labelRight) {
                            // 下個欄位關鍵字就停
                            val stop = t.contains("性別") || t.contains("出生") || t.contains("生日") ||
                                    t.contains("病歷號") || t.equals("DOB", true) || t.equals("ID", true)
                            if (stop) break
                            sb.append(t)
                        }
                    }
                    val candidate = cleanupNameCandidate(sb.toString())
                    if (isLikelyName(candidate)) return candidate
                }

                // 2) 本行只有「姓名:」或「名:」→ 下一行是姓名
                if (rawLine.matches(Regex("""^(姓名|名)\s*[:＝=：]?\s*$""", RegexOption.IGNORE_CASE))) {
                    val idx = block.lines.indexOf(line)
                    if (idx >= 0 && idx + 1 < block.lines.size) {
                        val next = block.lines[idx + 1].text.trim()
                        val candidate = cleanupNameCandidate(next)
                        if (isLikelyName(candidate)) return candidate
                    }
                }

                // 3) 同行形式：「姓名:王小明 生日:」或「名=王小明 性別:」
                Regex("""(姓名|名)\s*[:＝=：]\s*([^\s:：=]+)""").find(rawLine)?.let { m ->
                    val after = m.groupValues[2]
                    val candidate = cleanupNameCandidate(after.split(Regex("""(性別|出生|生日|病歷號|DOB|ID)"""))[0])
                    if (isLikelyName(candidate)) return candidate
                }
            }
        }

        // 4) 全文 fallback：含 lookahead 截斷，加入「名:」
        val fw = fullText.replace("＝", "=").replace("：", ":")
        val r = Regex("""(姓名|名)\s*[:=]?\s*([^\s:：=]{1,20}?)\s*(?=(性別|出生|生日|病歷號|DOB|ID|$))""")
        r.find(fw)?.let { m ->
            val candidate = cleanupNameCandidate(m.groupValues[2])
            if (isLikelyName(candidate)) return candidate
        }
        // 英文 fallback
        val rEn = Regex("""(Patient Name|Name)\s*[:=]?\s*([A-Za-z ·・\-]{2,30})\s*(?=(DOB|Birth|ID|Exam|Test|Procedure|$))""", RegexOption.IGNORE_CASE)
        rEn.find(fw)?.let { m ->
            val candidate = cleanupNameCandidate(m.groupValues[2])
            if (isLikelyName(candidate)) return candidate
        }
        return null
    }


    // 去噪：保留常見人名合法字元（中文、英文、·、-、空白），去掉其它符號
    private fun cleanupNameCandidate(s: String): String {
        if (s.isBlank()) return s
        val kept = s.replace(Regex("""[^ \u4e00-\u9fffA-Za-z·・\-]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()
        return kept
    }

    // 粗略判斷是否像姓名：無數字，長度合理（中文2~6；英文2~30，允許空白/·/-）
    private fun isLikelyName(s: String): Boolean {
        if (s.isBlank()) return false
        if (s.any { it.isDigit() }) return false
        val han = Regex("""^[\u4e00-\u9fff·・\-]{2,8}$""")
        val eng = Regex("""^[A-Za-z][A-Za-z ·・\-]{1,29}$""")
        return han.matches(s) || eng.matches(s)
    }




}
