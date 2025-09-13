package com.example.patientid.utils

import kotlin.math.min

object ExamTranslator {

    // JSON 載入的補充翻譯 (可動態更新)
    var extraDict: Map<String, String> = emptyMap()

    fun loadExtraDict(jsonDict: Map<String, String>) {
        extraDict = jsonDict
    }

    fun translateExamPart(part: String): String {
        val tokens = part.split(" ").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return part

        val firstToken = tokens.first()

        // === Step 1: 代碼對照 ===
        MedDict.codeDict[firstToken]?.let { mapped ->
            return "$part → $firstToken $mapped"
        }

        // === Step 2: 整句比對 ===
        MedDict.phraseDict[part]?.let { phrase ->
            return "$part → $phrase"
        }
        extraDict[part]?.let { phrase ->
            return "$part → $phrase"
        }

        // === Step 3: 逐詞翻譯 (含模糊修正) ===
        val translatedTokens = tokens.map { token ->
            val cleaned = token.replace(Regex("[^A-Za-z]"), "")
            val baseTrans = MedDict.dict[cleaned]
            val extraTrans = extraDict[cleaned]
            val fuzzyTrans = fuzzyMatch(cleaned, MedDict.dict + extraDict)

            when {
                baseTrans != null -> "$token $baseTrans"
                extraTrans != null -> "$token $extraTrans"
                fuzzyTrans != null -> "$token $fuzzyTrans"
                else -> token
            }
        }

        // === Step 4: 側別補強 ===
        val sideInfo = when {
            Regex("\\b(Lt|LI|L)\\b", RegexOption.IGNORE_CASE).containsMatchIn(part) -> "左"
            Regex("\\b(Rt|RI|R)\\b", RegexOption.IGNORE_CASE).containsMatchIn(part) -> "右"
            else -> ""
        }

        return if (sideInfo.isNotEmpty() && !translatedTokens.joinToString(" ").contains(sideInfo)) {
            "$part → $sideInfo ${translatedTokens.joinToString(" ")}"
        } else {
            translatedTokens.joinToString(" ")
        }
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