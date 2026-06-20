package me.wcy.music.search.artist

import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.wcy.music.common.OnItemClickListener
import me.wcy.music.common.SimpleMusicRefreshFragment
import me.wcy.music.consts.Consts
import me.wcy.music.consts.RoutePath
import me.wcy.music.search.SearchApi
import me.wcy.music.search.SearchViewModel
import me.wcy.music.search.bean.SearchArtistData
import me.wcy.radapter3.RAdapter
import me.wcy.router.CRouter
import top.wangchenyan.common.model.CommonResult
import top.wangchenyan.common.net.apiCall

@AndroidEntryPoint
class SearchArtistFragment : SimpleMusicRefreshFragment<SearchArtistData>() {
    private val viewModel by activityViewModels<SearchViewModel>()
    private val itemBinder by lazy {
        SearchArtistItemBinder(object : OnItemClickListener<SearchArtistData> {
            override fun onItemClick(item: SearchArtistData, position: Int) {
                CRouter.with(requireActivity())
                    .url(RoutePath.ARTIST_DETAIL)
                    .extra("id", item.id)
                    .extra("name", item.name)
                    .start()
            }
        }).apply {
            keywords = viewModel.keywords.value
        }
    }

    override fun isShowTitle(): Boolean = false

    override fun isRefreshEnabled(): Boolean = false

    override fun onLazyCreate() {
        super.onLazyCreate()
        lifecycleScope.launch {
            viewModel.keywords.collectLatest {
                if (it.isNotEmpty()) {
                    showLoadSirLoading()
                    itemBinder.keywords = it
                    autoRefresh(true)
                }
            }
        }
    }

    override fun initAdapter(adapter: RAdapter<SearchArtistData>) {
        adapter.register(itemBinder)
    }

    override suspend fun getData(page: Int): CommonResult<List<SearchArtistData>> {
        val keywords = viewModel.keywords.value
        if (keywords.isEmpty()) {
            return CommonResult.success(emptyList())
        }
        val res = apiCall {
            SearchApi.get().search(100, keywords, Consts.PAGE_COUNT, (page - 1) * Consts.PAGE_COUNT)
        }
        return if (res.isSuccessWithData()) {
            CommonResult.success(res.getDataOrThrow().artists)
        } else {
            CommonResult.fail(res.code, res.msg)
        }
    }
}
