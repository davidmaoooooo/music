package me.wcy.music.common.dialog.songmenu.items

import android.view.View
import androidx.appcompat.app.AlertDialog
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.dialog.songmenu.MenuItem
import me.wcy.music.consts.RoutePath
import me.wcy.music.utils.getSimpleArtist
import me.wcy.router.CRouter

class ArtistMenuItem(private val songData: SongData) : MenuItem {
    override val name: String
        get() = "歌手: ${songData.getSimpleArtist()}"

    override fun onClick(view: View) {
        val artists = songData.ar.filter { it.id > 0 && it.name.isNotEmpty() }
        if (artists.isEmpty()) return
        if (artists.size == 1) {
            openArtist(view, artists.first().id, artists.first().name)
            return
        }
        AlertDialog.Builder(view.context)
            .setTitle("选择歌手")
            .setItems(artists.map { it.name }.toTypedArray()) { _, which ->
                val artist = artists[which]
                openArtist(view, artist.id, artist.name)
            }
            .show()
    }

    private fun openArtist(view: View, id: Long, name: String) {
        CRouter.with(view.context)
            .url(RoutePath.ARTIST_DETAIL)
            .extra("id", id)
            .extra("name", name)
            .start()
    }
}
