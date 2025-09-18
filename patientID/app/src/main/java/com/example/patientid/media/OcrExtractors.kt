package com.example.patientid.recognition

import com.example.patientid.utils.ExamTranslator
import com.google.mlkit.vision.text.Text

object OcrExtractors {

    fun extractExamItemsUnified(fullText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }

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

        val examParts = mutableListOf<String>()
        var buffer: String? = null
        var collecting = false
        val codeRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*$")
        val codeWithDescRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*\\s+.+")
        val raRegex = Regex("^RA\\d+")
        val noise = listOf("kV","mAs","診","斷","健保","醫師","科別","檢體")

        for (line in lines) {
            if (line.contains("列印時間")) { collecting = true; continue }
            if (line.contains("檢查說明")) {
                collecting = false
                buffer?.let { examParts.add(it) }
                buffer = null
                break
            }
            if (collecting) {
                if (noise.any { line.contains(it) }) continue
                if (raRegex.matches(line)) continue
                when {
                    codeWithDescRegex.matches(line) -> { buffer?.let { examParts.add(it) }; buffer = null; examParts.add(line) }
                    codeRegex.matches(line)        -> { buffer?.let { examParts.add(it) }; buffer = line }
                    buffer != null                 -> { buffer += " $line"; examParts.add(buffer!!); buffer = null }
                }
            }
        }
        buffer?.let { examParts.add(it) }

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
            result["檢查項目"] = translated.joinToString("\n")
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
        val examParts = mutableListOf<String>()
        var buffer: String? = null
        var collecting = false
        val codeRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*$")
        val codeWithDescRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*\\s+.+")
        val raRegex = Regex("^RA\\d+")
        val noise = listOf("kV","mAs","診","斷","健保","醫師","科別","檢體")

        for (line in lines) {
            if (line.contains("列印時間")) { collecting = true; continue }
            if (line.contains("檢查說明")) {
                collecting = false; buffer?.let { examParts.add(it) }; buffer = null; break
            }
            if (collecting) {
                if (noise.any { line.contains(it) }) continue
                if (raRegex.matches(line)) continue
                when {
                    codeWithDescRegex.matches(line) -> { buffer?.let { examParts.add(it) }; buffer = null; examParts.add(line) }
                    codeRegex.matches(line)        -> { buffer?.let { examParts.add(it) }; buffer = line }
                    buffer != null                 -> { buffer += " $line"; examParts.add(buffer!!); buffer = null }
                }
            }
        }
        buffer?.let { examParts.add(it) }
        if (examParts.isNotEmpty()) result["檢查部位"] = examParts.joinToString("\n")
        return result
    }

    fun extractExamItems(fullText: String): List<String> {
        val lines = fullText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        val examItems = mutableListOf<String>()
        var collecting = false
        var buffer: String? = null
        val codeRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*$")
        val codeWithDescRegex = Regex("^\\*?\\d{3,}[A-Za-z0-9-]*\\s+.+")
        val raRegex = Regex("^RA\\d+")
        val noise = listOf("kV","mAs","診","斷","健保","醫師","科別","檢體")

        for (line in lines) {
            if (line.contains("列印時間")) { collecting = true; continue }
            if (collecting) {
                if (noise.any { line.contains(it) }) continue
                if (raRegex.matches(line)) continue
                when {
                    codeWithDescRegex.matches(line) -> { buffer?.let { examItems.add(it) }; buffer = null; examItems.add(line) }
                    codeRegex.matches(line)        -> { buffer?.let { examItems.add(it) }; buffer = line }
                    buffer != null                 -> { buffer += " $line"; examItems.add(buffer!!); buffer = null }
                }
            }
        }
        buffer?.let { examItems.add(it) }
        return examItems
    }
}
