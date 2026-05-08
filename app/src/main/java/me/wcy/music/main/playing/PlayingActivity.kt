package me.wcy.music.main.playing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.MediaItem
import com.blankj.utilcode.util.ConvertUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.wcy.lrcview.LrcView
import me.wcy.music.R
import me.wcy.music.account.service.UserService
import me.wcy.music.common.BaseMusicActivity
import me.wcy.music.common.bean.AlbumData
import me.wcy.music.common.bean.ArtistData
import me.wcy.music.common.bean.SongData
import me.wcy.music.common.dialog.songmenu.SimpleMenuItem
import me.wcy.music.common.dialog.songmenu.SongMoreMenuDialog
import me.wcy.music.common.dialog.songmenu.items.AlbumMenuItem
import me.wcy.music.common.dialog.songmenu.items.ArtistMenuItem
import me.wcy.music.common.dialog.songmenu.items.CollectMenuItem
import me.wcy.music.common.dialog.songmenu.items.CommentMenuItem
import me.wcy.music.common.dialog.songmenu.items.HeartModeMenuItem
import me.wcy.music.consts.RoutePath
import me.wcy.music.databinding.ActivityPlayingBinding
import me.wcy.music.discover.DiscoverApi
import me.wcy.music.ext.registerReceiverCompat
import me.wcy.music.listen.ListenTogetherDialog
import me.wcy.music.listen.ListenTogetherManager
import me.wcy.music.main.playlist.CurrentPlaylistFragment
import me.wcy.music.mine.MineApi
import me.wcy.music.service.PlayMode
import me.wcy.music.service.PlayState
import me.wcy.music.service.PlayerController
import me.wcy.music.service.likesong.LikeSongProcessor
import me.wcy.music.storage.preference.ConfigPreferences
import me.wcy.music.utils.getBaseCover
import me.wcy.music.storage.LrcCache
import me.wcy.music.utils.TimeUtils
import me.wcy.music.utils.getDuration
import me.wcy.music.utils.getLargeCover
import me.wcy.music.utils.getSongId
import me.wcy.music.utils.getSourcePlaylistId
import me.wcy.music.utils.isLocal
import me.wcy.music.utils.toSongEntity
import me.wcy.music.utils.toMediaItem
import me.wcy.router.CRouter
import me.wcy.router.annotation.Route
import top.wangchenyan.common.ext.getColor
import top.wangchenyan.common.ext.toast
import top.wangchenyan.common.ext.viewBindings
import top.wangchenyan.common.net.apiCall
import top.wangchenyan.common.utils.LaunchUtils
import top.wangchenyan.common.utils.image.ImageUtils
import java.io.File
import javax.inject.Inject
import kotlin.math.abs

/**
 * Created by wangchenyan.top on 2023/9/4.
 */
@Route(RoutePath.PLAYING)
@AndroidEntryPoint
class PlayingActivity : BaseMusicActivity() {
    private val viewBinding by viewBindings<ActivityPlayingBinding>()

    @Inject
    lateinit var playerController: PlayerController

    @Inject
    lateinit var likeSongProcessor: LikeSongProcessor

    @Inject
    lateinit var userService: UserService

    private val audioManager by lazy {
        getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private val defaultCoverBitmap by lazy {
        BitmapFactory.decodeResource(resources, R.drawable.bg_playing_default_cover)
    }

    private var loadLrcJob: Job? = null

    private var lastProgress = 0
    private var isDraggingProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBinding.root)

        initWindowInsets()
        ListenTogetherManager.bind(playerController)
        initTitle()
        initVolume()
        initCover()
        initLrc()
        initActions()
        initPlayControl()
        initData()
        switchCoverLrc(true)
    }

    private fun initWindowInsets() {
        configWindowInsets {
            fillNavBar = false
            fillDisplayCutout = false
            statusBarTextDarkStyle = true
            navBarButtonDarkStyle = true
            navBarColor = getColor(R.color.common_background_color)
        }
        applyPlayingSystemBars()

        val updateInsets = { insets: WindowInsetsCompat ->
            val result = insets.getInsets(
                WindowInsetsCompat.Type.statusBars()
                        or WindowInsetsCompat.Type.navigationBars()
                        or WindowInsetsCompat.Type.displayCutout()
            )
            viewBinding.llContent.updatePadding(
                left = result.left,
                top = result.top,
                right = result.right,
                bottom = result.bottom,
            )
        }
        val insets = ViewCompat.getRootWindowInsets(viewBinding.llContent)
        if (insets != null) {
            updateInsets(insets)
        }
        ViewCompat.setOnApplyWindowInsetsListener(viewBinding.llContent) { v, insets ->
            updateInsets(insets)
            insets
        }
    }

    private fun initTitle() {
        applyTitleBarColors()
        updateListenTogetherEntryVisibility()
        viewBinding.titleLayout.ivClose.setOnClickListener {
            onBackPressed()
        }
        viewBinding.titleLayout.ivListenTogether.setOnClickListener {
            if (ConfigPreferences.listenTogetherEnabled) {
                ListenTogetherDialog(this).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateListenTogetherEntryVisibility()
    }

    private fun updateListenTogetherEntryVisibility() {
        viewBinding.titleLayout.ivListenTogether.isVisible = ConfigPreferences.listenTogetherEnabled
    }

    private fun applyPlayingSystemBars() {
        val barColor = getColor(R.color.common_background_color)
        val useDarkIcons = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES
        window.statusBarColor = barColor
        window.navigationBarColor = barColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = useDarkIcons
            isAppearanceLightNavigationBars = useDarkIcons
        }
    }

    private fun applyTitleBarColors() {
        val iconColor = ColorStateList.valueOf(getColor(R.color.playing_on_surface))
        val subTextColor = getColor(R.color.playing_on_surface_variant)
        viewBinding.titleLayout.ivClose.imageTintList = iconColor
        viewBinding.titleLayout.ivListenTogether.imageTintList = iconColor
        viewBinding.titleLayout.tvTitle.setTextColor(iconColor)
        viewBinding.titleLayout.tvArtist.setTextColor(subTextColor)
    }

    private fun initVolume() {
        viewBinding.volumeLayout.sbVolume.max =
            audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        viewBinding.volumeLayout.sbVolume.progress =
            audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val filter = IntentFilter(VOLUME_CHANGED_ACTION)
        registerReceiverCompat(volumeReceiver, filter)
    }

    private fun initCover() {
        val playState = playerController.playState.value
        viewBinding.albumCoverView.initNeedle(playState.isPlaying)
        viewBinding.albumCoverView.setOnClickListener {
            switchCoverLrc(false)
        }
        setDefaultCover()
    }

    private fun initLrc() {
        viewBinding.lrcView.setDraggable(true) { view, time ->
            val playState = playerController.playState.value
            if (playState.isPlaying || playState.isPausing) {
                playerController.seekTo(time.toInt())
                if (playState.isPausing) {
                    playerController.playPause()
                }
                return@setDraggable true
            }
            return@setDraggable false
        }
        viewBinding.lrcView.setOnTapListener { view: LrcView?, x: Float, y: Float ->
            switchCoverLrc(true)
        }
    }

    private fun initActions() {
        viewBinding.controlLayout.ivLike.setOnClickListener {
            lifecycleScope.launch {
                val song = playerController.currentSong.value ?: return@launch
                val res = likeSongProcessor.like(this@PlayingActivity, song.getSongId())
                if (res.isSuccess()) {
                    updateOnlineActionsState(song)
                } else {
                    toast(res.msg)
                }
            }
        }
        viewBinding.controlLayout.ivDownload.setOnClickListener {
            lifecycleScope.launch {
                val song = playerController.currentSong.value ?: return@launch
                val res = apiCall {
                    DiscoverApi.get()
                        .getSongUrl(song.getSongId(), "standard")
                }
                if (res.isSuccessWithData() && res.getDataOrThrow().isNotEmpty()) {
                    val url = res.getDataOrThrow().first().url
                    LaunchUtils.launchBrowser(this@PlayingActivity, url)
                } else {
                    toast(res.msg)
                }
            }
        }
        viewBinding.controlLayout.ivComment.setOnClickListener {
            val song = playerController.currentSong.value ?: return@setOnClickListener
            showComments(song)
        }
        viewBinding.controlLayout.ivHeartMode.setOnClickListener {
            val song = playerController.currentSong.value ?: return@setOnClickListener
            startHeartMode(song)
        }
        viewBinding.controlLayout.ivMore.setOnClickListener {
            showCurrentSongMoreMenu()
        }
    }

    private fun showComments(song: MediaItem) {
        if (song.isLocal()) {
            toast("本地歌曲没有在线评论")
            return
        }
        CRouter.with(this)
            .url(RoutePath.COMMENT_LIST)
            .extra("id", song.getSongId())
            .extra("title", song.mediaMetadata.title?.toString().orEmpty())
            .start()
    }
    private fun startHeartMode(song: MediaItem) {
        if (song.isLocal()) {
            toast("本地歌曲不能使用心动模式")
            return
        }
        if (userService.isLogin().not()) {
            userService.checkLogin(this)
            return
        }
        lifecycleScope.launch {
            toast("正在获取心动模式")
            kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    song.getSourcePlaylistId().takeIf { it > 0 } ?: getLikePlaylistId()
                }
            }.onSuccess { playlistId ->
                playerController.startHeartMode(song, playlistId)
            }.onFailure {
                toast(it.message ?: "心动模式失败")
            }
        }
    }

    private fun showCurrentSongMoreMenu() {
        val song = playerController.currentSong.value ?: return
        if (song.isLocal()) {
            val entity = song.toSongEntity()
            SongMoreMenuDialog(this, entity)
                .setItems(
                    listOf(
                        SimpleMenuItem("文件名称: ${entity.fileName.ifEmpty { entity.title }}"),
                        SimpleMenuItem("播放时长: ${TimeUtils.formatMs(entity.duration)}"),
                        SimpleMenuItem(
                            "文件大小: ${ConvertUtils.byte2FitMemorySize(entity.fileSize)}"
                        ),
                        SimpleMenuItem("文件路径: ${entity.path.ifEmpty { "未知" }}")
                    )
                )
                .show()
            return
        }
        lifecycleScope.launch {
            showLoading()
            val result = kotlin.runCatching {
                withContext(Dispatchers.IO) {
                    val songData = loadSongData(song)
                    val playlistId = song.getSourcePlaylistId()
                        .takeIf { it > 0 }
                        ?: runCatching { getLikePlaylistId() }.getOrNull()
                    songData to playlistId
                }
            }
            dismissLoading()
            result.onSuccess { (songData, likePlaylistId) ->
                val items = mutableListOf(
                    CollectMenuItem(lifecycleScope, songData),
                    CommentMenuItem(songData),
                    ArtistMenuItem(songData),
                    AlbumMenuItem(songData)
                )
                if (likePlaylistId != null) {
                    items.add(
                        HeartModeMenuItem(
                            lifecycleScope,
                            songData,
                            likePlaylistId,
                            playerController
                        )
                    )
                }
                SongMoreMenuDialog(this@PlayingActivity, songData)
                    .setItems(items)
                    .show()
            }.onFailure {
                toast(it.message ?: "菜单加载失败")
            }
        }
    }

    private suspend fun getLikePlaylistId(): Long {
        val userId = userService.getUserId()
        val playlists = MineApi.get().getUserPlaylist(userId).playlists
        return playlists.firstOrNull { it.specialType == 5 }?.id
            ?: playlists.firstOrNull { it.userId == userId }?.id
            ?: throw IllegalStateException("没有找到可用于心动模式的歌单")
    }

    private suspend fun loadSongData(song: MediaItem): SongData {
        val id = song.getSongId()
        if (id > 0) {
            val detail = DiscoverApi.get().getSongDetail("""[{"id":$id}]""")
            detail.songs.firstOrNull()?.let { return it }
        }
        return song.toBasicSongData()
    }

    private fun MediaItem.toBasicSongData(): SongData {
        val artist = mediaMetadata.artist?.toString().orEmpty()
        return SongData(
            id = getSongId(),
            name = mediaMetadata.title?.toString().orEmpty(),
            ar = artist.split("/", "、")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { ArtistData(name = it) },
            al = AlbumData(
                name = mediaMetadata.albumTitle?.toString().orEmpty(),
                picUrl = mediaMetadata.getBaseCover().orEmpty()
            ),
            dt = mediaMetadata.getDuration()
        )
    }

    private fun initPlayControl() {
        lifecycleScope.launch {
            playerController.playMode.collectLatest { playMode ->
                viewBinding.controlLayout.ivMode.setImageLevel(playMode.value)
            }
        }

        viewBinding.controlLayout.ivMode.setOnClickListener {
            switchPlayMode()
        }
        viewBinding.controlLayout.flPlay.setOnClickListener {
            playerController.playPause()
        }
        viewBinding.controlLayout.ivPrev.setOnClickListener {
            playerController.prev()
        }
        viewBinding.controlLayout.ivNext.setOnClickListener {
            playerController.next()
        }
        viewBinding.controlLayout.ivPlaylist.setOnClickListener {
            CurrentPlaylistFragment.newInstance()
                .show(supportFragmentManager, CurrentPlaylistFragment.TAG)
        }
        viewBinding.controlLayout.sbProgress.setOnSeekBarChangeListener(object :
            OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (abs(progress - lastProgress) >= DateUtils.SECOND_IN_MILLIS) {
                    viewBinding.controlLayout.tvCurrentTime.text =
                        TimeUtils.formatMs(progress.toLong())
                    lastProgress = progress
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isDraggingProgress = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar ?: return
                isDraggingProgress = false
                val playState = playerController.playState.value
                if (playState.isPlaying || playState.isPausing) {
                    val progress = seekBar.progress
                    playerController.seekTo(progress)
                    if (viewBinding.lrcView.hasLrc()) {
                        viewBinding.lrcView.updateTime(progress.toLong())
                    }
                } else {
                    seekBar.progress = 0
                }
            }
        })
        viewBinding.volumeLayout.sbVolume.setOnSeekBarChangeListener(object :
            OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                seekBar ?: return
                audioManager.setStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    seekBar.progress,
                    AudioManager.FLAG_REMOVE_SOUND_AND_VIBRATE
                )
            }
        })
    }

    private fun initData() {
        val onSongUpdate: (MediaItem) -> Unit = { song ->
            viewBinding.controlLayout.tvTitle.text = song.mediaMetadata.title
            viewBinding.controlLayout.tvArtist.text = song.mediaMetadata.artist
            viewBinding.titleLayout.tvTitle.text = song.mediaMetadata.title
            viewBinding.titleLayout.tvArtist.text = song.mediaMetadata.artist
            viewBinding.controlLayout.sbProgress.max = song.mediaMetadata.getDuration().toInt()
            viewBinding.controlLayout.sbProgress.progress =
                playerController.playProgress.value.toInt()
            viewBinding.controlLayout.sbProgress.secondaryProgress = 0
            viewBinding.controlLayout.tvCurrentTime.text =
                TimeUtils.formatMs(playerController.playProgress.value)
            viewBinding.controlLayout.tvTotalTime.text =
                TimeUtils.formatMs(song.mediaMetadata.getDuration())
            updateCover(song)
            updateLrc(song)
            viewBinding.albumCoverView.reset()
            updatePlayState(playerController.playState.value)
            updateOnlineActionsState(song)
            if (ListenTogetherManager.state.value.currentTitle.isEmpty()) {
                ListenTogetherManager.onLocalSongChanged(song)
            }
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                playerController.currentSong.collectLatest { song ->
                    if (song != null) {
                        onSongUpdate(song)
                    } else {
                        finish()
                    }
                }
            }
        }

        lifecycleScope.launch {
            playerController.playState.collectLatest { playState ->
                updatePlayState(playState)
            }
        }

        lifecycleScope.launch {
            playerController.playProgress.collectLatest { progress ->
                if (isDraggingProgress.not()) {
                    viewBinding.controlLayout.sbProgress.progress = progress.toInt()
                }
                if (viewBinding.lrcView.hasLrc()) {
                    viewBinding.lrcView.updateTime(progress)
                }
            }
        }

        lifecycleScope.launch {
            playerController.bufferingPercent.collectLatest { percent ->
                viewBinding.controlLayout.sbProgress.secondaryProgress =
                    viewBinding.controlLayout.sbProgress.max * percent / 100
            }
        }

        lifecycleScope.launch {
            ListenTogetherManager.state.collectLatest { state ->
                val active = ConfigPreferences.listenTogetherEnabled && state.roomCode.isNotEmpty()
                viewBinding.tvListenTogetherStatus.isVisible = active
                viewBinding.tvListenTogetherStatus.text = if (active) {
                    val peer = state.peerName.ifEmpty { "等待对方" }
                    val role = if (state.role == me.wcy.music.listen.ListenTogetherRole.Owner) "房主 · " else ""
                    "${role}房间 ${state.roomCode} · $peer"
                } else {
                    ""
                }
                viewBinding.titleLayout.ivListenTogether.isSelected = active
            }
        }
    }

    private fun updateCover(song: MediaItem) {
        setDefaultCover()
        ImageUtils.loadBitmap(song.getLargeCover()) {
            if (it.isSuccessWithData()) {
                val bitmap = it.getDataOrThrow()
                viewBinding.albumCoverView.setCoverBitmap(bitmap)
            }
        }
    }

    private fun setDefaultCover() {
        viewBinding.albumCoverView.setCoverBitmap(defaultCoverBitmap)
    }

    private fun updateLrc(song: MediaItem) {
        loadLrcJob?.cancel()
        loadLrcJob = null
        val lrcPath = LrcCache.getLrcFilePath(song)
        viewBinding.lrcView.loadLrc("")
        if (song.isLocal()) {
            if (lrcPath?.isNotEmpty() == true) {
                loadLrc(lrcPath)
            } else {
                setLrcLabel("暂无歌词")
            }
        } else {
            val translatedLrcPath = LrcCache.getTranslatedLrcFilePath(song)
            if (lrcPath?.isNotEmpty() == true) {
                loadLrc(lrcPath, translatedLrcPath)
                if (translatedLrcPath?.isNotEmpty() == true) {
                    return
                }
            } else {
                setLrcLabel("歌词加载中...")
            }
            loadLrcJob = lifecycleScope.launch {
                kotlin.runCatching {
                    val lrcWrap = DiscoverApi.get().getLrc(song.getSongId())
                    if (lrcWrap.code == 200 && lrcWrap.lrc.isValid()) {
                        val file = LrcCache.saveLrcFile(song, lrcWrap.lrc.lyric)
                        val translatedFile = LrcCache.saveTranslatedLrcFile(song, lrcWrap.tlyric.lyric)
                        file to translatedFile
                    } else {
                        throw IllegalStateException("lrc is invalid")
                    }
                }.onSuccess {
                    loadLrc(it.first.path, it.second.path)
                }.onFailure {
                    Log.e(TAG, "load lrc error", it)
                    if (lrcPath.isNullOrEmpty()) {
                        setLrcLabel("歌词加载失败")
                    }
                }
            }
        }
    }

    private fun loadLrc(path: String) {
        val file = File(path)
        viewBinding.lrcView.loadLrc(file)
    }

    private fun loadLrc(path: String, translatedPath: String?) {
        val file = File(path)
        val translatedFile = translatedPath?.let(::File)
        if (translatedFile?.exists() == true) {
            viewBinding.lrcView.loadLrc(file, translatedFile)
        } else {
            viewBinding.lrcView.loadLrc(file)
        }
    }

    private fun setLrcLabel(label: String) {
        viewBinding.lrcView.setLabel(label)
    }

    private fun switchCoverLrc(showCover: Boolean) {
        if (resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE) {
            viewBinding.albumCoverView.isVisible = showCover
            viewBinding.lrcLayout.isVisible = showCover.not()
        }
    }

    private fun switchPlayMode() {
        val mode = when (playerController.playMode.value) {
            PlayMode.Loop -> PlayMode.Shuffle
            PlayMode.Shuffle -> PlayMode.Single
            PlayMode.Single -> PlayMode.Loop
        }
        toast(mode.nameRes)
        playerController.setPlayMode(mode)
    }

    private fun updatePlayState(playState: PlayState) {
        when (playState) {
            PlayState.Preparing -> {
                viewBinding.controlLayout.flPlay.isEnabled = false
                viewBinding.controlLayout.ivPlay.isSelected = false
                viewBinding.controlLayout.loadingProgress.isVisible = true
                viewBinding.albumCoverView.pause()
            }

            PlayState.Playing -> {
                viewBinding.controlLayout.flPlay.isEnabled = true
                viewBinding.controlLayout.ivPlay.isSelected = true
                viewBinding.controlLayout.loadingProgress.isVisible = false
                viewBinding.albumCoverView.start()
            }

            else -> {
                viewBinding.controlLayout.flPlay.isEnabled = true
                viewBinding.controlLayout.ivPlay.isSelected = false
                viewBinding.controlLayout.loadingProgress.isVisible = false
                viewBinding.albumCoverView.pause()
            }
        }
    }

    private fun updateOnlineActionsState(song: MediaItem) {
        viewBinding.controlLayout.llActions.isVisible = song.isLocal().not()
        viewBinding.controlLayout.ivLike.isSelected = likeSongProcessor.isLiked(song.getSongId())
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(volumeReceiver)
        defaultCoverBitmap.recycle()
    }

    private val volumeReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            viewBinding.volumeLayout.sbVolume.progress =
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        }
    }

    companion object {
        private const val TAG = "PlayingActivity"
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
    }
}

