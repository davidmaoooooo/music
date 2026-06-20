package me.wcy.music.search.artist

import android.annotation.SuppressLint
import com.blankj.utilcode.util.SizeUtils
import me.wcy.music.common.bean.AlbumData
import me.wcy.music.databinding.ItemSearchPlaylistBinding
import me.wcy.music.utils.ImageUtils.loadCover
import me.wcy.radapter3.RItemBinder

class ArtistAlbumItemBinder(private val onItemClick: (AlbumData) -> Unit) :
    RItemBinder<ItemSearchPlaylistBinding, AlbumData>() {

    @SuppressLint("SetTextI18n")
    override fun onBind(viewBinding: ItemSearchPlaylistBinding, item: AlbumData, position: Int) {
        viewBinding.root.setOnClickListener {
            onItemClick(item)
        }
        viewBinding.ivCover.loadCover(item.getSmallCover(), SizeUtils.dp2px(4f))
        viewBinding.tvTitle.text = item.name
        viewBinding.tvSubTitle.text = "专辑"
    }
}
