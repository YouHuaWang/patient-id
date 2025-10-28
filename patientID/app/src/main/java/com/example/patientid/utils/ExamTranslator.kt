package com.example.patientid.utils

import kotlin.math.min

object ExamTranslator {

    // JSON 載入的補充翻譯 (可動態更新)
    var extraDict: Map<String, String> = emptyMap()

    fun loadExtraDict(jsonDict: Map<String, String>) {
        extraDict = jsonDict
    }

    fun translateExamPart(part: String): String {
        // 1) 先嘗試「代碼→片語」與整句對照（若命中，直接輸出）
        val tokens = part.split(Regex("[\\s+/,]+")).filter { it.isNotBlank() }
        val firstToken = tokens.firstOrNull().orEmpty()

        // 代碼精準對照（若代碼在表內，直接用，不帶數字進中文）
        MedDict.codeDict[firstToken]?.let { mapped ->
            return "$part -> $mapped"
        }

        // 整句比對（固定片語）
        MedDict.phraseDict[part]?.let { phrase -> return "$part -> $phrase" }
        extraDict[part]?.let { phrase -> return "$part -> $phrase" }

        // 2) 規則法：只翻英文，不處理數字與代碼；輸出「左/右 + 部位 + 視角(用和銜接)」
        val phrase = toChinesePhrase(part)
        if (phrase.isNotBlank()) return "$part -> $phrase"

        // 3) 退而求其次：維持原先的逐詞翻（但它可能較醜），當成 fallback
        val translatedTokens = tokens.map { token ->
            // 清英文字母做查表；數字與符號不進翻譯
            val cleaned = token.replace(Regex("[^A-Za-z]"), "").lowercase()
            val baseTrans = MedDict.dict[cleaned]
            val extraTrans = extraDict[cleaned]
            val fuzzyTrans = fuzzyMatch(cleaned, MedDict.dict + extraDict)

            when {
                baseTrans != null -> "$token $baseTrans"
                extraTrans != null -> "$token $extraTrans"
                fuzzyTrans != null -> "$token $fuzzyTrans"
                else -> token
            }
        }.joinToString(" ")

        return translatedTokens
    }

    /** 將 "*340-0004 Lt Ankle AP+Lat" 組成「左踝前後位和側位」；只翻英文，不處理數字/代碼 */
    private fun toChinesePhrase(part: String): String {
        val rawTokens = part.split(Regex("[\\s+/,-]+")).filter { it.isNotBlank() }

        // 忽略代碼/純數字 token
        fun isCodeOrNumber(t: String): Boolean {
            // 代碼：以 * 或數字開頭；或大部分為數字
            return t.matches(Regex("^\\*?\\d[\\dA-Za-z-]*$"))
        }

        // 側別
        var side: String? = null
        // 部位（挑第一個命中者）
        var body: String? = null
        // 視角/位向（依出現順序去重）
        val views = mutableListOf<String>()

        for (t in rawTokens) {
            if (isCodeOrNumber(t)) continue  // 不處理數字/代碼

            val k = t.replace(Regex("[^A-Za-z]"), "").lowercase()
            if (k.isBlank()) continue

            when {
                // 側別
                k in listOf("lt","l","left") -> side = "左"
                k in listOf("rt","r","right") -> side = "右"

                // 視角/位向
                k in listOf("ap","pa","lat","oblique","axial","coronal","sagittal","supine","prone","flex","extension") -> {
                    MedDict.dict[k]?.let { zh ->
                        if (!views.contains(zh)) views.add(zh)
                    }
                }

                // 部位（只取第一個最主要解剖詞）
                MedDict.dict.containsKey(k) && body == null -> {
                    val zh = MedDict.dict[k]
                    // 排除把 "ap/lat" 這類位向誤當部位
                    if (zh != null && zh !in listOf("前後位","後前位","側位","斜位","軸位","冠狀位","矢狀位","仰臥位","俯臥位","屈曲","伸展")) {
                        body = zh
                    }
                }
            }
        }

        // 視角文字：兩個用「和」，三個以上用「、」+最後「和」
        val viewsText = when (views.size) {
            0 -> ""
            1 -> views[0]
            2 -> "${views[0]}和${views[1]}"
            else -> views.dropLast(1).joinToString("、") + "和" + views.last()
        }

        // 組合片語
        val sb = StringBuilder()
        if (!side.isNullOrBlank()) sb.append(side)
        if (!body.isNullOrBlank()) sb.append(body)
        if (viewsText.isNotBlank()) sb.append(viewsText)

        return sb.toString()
    }


    // ====== 模糊比對 (Levenshtein) ======
    private fun fuzzyMatch(word: String, dict: Map<String, String>, maxDistance: Int = 2): String? {
        var bestMatch: String? = null
        var bestDist = Int.MAX_VALUE

        for ((key, value) in dict) {
            val dist = levenshtein(word.lowercase(), key.lowercase())
            if (dist < bestDist && dist <= maxDistance) {
                bestDist = dist
                bestMatch = value
            }
        }
        return bestMatch
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in a.indices) dp[i + 1][0] = i + 1
        for (j in b.indices) dp[0][j + 1] = j + 1

        for (i in a.indices) {
            for (j in b.indices) {
                val cost = if (a[i] == b[j]) 0 else 1
                dp[i + 1][j + 1] = min(
                    dp[i][j + 1] + 1,
                    min(
                        dp[i + 1][j] + 1,
                        dp[i][j] + cost
                    )
                )
            }
        }
        return dp[a.length][b.length]
    }
}