package com.example.patientid.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleSupport {
    const val LANG_CHINESE = "zh"
    const val LANG_ENGLISH = "en"
    const val LANG_KOREAN = "ko"   // ✅ 新增韓文常數

    fun updateLocale(ctx: Context, language: String) {
        val locale = when (language) {
            LANG_ENGLISH -> Locale.ENGLISH
            LANG_KOREAN -> Locale.KOREAN   // ✅ 新增韓文分支
            else -> Locale.TRADITIONAL_CHINESE
        }
        val res = ctx.resources
        val config: Configuration = res.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }
}
