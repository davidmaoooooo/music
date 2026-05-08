package me.wcy.music.common

import android.os.Bundle
import com.kingja.loadsir.callback.Callback
import me.wcy.music.widget.loadsir.SoundWaveLoadingCallback
import top.wangchenyan.common.ui.activity.BaseActivity

/**
 * Created by wangchenyan.top on 2023/9/4.
 */
abstract class BaseMusicActivity : BaseActivity() {
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

    override fun getLoadingCallback(): Callback {
        return SoundWaveLoadingCallback()
    }

    override fun showLoadSirLoading() {
        loadService?.showCallback(SoundWaveLoadingCallback::class.java)
    }

    private fun applyThemeColor() {
        appliedThemeOverlayRes = ThemeColor.themeOverlayRes()
        theme.applyStyle(appliedThemeOverlayRes, true)
    }

    companion object {
        private const val TAG = "BaseMusicActivity"
    }
}
