package com.example.patientid.core

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleSupport {
    const val LANG_CHINESE = "zh"
    const val LANG_ENGLISH = "en"

    fun updateLocale(ctx: Context, language: String) {
        val locale = when (language) {
            LANG_ENGLISH -> Locale.ENGLISH
            else -> Locale.TRADITIONAL_CHINESE
        }
        val res = ctx.resources
        val config: Configuration = res.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        res.updateConfiguration(config, res.displayMetrics)
    }
}
