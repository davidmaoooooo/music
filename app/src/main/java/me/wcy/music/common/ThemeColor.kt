package me.wcy.music.common

import android.content.Context
import android.graphics.Color
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat
import me.wcy.music.R
import me.wcy.music.storage.preference.ConfigPreferences

object ThemeColor {
    fun themeOverlayRes(): Int {
        return when (resolveMode()) {
            "red" -> R.style.ThemeOverlay_Music_Red
            "blue" -> R.style.ThemeOverlay_Music_Blue
            "green" -> R.style.ThemeOverlay_Music_Green
            "purple" -> R.style.ThemeOverlay_Music_Purple
            "orange" -> R.style.ThemeOverlay_Music_Orange
            "teal" -> R.style.ThemeOverlay_Music_Teal
            "indigo" -> R.style.ThemeOverlay_Music_Indigo
            "pink" -> R.style.ThemeOverlay_Music_Pink
            "cyan" -> R.style.ThemeOverlay_Music_Cyan
            "custom" -> nearestThemeOverlayRes(customPrimaryColor())
            else -> R.style.ThemeOverlay_Music_Blue
        }
    }

    @ColorInt
    fun primary(context: Context): Int {
        if (ConfigPreferences.themeColorMode == "custom") {
            return customPrimaryColor()
        }
        val resId = when (resolveMode()) {
            "red" -> R.color.red_500
            "blue" -> R.color.theme_blue_primary
            "green" -> R.color.theme_green_primary
            "purple" -> R.color.theme_purple_primary
            "orange" -> R.color.theme_orange_primary
            "teal" -> R.color.theme_teal_primary
            "indigo" -> R.color.theme_indigo_primary
            "pink" -> R.color.theme_pink_primary
            "cyan" -> R.color.theme_cyan_primary
            else -> R.color.theme_blue_primary
        }
        return ContextCompat.getColor(context, resId)
    }

    private fun resolveMode(): String {
        return if (ConfigPreferences.themeColorMode == "custom" &&
            ConfigPreferences.customThemeColorHex.isBlank()
        ) {
            ConfigPreferences.customThemeColorMode
        } else {
            ConfigPreferences.themeColorMode
        }
    }

    @ColorInt
    private fun customPrimaryColor(): Int {
        return runCatching {
            Color.parseColor(ConfigPreferences.customThemeColorHex)
        }.getOrDefault(Color.parseColor("#00897B"))
    }

    private fun nearestThemeOverlayRes(@ColorInt color: Int): Int {
        val candidates = listOf(
            Color.parseColor("#F44336") to R.style.ThemeOverlay_Music_Red,
            Color.parseColor("#1976D2") to R.style.ThemeOverlay_Music_Blue,
            Color.parseColor("#2E7D32") to R.style.ThemeOverlay_Music_Green,
            Color.parseColor("#7B1FA2") to R.style.ThemeOverlay_Music_Purple,
            Color.parseColor("#EF6C00") to R.style.ThemeOverlay_Music_Orange,
            Color.parseColor("#00897B") to R.style.ThemeOverlay_Music_Teal,
            Color.parseColor("#3949AB") to R.style.ThemeOverlay_Music_Indigo,
            Color.parseColor("#C2185B") to R.style.ThemeOverlay_Music_Pink,
            Color.parseColor("#00838F") to R.style.ThemeOverlay_Music_Cyan
        )
        return candidates.minByOrNull { (candidate, _) ->
            val dr = Color.red(color) - Color.red(candidate)
            val dg = Color.green(color) - Color.green(candidate)
            val db = Color.blue(color) - Color.blue(candidate)
            dr * dr + dg * dg + db * db
        }?.second ?: R.style.ThemeOverlay_Music_Blue
    }
}
