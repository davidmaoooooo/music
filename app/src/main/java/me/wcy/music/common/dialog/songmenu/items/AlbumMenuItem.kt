package me.wcy.music.common.dialog.songmenu.items

import android.view.View
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.dialog.songmenu.MenuItem
import me.wcy.music.consts.RoutePath
import me.wcy.router.CRouter

class AlbumMenuItem(private val songData: SongData) : MenuItem {
    override val name: String
        get() = "专辑: ${songData.al.name}"

    override fun onClick(view: View) {
        if (songData.al.id <= 0) return
        CRouter.with(view.context)
            .url(RoutePath.ALBUM_SONGS)
            .extra("id", songData.al.id)
            .extra("name", songData.al.name)
            .start()
    }
}
