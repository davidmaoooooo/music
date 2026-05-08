package me.wcy.music.main

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import com.blankj.utilcode.util.AppUtils
import com.blankj.utilcode.util.ToastUtils
import me.wcy.music.R
import me.wcy.music.common.BaseMusicActivity
import top.wangchenyan.common.R as CommonR
import top.wangchenyan.common.widget.TitleLayout

class AboutActivity : BaseMusicActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)
        findViewById<TitleLayout>(CommonR.id.common_title_layout)?.setTitleText(getString(R.string.menu_about))
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, AboutFragment())
            .commit()
    }

    class AboutFragment : PreferenceFragmentCompat() {
        private var versionClickCount = 0

        private val version: Preference by lazy {
            findPreference("version")!!
        }
        private val ponyMusic: Preference by lazy {
            findPreference("pony_music")!!
        }
        private val neteaseApi: Preference by lazy {
            findPreference("netease_api")!!
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            addPreferencesFromResource(R.xml.preference_about)
            alignPreferencesLeft(preferenceScreen)
            version.summary = AppUtils.getAppVersionName()
            version.setOnPreferenceClickListener {
                versionClickCount++
                if (versionClickCount >= 5) {
                    versionClickCount = 0
                    startActivity(Intent(requireContext(), DebugSettingsActivity::class.java))
                } else {
                    ToastUtils.showShort("再点 ${5 - versionClickCount} 次进入调试设置")
                }
                true
            }
            ponyMusic.setOnPreferenceClickListener {
                openUrl(it.summary.toString())
                true
            }
            neteaseApi.setOnPreferenceClickListener {
                openUrl(it.summary.toString())
                true
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

        private fun openUrl(url: String) {
            val intent = Intent(Intent.ACTION_VIEW)
            intent.data = Uri.parse(url)
            startActivity(intent)
        }
    }
}
