package me.wcy.music.discover.playlist.detail.item

import android.content.res.Configuration
import androidx.core.view.isVisible
import me.wcy.music.common.OnItemClickListener2
import me.wcy.music.common.bean.SongData
import me.wcy.music.databinding.ItemPlaylistSongBinding
import me.wcy.music.utils.getSimpleArtist
import me.wcy.radapter3.RItemBinder

/**
 * Created by wangchenyan.top on 2023/9/22.
 */
class PlaylistSongItemBinder(private val listener: OnItemClickListener2<SongData>) :
    RItemBinder<ItemPlaylistSongBinding, SongData>() {
    var currentSongId: Long = 0

    override fun onBind(viewBinding: ItemPlaylistSongBinding, item: SongData, position: Int) {
        viewBinding.root.setOnClickListener {
            listener.onItemClick(item, position)
        }
        viewBinding.ivMore.setOnClickListener {
            listener.onMoreClick(item, position)
        }
        val isCurrent = item.id == currentSongId && currentSongId > 0
        viewBinding.tvIndex.isVisible = isCurrent.not()
        viewBinding.tvPlayingFlag.isVisible = isCurrent
        viewBinding.tvPlayingFlag.setTextColor(playingFlagColor(viewBinding.root.context.resources.configuration))
        viewBinding.tvIndex.text = (position + 1).toString()
        viewBinding.tvTitle.text = item.name
        viewBinding.tvSubTitle.text = buildString {
            append(item.getSimpleArtist())
            append(" - ")
            append(item.al.name)
            item.originSongSimpleData?.let { originSong ->
                append(" | 原唱: ")
                append(originSong.artists.joinToString("/") { it.name })
            }
        }
    }

    private fun playingFlagColor(configuration: Configuration): Int {
        val night = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        return if (night == Configuration.UI_MODE_NIGHT_YES) {
            0xFFFFFFFF.toInt()
        } else {
            0xFF9E9E9E.toInt()
        }
    }
}
