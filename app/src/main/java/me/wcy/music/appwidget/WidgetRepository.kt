package me.wcy.music.appwidget

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import androidx.datastore.preferences.preferencesDataStore
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import me.wcy.music.R
import me.wcy.music.service.PlayServiceModule.playerController
import me.wcy.music.service.PlayState
import me.wcy.music.service.PlayerController
import me.wcy.music.utils.BitmapUtils.blur
import me.wcy.music.utils.toSongEntity
import top.wangchenyan.common.CommonApp
import top.wangchenyan.common.utils.image.ImageUtils

/**
 * Created by wangchenyan.top on 2025/9/30.
 */
object WidgetRepository : CoroutineScope by MainScope() {
    val Context.widgetStore by preferencesDataStore("MusicAppWidget")
    private lateinit var playerController: PlayerController
    private lateinit var state: WidgetState

    private val _coverBitmapFlow: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val coverBitmapFlow: StateFlow<Bitmap?> = _coverBitmapFlow

    private val _bgBitmapFlow: MutableStateFlow<Bitmap?> = MutableStateFlow(null)
    val bgBitmapFlow: StateFlow<Bitmap?> = _bgBitmapFlow

    private var loadCoverJob: Job? = null

    /** 小组件存在性检查的短期缓存。 */
    private var widgetPresent = false
    private var widgetCheckedAt = 0L

    fun init(application: Application) {
        if (::playerController.isInitialized) {
            return
        }
        playerController = application.playerController()
        state = WidgetState()
            .copy(application, playerController.currentSong.value)
            .copy(playerController.playState.value)
        launch {
            playerController.currentSong.collectLatest {
                val newState = state.copy(application, it)
                if (state != newState) {
                    state = newState
                    loadCoverJob?.cancel()
                    _coverBitmapFlow.value = null
                    _bgBitmapFlow.value = null
                    MusicAppWidget().updateState(application, state)
                    // 桌面没有小组件时不必加载封面并做高斯模糊
                    loadCoverJob = launch { loadCoverInternal() }
                }
            }
        }
        launch {
            playerController.playState.collectLatest {
                val newState = state.copy(it)
                if (state != newState) {
                    state = newState
                    MusicAppWidget().updateState(application, state)
                }
            }
        }
        // 小组件可能在本类初始化之前就已添加到桌面，存在时补加载一次封面
        launch {
            if (hasWidget(application)) {
                loadCoverInternal()
            }
        }
    }

    /**
     * 小组件被添加到桌面时调用：此时不会触发切歌回调，
     * 需要主动加载一次当前歌曲的封面。
     */
    fun refreshCover() {
        // WidgetRepository 由 MediaController 就绪后异步初始化，
        // 小组件可能先被添加，此处需防止访问未初始化的 state
        if (::playerController.isInitialized.not() || ::state.isInitialized.not()) return
        // 由小组件添加事件触发，直接标记为存在，避免存在性缓存造成误判
        widgetPresent = true
        widgetCheckedAt = System.currentTimeMillis()
        loadCoverJob?.cancel()
        loadCoverJob = launch { loadCoverInternal() }
    }

    /** 加载当前歌曲封面并做高斯模糊；桌面无小组件时直接跳过。 */
    private suspend fun loadCoverInternal() {
        if (::state.isInitialized.not()) return
        val context = CommonApp.app
        if (!hasWidget(context)) return
        if (state.album.isBlank()) return
        val result = ImageUtils.loadBitmap(state.album)
        if (result.isSuccessWithData()) {
            val bitmap = result.getDataOrThrow()
            val bgBitmap = bitmap.blur(context)
            _coverBitmapFlow.value = bitmap
            _bgBitmapFlow.value = bgBitmap
            MusicAppWidget().updateState(context, state)
        }
    }

    /** 桌面是否存在本应用的小组件实例（结果短期缓存，避免每次切歌都查一次）。 */
    private suspend fun hasWidget(context: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - widgetCheckedAt < WIDGET_CHECK_INTERVAL_MS) return widgetPresent
        // 查询失败时按「存在」处理，保证封面仍会加载，避免功能退化
        val present = runCatching {
            GlanceAppWidgetManager(context).getGlanceIds(MusicAppWidget::class.java).isNotEmpty()
        }.getOrDefault(true)
        widgetPresent = present
        widgetCheckedAt = now
        return present
    }

    fun getMediaController(): MediaController? {
        if (::playerController.isInitialized.not()) {
            return null
        }
        return playerController.mediaController
    }

    private const val WIDGET_CHECK_INTERVAL_MS = 10_000L

    private fun WidgetState.copy(context: Context, mediaItem: MediaItem?): WidgetState {
        val song = mediaItem?.toSongEntity()
        return copy(
            title = song?.title.orEmpty().ifEmpty { context.getString(R.string.no_playing_song) },
            artist = song?.artist.orEmpty(),
            album = song?.getSmallCover().orEmpty()
        )
    }

    private fun WidgetState.copy(playState: PlayState): WidgetState {
        return copy(
            isPlaying = (playState == PlayState.Playing)
        )
    }
}