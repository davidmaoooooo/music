package me.wcy.music.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.audiofx.AudioEffect
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.wcy.music.R
import me.wcy.music.common.BaseMusicActivity
import me.wcy.music.common.DarkModeService
import me.wcy.music.consts.PreferenceName
import me.wcy.music.net.MusicCacheManager
import me.wcy.music.service.PlayerController
import me.wcy.music.storage.preference.ConfigPreferences
import me.wcy.music.utils.MusicUtils
import me.wcy.router.CRouter
import me.wcy.router.annotation.Route
import top.wangchenyan.common.R as CommonR
import top.wangchenyan.common.ext.toast
import top.wangchenyan.common.widget.TitleLayout
import javax.inject.Inject

@Route("/settings")
@AndroidEntryPoint
class SettingsActivity : BaseMusicActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        findViewById<TitleLayout>(CommonR.id.common_title_layout)?.setTitleText("功能设置")
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, SettingsFragment())
            .commitAllowingStateLoss()
    }

    @AndroidEntryPoint
    class SettingsFragment : PreferenceFragmentCompat() {
        private var debugBlankClickCount = 0

        private val darkMode: Preference by lazy {
            findPreference(getString(R.string.setting_key_dark_mode))!!
        }
        private val soundEffect: Preference by lazy {
            findPreference(getString(R.string.setting_key_sound_effect))!!
        }
        private val themeColorMode: Preference by lazy {
            findPreference(getString(R.string.setting_key_theme_color_mode))!!
        }
        private val filterSize: Preference by lazy {
            findPreference(getString(R.string.setting_key_filter_size))!!
        }
        private val filterTime: Preference by lazy {
            findPreference(getString(R.string.setting_key_filter_time))!!
        }
        private val musicCacheLimit: Preference by lazy {
            findPreference(getString(R.string.setting_key_music_cache_limit))!!
        }
        private val clearMusicCache: Preference by lazy {
            findPreference(getString(R.string.setting_key_clear_music_cache))!!
        }

        @Inject
        lateinit var playerController: PlayerController

        @Inject
        lateinit var darkModeService: DarkModeService

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            preferenceManager.sharedPreferencesName = PreferenceName.CONFIG
            addPreferencesFromResource(R.xml.preference_setting)
            alignPreferencesLeft(preferenceScreen)

            initDarkMode()
            initThemeColor()
            initSoundEffect()
            initCache()
            initFilter()
        }

        override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
            super.onViewCreated(view, savedInstanceState)
            listView.setOnTouchListener { recyclerView, event ->
                if (event.action == MotionEvent.ACTION_UP) {
                    val list = recyclerView as RecyclerView
                    val child = list.findChildViewUnder(event.x, event.y)
                    if (child == null) {
                        debugBlankClickCount++
                        if (debugBlankClickCount >= 5) {
                            debugBlankClickCount = 0
                            CRouter.with(requireContext()).url("/debug/settings").start()
                        }
                    } else {
                        debugBlankClickCount = 0
                    }
                }
                false
            }
        }

        private fun alignPreferencesLeft(group: PreferenceGroup?) {
            group ?: return
            group.isIconSpaceReserved = false
            for (index in 0 until group.preferenceCount) {
                val preference = group.getPreference(index)
                preference.isIconSpaceReserved = false
                if (preference is PreferenceGroup) {
                    alignPreferencesLeft(preference)
                }
            }
        }

        private fun initDarkMode() {
            darkMode.summary = getSummary(
                ConfigPreferences.darkMode,
                R.array.dark_mode_entries,
                R.array.dark_mode_values
            )
            darkMode.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue.toString()
                darkMode.summary = getSummary(
                    value,
                    R.array.dark_mode_entries,
                    R.array.dark_mode_values
                )
                darkModeService.setDarkMode(DarkModeService.DarkMode.fromValue(value))
                true
            }
        }

        private fun initSoundEffect() {
            soundEffect.setOnPreferenceClickListener {
                startEqualizer()
                true
            }
        }

        private fun initThemeColor() {
            sanitizeThemeColorMode()
            updateThemeColorSummary()
            themeColorMode.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue.toString()
                if (value == "custom") {
                    showCustomThemePalette()
                } else {
                    applyThemeColor(value)
                }
                false
            }
        }

        private fun sanitizeThemeColorMode() {
            val validValues = resources.getStringArray(R.array.theme_color_values)
            if (ConfigPreferences.themeColorMode !in validValues) {
                ConfigPreferences.themeColorMode = "blue"
                preferenceManager.sharedPreferences
                    ?.edit()
                    ?.putString(getString(R.string.setting_key_theme_color_mode), "blue")
                    ?.commit()
            }
        }

        private fun showCustomThemePalette() {
            val preview = View(requireContext()).apply {
                background = buildColorPreviewDrawable(ConfigPreferences.customThemeColorHex)
            }
            val input = EditText(requireContext()).apply {
                setSingleLine(true)
                hint = "#00897B"
                setText(ConfigPreferences.customThemeColorHex.ifBlank { "#00897B" })
                filters = arrayOf(InputFilter.LengthFilter(7))
                setSelectAllOnFocus(true)
            }
            val content = LinearLayout(requireContext()).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(24), dp(12), dp(24), dp(4))
                addView(preview, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(44)
                ).apply {
                    bottomMargin = dp(12)
                })
                addView(input, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
            }
            input.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    val normalized = normalizeHexColor(s?.toString().orEmpty())
                    if (normalized != null) {
                        preview.background = buildColorPreviewDrawable(normalized)
                    }
                }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
            AlertDialog.Builder(requireContext())
                .setTitle("自定义调色盘")
                .setView(content)
                .setPositiveButton("确定") { _, _ ->
                    val hex = normalizeHexColor(input.text?.toString().orEmpty())
                    if (hex == null) {
                        toast("请输入 #RRGGBB 格式的颜色码")
                    } else {
                        applyThemeColor("custom", hex)
                    }
                }
                .setNegativeButton("取消", null)
                .show()
        }

        private fun dp(value: Int): Int {
            return (value * resources.displayMetrics.density).toInt()
        }

        private fun applyThemeColor(mode: String, customMode: String? = null) {
            ConfigPreferences.themeColorMode = mode
            if (customMode != null) {
                ConfigPreferences.customThemeColorHex = customMode
            }
            preferenceManager.sharedPreferences
                ?.edit()
                ?.putString(getString(R.string.setting_key_theme_color_mode), mode)
                ?.putString("custom_theme_color_hex", ConfigPreferences.customThemeColorHex)
                ?.commit()
            updateThemeColorSummary()
            requireActivity().recreate()
            toast("主题色已应用")
        }

        private fun updateThemeColorSummary() {
            themeColorMode.summary = if (ConfigPreferences.themeColorMode == "custom") {
                "自定义调色盘：${ConfigPreferences.customThemeColorHex.ifBlank { "#00897B" }}"
            } else {
                getSummary(
                    ConfigPreferences.themeColorMode,
                    R.array.theme_color_entries,
                    R.array.theme_color_values
                )
            }
        }

        private fun normalizeHexColor(value: String): String? {
            val text = value.trim()
            val normalized = if (text.startsWith("#")) text else "#$text"
            return normalized.uppercase()
                .takeIf { it.matches(Regex("#[0-9A-F]{6}")) }
        }

        private fun buildColorPreviewDrawable(hex: String): GradientDrawable {
            return GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(runCatching { Color.parseColor(hex) }.getOrDefault(Color.parseColor("#00897B")))
                setStroke(dp(1), requireContext().getColor(R.color.md_outline))
            }
        }

        private fun initFilter() {
            filterSize.summary = getSummary(
                ConfigPreferences.filterSize,
                R.array.filter_size_entries,
                R.array.filter_size_entry_values
            )
            filterSize.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue.toString()
                filterSize.summary = getSummary(
                    value,
                    R.array.filter_size_entries,
                    R.array.filter_size_entry_values
                )
                true
            }

            filterTime.summary = getSummary(
                ConfigPreferences.filterTime,
                R.array.filter_time_entries,
                R.array.filter_time_entry_values
            )
            filterTime.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue.toString()
                filterTime.summary = getSummary(
                    value,
                    R.array.filter_time_entries,
                    R.array.filter_time_entry_values
                )
                true
            }
        }

        private fun initCache() {
            updateCacheSummary()
            musicCacheLimit.setOnPreferenceChangeListener { _, newValue ->
                val value = newValue.toString()
                ConfigPreferences.musicCacheLimitMb = value
                musicCacheLimit.summary = buildCacheLimitSummary(value)
                true
            }
            clearMusicCache.setOnPreferenceClickListener {
                lifecycleScope.launch {
                    withContext(Dispatchers.IO) {
                        MusicCacheManager.clear()
                    }
                    updateCacheSummary()
                    toast("缓存已清理")
                }
                true
            }
        }

        private fun updateCacheSummary() {
            musicCacheLimit.summary = buildCacheLimitSummary(ConfigPreferences.musicCacheLimitMb)
            clearMusicCache.summary = "当前占用 ${MusicCacheManager.formatSize(MusicCacheManager.getCacheSizeBytes())}"
        }

        private fun buildCacheLimitSummary(value: String): String {
            val label = getSummary(
                value,
                R.array.music_cache_limit_entries,
                R.array.music_cache_limit_values
            )
            val size = MusicCacheManager.formatSize(MusicCacheManager.getCacheSizeBytes())
            return "$label，当前 $size"
        }

        private fun startEqualizer() {
            if (MusicUtils.isAudioControlPanelAvailable(requireContext())) {
                val intent = Intent()
                val packageName = requireContext().packageName
                intent.action = AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL
                intent.putExtra(AudioEffect.EXTRA_PACKAGE_NAME, packageName)
                intent.putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                intent.putExtra(
                    AudioEffect.EXTRA_AUDIO_SESSION,
                    playerController.getAudioSessionId()
                )
                try {
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    e.printStackTrace()
                    toast(R.string.device_not_support)
                }
            } else {
                toast(R.string.device_not_support)
            }
        }

        private fun getSummary(value: String, entries: Int, values: Int): String {
            val entryArray = resources.getStringArray(entries)
            val valueArray = resources.getStringArray(values)
            val index = valueArray.indexOf(value).coerceAtLeast(0)
            return entryArray[index]
        }
    }
}
