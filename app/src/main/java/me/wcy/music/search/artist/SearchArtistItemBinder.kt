package me.wcy.music.search.artist

import android.annotation.SuppressLint
import com.blankj.utilcode.util.SizeUtils
import me.wcy.music.common.OnItemClickListener
import me.wcy.music.databinding.ItemSearchArtistBinding
import me.wcy.music.search.bean.SearchArtistData
import me.wcy.music.utils.ImageUtils.loadCover
import me.wcy.music.utils.MusicUtils
import me.wcy.radapter3.RItemBinder
import top.wangchenyan.common.ext.context

class SearchArtistItemBinder(
    private val listener: OnItemClickListener<SearchArtistData>
) : RItemBinder<ItemSearchArtistBinding, SearchArtistData>() {
    var keywords = ""

    @SuppressLint("SetTextI18n")
    override fun onBind(viewBinding: ItemSearchArtistBinding, item: SearchArtistData, position: Int) {
        viewBinding.root.setOnClickListener {
            listener.onItemClick(item, position)
        }
        viewBinding.ivCover.loadCover(item.getCover(), SizeUtils.dp2px(24f))
        viewBinding.tvTitle.text = MusicUtils.keywordsTint(viewBinding.context, item.name, keywords)
        val alias = item.alias.joinToString("/")
        val summary = listOf(
            "${item.musicSize} 首歌",
            "${item.albumSize} 张专辑",
            "${item.mvSize} 个 MV"
        ).joinToString(" · ")
        viewBinding.tvSubTitle.text = if (alias.isEmpty()) summary else "$alias · $summary"
    }
}
