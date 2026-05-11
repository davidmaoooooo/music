package me.wcy.music.mine.home

import com.blankj.utilcode.util.SizeUtils
import me.wcy.music.databinding.ItemUserPlaylistBinding
import me.wcy.music.mine.bean.MineVirtualPlaylist
import me.wcy.music.utils.ImageUtils.loadCover
import me.wcy.radapter3.RItemBinder

class MineVirtualPlaylistItemBinder(
    private val listener: OnItemClickListener
) : RItemBinder<ItemUserPlaylistBinding, MineVirtualPlaylist>() {

    override fun onBind(
        viewBinding: ItemUserPlaylistBinding,
        item: MineVirtualPlaylist,
        position: Int
    ) {
        viewBinding.root.setOnClickListener {
            listener.onItemClick(item)
        }
        viewBinding.ivCover.loadCover(item.coverImgUrl, SizeUtils.dp2px(4f))
        viewBinding.tvName.text = item.name
        viewBinding.tvCount.text = "${item.trackCount}首"
        viewBinding.ivMore.visibility = android.view.View.GONE
    }

    interface OnItemClickListener {
        fun onItemClick(item: MineVirtualPlaylist)
    }
}
