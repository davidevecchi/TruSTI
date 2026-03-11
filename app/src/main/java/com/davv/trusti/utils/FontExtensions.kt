package com.davv.trusti.utils

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.davv.trusti.R

/**
 * Extension functions to ensure font loading works correctly
 */
object FontExtensions {
    
    /**
     * Apply the Tsukimi Rounded font programmatically
     */
    fun applyTsukimiFont(textView: TextView, weight: Int = Typeface.NORMAL) {
        val context = textView.context
        try {
            val fontRes = when (weight) {
                Typeface.BOLD -> R.font.tsukimi_rounded_bold
                else -> R.font.tsukimi_rounded_regular
            }
            textView.typeface = ResourcesCompat.getFont(context, fontRes)
        } catch (e: Exception) {
            android.util.Log.w("FontExtensions", "Failed to load custom font: ${e.message}")
        }
    }
    
    /**
     * Apply font from resources with specific weight
     */
    fun applyTsukimiFontFromResources(textView: TextView, context: Context, weight: Int = 400) {
        try {
            val fontRes = when (weight) {
                300 -> R.font.tsukimi_rounded_light
                400 -> R.font.tsukimi_rounded_regular
                500 -> R.font.tsukimi_rounded_medium
                600 -> R.font.tsukimi_rounded_semibold
                700 -> R.font.tsukimi_rounded_bold
                else -> R.font.tsukimi_rounded_regular
            }
            
            textView.typeface = ResourcesCompat.getFont(context, fontRes)
        } catch (e: Exception) {
            android.util.Log.w("FontExtensions", "Failed to load font from resources: ${e.message}")
        }
    }
}
