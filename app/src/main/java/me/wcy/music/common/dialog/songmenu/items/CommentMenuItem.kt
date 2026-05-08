package me.wcy.music.common.dialog.songmenu.items

import android.view.View
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.dialog.songmenu.MenuItem
import me.wcy.music.consts.RoutePath
import me.wcy.router.CRouter

class CommentMenuItem(private val songData: SongData) : MenuItem {
    override val name: String
        get() = "评论"

    override fun onClick(view: View) {
        CRouter.with(view.context)
            .url(RoutePath.COMMENT_LIST)
            .extra("id", songData.id)
            .extra("title", songData.name)
            .start()
    }
}
