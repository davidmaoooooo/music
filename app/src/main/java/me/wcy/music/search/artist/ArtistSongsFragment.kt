package me.wcy.music.search.artist

import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import me.wcy.music.R
import me.wcy.music.common.BaseMusicFragment
import me.wcy.music.common.OnItemClickListener2
import me.wcy.music.common.bean.AlbumData
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.dialog.songmenu.SongMoreMenuDialog
import me.wcy.music.common.dialog.songmenu.items.AlbumMenuItem
import me.wcy.music.common.dialog.songmenu.items.ArtistMenuItem
import me.wcy.music.common.dialog.songmenu.items.CollectMenuItem
import me.wcy.music.common.dialog.songmenu.items.CommentMenuItem
import me.wcy.music.consts.RoutePath
import me.wcy.music.discover.DiscoverApi
import me.wcy.music.discover.recommend.song.item.RecommendSongItemBinder
import me.wcy.music.search.SearchApi
import me.wcy.music.service.PlayerController
import me.wcy.music.utils.getSimpleArtist
import me.wcy.music.utils.toMediaItem
import me.wcy.radapter3.RAdapter
import me.wcy.router.CRouter
import me.wcy.router.annotation.Route
import top.wangchenyan.common.net.apiCall
import top.wangchenyan.common.R as CommonR
import top.wangchenyan.common.ext.getColor
import top.wangchenyan.common.widget.TitleLayout
import javax.inject.Inject

@Route(RoutePath.ARTIST_DETAIL)
@AndroidEntryPoint
class ArtistSongsFragment : BaseMusicFragment() {
    private companion object {
        private const val PAGE_SIZE = 50
    }

    private var rootView: View? = null
    private lateinit var contentView: LinearLayout
    private lateinit var songRecyclerView: RecyclerView
    private lateinit var albumRecyclerView: RecyclerView
    private lateinit var titleLayout: TitleLayout
    private lateinit var songToolBar: LinearLayout
    private lateinit var tvPlayAll: TextView
    private lateinit var etSongSearch: EditText
    private lateinit var tvSongsTab: TextView
    private lateinit var tvAlbumsTab: TextView
    private val adapter by lazy { RAdapter<SongData>() }
    private val albumAdapter by lazy { RAdapter<AlbumData>() }
    private val songs = mutableListOf<SongData>()
    private var visibleSongs: List<SongData> = emptyList()
    private val albums = mutableListOf<AlbumData>()
    private var artistId = 0L
    private var loadingSongs = false
    private var hasMoreSongs = false
    private var loadingAlbums = false
    private var hasMoreAlbums = false
    private var selectedPage = Page.Songs
    private var artistName = ""
    private var songSearchJob: kotlinx.coroutines.Job? = null

    @Inject
    lateinit var playerController: PlayerController

    override fun getRootView(): View {
        rootView?.let { return it }
        return layoutInflater.inflate(R.layout.fragment_artist_detail, null).also { root ->
            rootView = root
            contentView = root.findViewById(R.id.content)
            songRecyclerView = root.findViewById(R.id.songRecyclerView)
            albumRecyclerView = root.findViewById(R.id.albumRecyclerView)
            titleLayout = root.findViewById(CommonR.id.common_title_layout)
            songToolBar = root.findViewById(R.id.songToolBar)
            tvPlayAll = root.findViewById(R.id.tvPlayAll)
            etSongSearch = root.findViewById(R.id.etSongSearch)
            tvSongsTab = root.findViewById(R.id.tvSongsTab)
            tvAlbumsTab = root.findViewById(R.id.tvAlbumsTab)
        }
    }

    override fun isUseLoadSir(): Boolean {
        return true
    }

    override fun getLoadSirTarget(): View {
        return contentView
    }

    override fun onReload() {
        super.onReload()
        if (selectedPage == Page.Songs) {
            loadData()
        } else {
            loadAlbums()
        }
    }

    override fun onLazyCreate() {
        super.onLazyCreate()

        artistId = getRouteArguments().getLongExtra("id", 0)
        artistName = getRouteArguments().getStringExtra("name").orEmpty()
        selectedPage = if (getRouteArguments().getStringExtra("page") == "albums") {
            Page.Albums
        } else {
            Page.Songs
        }
        if (artistId <= 0) {
            finish()
            return
        }

        configWindowInsets {
            navBarColor = getColor(R.color.play_bar_bg)
        }

        titleLayout.setTitleText(artistName.ifEmpty { "歌手歌曲" })
        tvSongsTab.setOnClickListener {
            showPage(Page.Songs)
        }
        tvAlbumsTab.setOnClickListener {
            showPage(Page.Albums)
        }
        adapter.register(RecommendSongItemBinder(object : OnItemClickListener2<SongData> {
            override fun onItemClick(item: SongData, position: Int) {
                val songList = adapter.getDataList().map { it.toMediaItem() }
                if (songList.isNotEmpty()) {
                    playerController.replaceAll(songList, songList[position])
                }
            }

            override fun onMoreClick(item: SongData, position: Int) {
                SongMoreMenuDialog(requireActivity(), item)
                    .setItems(
                        listOf(
                            CollectMenuItem(lifecycleScope, item),
                            CommentMenuItem(item),
                            ArtistMenuItem(item),
                            AlbumMenuItem(item)
                        )
                    )
                    .show()
            }
        }))
        songRecyclerView.adapter = adapter
        visibleSongs = songs
        songRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadingSongs || hasMoreSongs.not()) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= adapter.getDataList().size - 6) {
                    loadData(loadMore = true)
                }
            }
        })
        albumAdapter.register(ArtistAlbumItemBinder { album ->
            CRouter.with(requireContext())
                .url(RoutePath.ALBUM_SONGS)
                .extra("id", album.id)
                .extra("name", album.name)
                .extra("artist_id", artistId)
                .extra("artist_name", artistName)
                .start()
        })
        albumRecyclerView.adapter = albumAdapter
        albumRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy <= 0 || loadingAlbums || hasMoreAlbums.not()) return
                val layoutManager = recyclerView.layoutManager as? LinearLayoutManager ?: return
                val lastVisible = layoutManager.findLastVisibleItemPosition()
                if (lastVisible >= albumAdapter.getDataList().size - 6) {
                    loadAlbums(loadMore = true)
                }
            }
        })
        tvPlayAll.setOnClickListener {
            val songList = visibleSongs.map { it.toMediaItem() }
            if (songList.isNotEmpty()) {
                playerController.replaceAll(songList, songList.first())
                CRouter.with(requireContext()).url(RoutePath.PLAYING).start()
            }
        }
        etSongSearch.doAfterTextChanged {
            searchArtistSongs(it?.toString()?.trim().orEmpty())
        }

        showPage(selectedPage)
        loadData(loadMore = false)
    }

    private fun showPage(page: Page) {
        selectedPage = page
        val showSongs = page == Page.Songs
        songToolBar.isVisible = showSongs
        songRecyclerView.isVisible = showSongs
        albumRecyclerView.isVisible = showSongs.not()
        updateTabs(showSongs)
        if (page == Page.Albums && albums.isEmpty() && loadingAlbums.not()) {
            loadAlbums(loadMore = false)
        }
    }

    private fun loadData(loadMore: Boolean = false) {
        if (loadingSongs) return
        loadingSongs = true
        lifecycleScope.launch {
            if (loadMore.not()) showLoadSirLoading()
            kotlin.runCatching {
                DiscoverApi.get().getArtistSongPage(
                    artistId,
                    limit = PAGE_SIZE,
                    offset = if (loadMore) songs.size else 0
                )
            }.onSuccess { page ->
                val pageSongs = page.songs.ifEmpty { page.hotSongs }.filter { it.id > 0 }
                hasMoreSongs = page.more
                if (loadMore.not()) songs.clear()
                songs.addAll(pageSongs)
                if (selectedPage == Page.Songs) {
                    if (songs.isNotEmpty()) {
                        showLoadSirSuccess()
                        refreshVisibleSongs()
                    } else {
                        showLoadSirEmpty()
                    }
                }
            }.onFailure {
                if (loadMore.not() && selectedPage == Page.Songs) {
                    showLoadSirError(it.message ?: "获取歌手歌曲失败")
                }
            }.also {
                loadingSongs = false
            }
        }
    }

    private fun loadAlbums(loadMore: Boolean = false) {
        if (loadingAlbums) return
        loadingAlbums = true
        lifecycleScope.launch {
            if (loadMore.not() && selectedPage == Page.Albums) showLoadSirLoading()
            kotlin.runCatching {
                DiscoverApi.get().getArtistAlbums(
                    artistId,
                    limit = PAGE_SIZE,
                    offset = if (loadMore) albums.size else 0
                )
            }.onSuccess { page ->
                val pageAlbums = page.hotAlbums.filter { it.id > 0 }
                hasMoreAlbums = page.more
                if (loadMore.not()) albums.clear()
                albums.addAll(pageAlbums)
                if (selectedPage == Page.Albums) {
                    if (albums.isNotEmpty()) {
                        showLoadSirSuccess()
                        albumAdapter.refresh(albums)
                    } else {
                        showLoadSirEmpty()
                    }
                } else {
                    albumAdapter.refresh(albums)
                }
            }.onFailure {
                if (loadMore.not() && selectedPage == Page.Albums) {
                    showLoadSirError(it.message ?: "获取歌手专辑失败")
                }
            }.also {
                loadingAlbums = false
            }
        }
    }

    private fun searchArtistSongs(keyword: String) {
        songSearchJob?.cancel()
        if (keyword.isEmpty()) {
            refreshVisibleSongs()
            return
        }
        val currentArtistName = artistName
        songSearchJob = lifecycleScope.launch {
            kotlinx.coroutines.delay(300)
            val res = apiCall {
                SearchApi.get().search(1, "$currentArtistName $keyword", PAGE_SIZE, 0)
            }
            val apiSongs = if (res.isSuccessWithData()) {
                res.getDataOrThrow().songs.filter { song ->
                    song.ar.any { it.id == artistId } ||
                        song.getSimpleArtist().contains(currentArtistName, ignoreCase = true)
                }
            } else {
                emptyList()
            }
            visibleSongs = apiSongs.ifEmpty {
                songs.filter {
                    it.name.contains(keyword, ignoreCase = true) ||
                        it.getSimpleArtist().contains(keyword, ignoreCase = true) ||
                        it.al.name.contains(keyword, ignoreCase = true)
                }
            }
            adapter.refresh(visibleSongs)
            if (selectedPage == Page.Songs && visibleSongs.isEmpty()) {
                showLoadSirEmpty()
            } else if (selectedPage == Page.Songs) {
                showLoadSirSuccess()
            }
        }
    }

    private fun refreshVisibleSongs() {
        val keyword = etSongSearch.text?.toString()?.trim().orEmpty()
        visibleSongs = if (keyword.isEmpty()) {
            songs
        } else {
            songs.filter {
                it.name.contains(keyword, ignoreCase = true) ||
                    it.getSimpleArtist().contains(keyword, ignoreCase = true) ||
                    it.al.name.contains(keyword, ignoreCase = true)
            }
        }
        adapter.refresh(visibleSongs)
        if (selectedPage == Page.Songs && songs.isNotEmpty() && visibleSongs.isEmpty()) {
            showLoadSirEmpty()
        } else if (selectedPage == Page.Songs && visibleSongs.isNotEmpty()) {
            showLoadSirSuccess()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rootView = null
    }

    private fun updateTabs(showSongs: Boolean) {
        tvSongsTab.setBackgroundResource(
            if (showSongs) R.drawable.bg_artist_tab_selected else R.drawable.bg_artist_tab_unselected
        )
        tvAlbumsTab.setBackgroundResource(
            if (showSongs) R.drawable.bg_artist_tab_unselected else R.drawable.bg_artist_tab_selected
        )
        tvSongsTab.setTextColor(getColor(if (showSongs) android.R.color.white else R.color.common_text_h2_color))
        tvAlbumsTab.setTextColor(getColor(if (showSongs) R.color.common_text_h2_color else android.R.color.white))
    }

    private enum class Page {
        Songs,
        Albums
    }
}
