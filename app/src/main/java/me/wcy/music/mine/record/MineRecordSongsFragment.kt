package me.wcy.music.mine.record

import android.view.View
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import me.wcy.music.common.BaseMusicFragment
import me.wcy.music.common.OnItemClickListener2
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.dialog.songmenu.SongMoreMenuDialog
import me.wcy.music.common.dialog.songmenu.items.AlbumMenuItem
import me.wcy.music.common.dialog.songmenu.items.ArtistMenuItem
import me.wcy.music.common.dialog.songmenu.items.CollectMenuItem
import me.wcy.music.common.dialog.songmenu.items.CommentMenuItem
import me.wcy.music.consts.RoutePath
import me.wcy.music.databinding.FragmentMineRecordSongsBinding
import me.wcy.music.discover.playlist.detail.item.PlaylistSongItemBinder
import me.wcy.music.mine.MineApi
import me.wcy.music.account.service.UserService
import me.wcy.music.mine.bean.MineVirtualPlaylist
import me.wcy.music.service.PlayerController
import me.wcy.music.utils.toMediaItem
import me.wcy.radapter3.RAdapter
import me.wcy.router.CRouter
import me.wcy.router.annotation.Route
import top.wangchenyan.common.ext.toast
import top.wangchenyan.common.ext.viewBindings
import javax.inject.Inject

@Route(RoutePath.MINE_RECORD_SONGS, needLogin = true)
@AndroidEntryPoint
class MineRecordSongsFragment : BaseMusicFragment() {
    private val viewBinding by viewBindings<FragmentMineRecordSongsBinding>()
    private val adapter = RAdapter<SongData>()
    private var type = ""
    private var title = ""
    private var songs: List<SongData> = emptyList()

    @Inject
    lateinit var playerController: PlayerController

    @Inject
    lateinit var userService: UserService

    override fun getRootView(): View {
        return viewBinding.root
    }

    override fun onLazyCreate() {
        super.onLazyCreate()
        type = getRouteArguments().getStringExtra("type").orEmpty()
        title = getRouteArguments().getStringExtra("title").orEmpty().ifBlank {
            if (type == MineVirtualPlaylist.TYPE_LISTENING_RANK) "近期听歌排行" else "最近播放"
        }
        getTitleLayout()?.setTitleText(title)
        adapter.register(PlaylistSongItemBinder(object : OnItemClickListener2<SongData> {
            override fun onItemClick(item: SongData, position: Int) {
                val mediaItems = songs.map { it.toMediaItem() }
                mediaItems.getOrNull(position)?.let { playerController.replaceAll(mediaItems, it) }
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
        viewBinding.recyclerView.adapter = adapter
        viewBinding.llPlayAll.setOnClickListener {
            val mediaItems = songs.map { it.toMediaItem() }
            if (mediaItems.isNotEmpty()) {
                playerController.replaceAll(mediaItems, mediaItems.first())
                CRouter.with(requireContext()).url(RoutePath.PLAYING).start()
            }
        }
        loadData()
    }

    private fun loadData() {
        lifecycleScope.launch {
            val cached = MineRecordStore.songsOf(type)
            if (cached.isNotEmpty()) {
                updateSongs(cached)
                return@launch
            }
            val loaded = runCatching {
                when (type) {
                    MineVirtualPlaylist.TYPE_LISTENING_RANK -> {
                        val uid = userService.getUserId()
                        MineApi.get().getUserRecord(uid, 1).weekData.map { it.song }
                    }
                    else -> MineApi.get().getRecentSongs(100).data.list.map { it.song }
                }.filter { it.id > 0 }.distinctBy { it.id }
            }.getOrElse {
                toast(it.message ?: "加载失败")
                emptyList()
            }
            if (type == MineVirtualPlaylist.TYPE_LISTENING_RANK) {
                MineRecordStore.listeningRanks = loaded.map { me.wcy.music.mine.bean.UserRecordItemData(song = it) }
            } else {
                MineRecordStore.recentSongs = loaded
            }
            updateSongs(loaded)
        }
    }

    private fun updateSongs(list: List<SongData>) {
        songs = list
        viewBinding.tvSongCount.text = "${list.size}首"
        adapter.refresh(list)
    }
}
