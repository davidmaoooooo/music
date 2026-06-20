package me.wcy.music.search.album

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import me.wcy.music.R
import me.wcy.music.common.BaseMusicFragment
import me.wcy.music.common.OnItemClickListener2
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.dialog.songmenu.SongMoreMenuDialog
import me.wcy.music.common.dialog.songmenu.items.AlbumMenuItem
import me.wcy.music.common.dialog.songmenu.items.ArtistMenuItem
import me.wcy.music.common.dialog.songmenu.items.CollectMenuItem
import me.wcy.music.common.dialog.songmenu.items.CommentMenuItem
import me.wcy.music.consts.RoutePath
import me.wcy.music.discover.DiscoverApi
import me.wcy.music.discover.recommend.song.item.RecommendSongItemBinder
import me.wcy.music.service.PlayerController
import me.wcy.music.utils.toMediaItem
import me.wcy.radapter3.RAdapter
import me.wcy.router.CRouter
import me.wcy.router.annotation.Route
import top.wangchenyan.common.R as CommonR
import top.wangchenyan.common.ext.getColor
import top.wangchenyan.common.widget.TitleLayout
import javax.inject.Inject

@Route(RoutePath.ALBUM_SONGS)
@AndroidEntryPoint
class AlbumSongsFragment : BaseMusicFragment() {
    private var rootView: View? = null
    private lateinit var contentView: LinearLayout
    private lateinit var recyclerView: RecyclerView
    private lateinit var titleLayout: TitleLayout
    private lateinit var artistTabs: LinearLayout
    private lateinit var tvSongsTab: TextView
    private lateinit var tvAlbumsTab: TextView
    private lateinit var tvPlayAll: TextView
    private val adapter by lazy { RAdapter<SongData>() }
    private var artistId = 0L
    private var artistName = ""

    @Inject
    lateinit var playerController: PlayerController

    override fun getRootView(): View {
        rootView?.let { return it }
        return layoutInflater.inflate(R.layout.fragment_artist_songs, null).also { root ->
            rootView = root
            contentView = root.findViewById(R.id.content)
            recyclerView = root.findViewById(R.id.recyclerView)
            titleLayout = root.findViewById(CommonR.id.common_title_layout)
            artistTabs = root.findViewById(R.id.artistTabs)
            tvSongsTab = root.findViewById(R.id.tvSongsTab)
            tvAlbumsTab = root.findViewById(R.id.tvAlbumsTab)
            tvPlayAll = root.findViewById(R.id.tvPlayAll)
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
        loadData()
    }

    override fun onLazyCreate() {
        super.onLazyCreate()

        val albumId = getRouteArguments().getLongExtra("id", 0)
        val albumName = getRouteArguments().getStringExtra("name").orEmpty()
        artistId = getRouteArguments().getLongExtra("artist_id", 0)
        artistName = getRouteArguments().getStringExtra("artist_name").orEmpty()
        if (albumId <= 0) {
            finish()
            return
        }

        configWindowInsets {
            navBarColor = getColor(R.color.play_bar_bg)
        }

        titleLayout.setTitleText(albumName.ifEmpty { "专辑歌曲" })
        initArtistTabs()
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
        recyclerView.adapter = adapter
        tvPlayAll.setOnClickListener {
            val songList = adapter.getDataList().map { it.toMediaItem() }
            if (songList.isNotEmpty()) {
                playerController.replaceAll(songList, songList.first())
                CRouter.with(requireContext()).url(RoutePath.PLAYING).start()
            }
        }

        loadData()
    }

    private fun initArtistTabs() {
        artistTabs.isVisible = artistId > 0
        if (artistId <= 0) return
        tvSongsTab.setBackgroundResource(R.drawable.bg_artist_tab_unselected)
        tvAlbumsTab.setBackgroundResource(R.drawable.bg_artist_tab_selected)
        tvSongsTab.setTextColor(getColor(R.color.common_text_h2_color))
        tvAlbumsTab.setTextColor(getColor(android.R.color.white))
        tvSongsTab.setOnClickListener {
            CRouter.with(requireContext())
                .url(RoutePath.ARTIST_DETAIL)
                .extra("id", artistId)
                .extra("name", artistName)
                .extra("page", "songs")
                .start()
        }
        tvAlbumsTab.setOnClickListener {
            CRouter.with(requireContext())
                .url(RoutePath.ARTIST_DETAIL)
                .extra("id", artistId)
                .extra("name", artistName)
                .extra("page", "albums")
                .start()
        }
    }

    private fun loadData() {
        val albumId = getRouteArguments().getLongExtra("id", 0)
        lifecycleScope.launch {
            showLoadSirLoading()
            kotlin.runCatching {
                DiscoverApi.get().getAlbumSongs(albumId).songs.filter { it.id > 0 }
            }.onSuccess { songs ->
                if (songs.isNotEmpty()) {
                    showLoadSirSuccess()
                    adapter.refresh(songs)
                    songs.firstOrNull()?.al?.name?.takeIf { it.isNotEmpty() }?.let {
                        titleLayout.setTitleText(it)
                    }
                } else {
                    showLoadSirEmpty()
                }
            }.onFailure {
                showLoadSirError(it.message ?: "获取专辑歌曲失败")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        rootView = null
    }
}
