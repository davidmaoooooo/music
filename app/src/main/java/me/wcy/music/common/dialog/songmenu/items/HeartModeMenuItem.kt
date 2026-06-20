package me.wcy.music.common.dialog.songmenu.items

import android.view.View
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.dialog.songmenu.MenuItem
import me.wcy.music.consts.RoutePath
import me.wcy.music.service.PlayerController
import me.wcy.music.utils.toMediaItem
import me.wcy.router.CRouter
import top.wangchenyan.common.ext.toast

class HeartModeMenuItem(
    private val scope: CoroutineScope,
    private val songData: SongData,
    private val playlistId: Long,
    private val playerController: PlayerController
) : MenuItem {
    override val name: String
        get() = "心动模式"

    override fun onClick(view: View) {
        scope.launch {
            if (playlistId <= 0) {
                toast("没有找到可用于心动模式的歌单")
                return@launch
            }
            playerController.startHeartMode(songData.toMediaItem(playlistId), playlistId)
            CRouter.with(view.context).url(RoutePath.PLAYING).start()
        }
    }
}
