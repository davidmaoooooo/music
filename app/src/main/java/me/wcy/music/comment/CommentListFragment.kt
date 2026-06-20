package me.wcy.music.comment

import android.view.View
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import me.wcy.music.R
import me.wcy.music.common.BaseMusicFragment
import me.wcy.music.common.bean.CommentData
import me.wcy.music.consts.RoutePath
import me.wcy.music.databinding.FragmentCommentListBinding
import me.wcy.music.discover.DiscoverApi
import me.wcy.radapter3.RAdapter
import me.wcy.router.annotation.Route
import top.wangchenyan.common.ext.viewBindings

@Route(RoutePath.COMMENT_LIST)
@AndroidEntryPoint
class CommentListFragment : BaseMusicFragment() {
    private companion object {
        private const val PAGE_SIZE = 30
    }

    private val viewBinding by viewBindings<FragmentCommentListBinding>()
    private val adapter by lazy { RAdapter<CommentData>() }
    private val comments = mutableListOf<CommentData>()
    private var songId = 0L
    private var loadingMore = false
    private var hasMore = true

    override fun getRootView(): View {
        return viewBinding.root
    }

    override fun isUseLoadSir(): Boolean {
        return true
    }

    override fun getLoadSirTarget(): View {
        return viewBinding.content
    }

    override fun onReload() {
        super.onReload()
        loadData(false)
    }

    override fun onLazyCreate() {
        super.onLazyCreate()
        songId = getRouteArguments().getLongExtra("id", 0)
        val title = getRouteArguments().getStringExtra("title").orEmpty()
        getTitleLayout()?.setTitleText(if (title.isEmpty()) "评论" else "评论: $title")
        if (songId <= 0) {
            finish()
            return
        }

        adapter.register(CommentItemBinder())
        viewBinding.recyclerView.adapter = adapter
        viewBinding.recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadingMore || hasMore.not()) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                if (layoutManager.findLastVisibleItemPosition() >= adapter.getDataList().size - 5) {
                    loadData(true)
                }
            }
        })
        loadData(false)
    }

    private fun loadData(loadMore: Boolean) {
        if (loadingMore) return
        loadingMore = true
        lifecycleScope.launch {
            if (loadMore.not()) showLoadSirLoading()
            kotlin.runCatching {
                DiscoverApi.get().getMusicComments(
                    songId,
                    limit = PAGE_SIZE,
                    offset = if (loadMore) comments.size else 0
                )
            }.onSuccess { data ->
                hasMore = data.more
                if (loadMore.not()) comments.clear()
                if (loadMore.not() && data.hotComments.isNotEmpty()) {
                    comments.addAll(data.hotComments)
                }
                comments.addAll(data.comments)
                if (comments.isEmpty()) {
                    showLoadSirEmpty()
                } else {
                    showLoadSirSuccess()
                    adapter.refresh(comments.distinctBy { it.user.userId to it.content })
                }
            }.onFailure {
                if (loadMore.not()) {
                    showLoadSirError(it.message ?: "评论加载失败")
                }
            }.also {
                loadingMore = false
            }
        }
    }
}
