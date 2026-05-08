package me.wcy.music.common

import android.os.Bundle
import dagger.hilt.android.AndroidEntryPoint
import top.wangchenyan.common.ui.activity.FragmentContainerActivity

/**
 * Created by wangchenyan.top on 2023/8/7.
 */
@AndroidEntryPoint
class MusicFragmentContainerActivity : FragmentContainerActivity() {
    private var appliedThemeOverlayRes = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        applyThemeColor()
        super.onCreate(savedInstanceState)
        applyThemeColor()
    }

    override fun onResume() {
        super.onResume()
        if (appliedThemeOverlayRes != 0 && appliedThemeOverlayRes != ThemeColor.themeOverlayRes()) {
            recreate()
        }
    }

    private fun applyThemeColor() {
        appliedThemeOverlayRes = ThemeColor.themeOverlayRes()
        theme.applyStyle(appliedThemeOverlayRes, true)
    }
}
