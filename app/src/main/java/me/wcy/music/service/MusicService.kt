package me.wcy.music.service

import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.HeartRating
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Rating
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.CacheBitmapLoader
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.blankj.utilcode.util.ActivityUtils
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import me.wcy.music.R
import me.wcy.music.net.datasource.MusicDataSource
import me.wcy.music.service.likesong.LikeSongProcessor
import me.wcy.music.service.likesong.LikeSongProcessorModule.Companion.audioPlayer
import me.wcy.music.utils.MusicUtils
import me.wcy.music.utils.getSongId
import me.wcy.music.utils.isLiked
import me.wcy.music.utils.parseSongIdFromMediaId
import me.wcy.music.utils.setLiked
import top.wangchenyan.common.CommonApp

/**
 * Created by wangchenyan.top on 2024/3/26.
 */
class MusicService : MediaSessionService() {
    private lateinit var player: Player
    private lateinit var session: MediaSession

    private var sessionCallback: SourceSessionCallback? = null
    private var likeStateJob: Job? = null

    /** 通过 Hilt EntryPoint 获取，用于同步「喜欢」状态。 */
    private val likeSongProcessor: LikeSongProcessor?
        get() = runCatching { application.audioPlayer() }.getOrNull()

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val basePlayer = ExoPlayer.Builder(applicationContext)
            // 自动处理音频焦点
            .setAudioAttributes(AudioAttributes.DEFAULT, true)
            // 自动暂停播放
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(applicationContext)
                    .setDataSourceFactory(MusicDataSource.Factory(applicationContext))
            )
            .build()

        player = NotificationPlayer(basePlayer)
        basePlayer.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 切歌后把喜欢状态同步给系统控制中心
                sessionCallback?.refreshLikeButton()
            }
        })

        val callback = SourceSessionCallback()
        sessionCallback = callback
        session = MediaSession.Builder(this, player)
            .setCallback(callback)
            .setBitmapLoader(CacheBitmapLoader(DataSourceBitmapLoader(applicationContext)))
            .setSessionActivity(
                PendingIntent.getActivity(
                    this,
                    0,
                    MusicUtils.getStartPlayingPageIntent(this),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(applicationContext).build().apply {
                setSmallIcon(R.drawable.ic_notification)
            }
        )

        // App 内点赞后，把新的喜欢状态同步给系统控制中心
        likeStateJob = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            likeSongProcessor?.likeStateVersion?.collect {
                callback.refreshLikeButton()
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return session
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        player.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        likeStateJob?.cancel()
        player.release()
        session.release()
    }

    companion object {
        val EXTRA_NOTIFICATION = "${CommonApp.app.packageName}.notification"

        /**
         * 「设置评分」命令，对应 onSetRating 回调。
         *
         * 必须用预定义命令码构造：自定义命令（action 字符串）不会路由到 onSetRating。
         */
        private val COMMAND_SET_RATING =
            SessionCommand(SessionCommand.COMMAND_CODE_SESSION_SET_RATING)
    }

    /**
     * 为系统控制中心（vivo 原子随身听 / 原子岛等）提供「喜欢」按钮。
     *
     * 按钮状态通过 MediaItem 的 HeartRating 同步，系统据此显示当前歌曲是否已喜欢。
     */
    @OptIn(UnstableApi::class)
    private inner class SourceSessionCallback : MediaSession.Callback {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

        /** 更新 metadata 会触发切歌回调，用它避免重入。 */
        private var isRefreshingLike = false

        @OptIn(UnstableApi::class)
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val result = super.onConnect(session, controller)
            // SET_RATING 已包含在默认会话命令中，这里只需暴露喜欢按钮
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(result.availableSessionCommands)
                .setAvailablePlayerCommands(result.availablePlayerCommands)
                .setCustomLayout(listOf(likeButton()))
                .build()
        }

        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onPostConnect(session, controller)
            refreshLikeButton()
        }

        /**
         * 系统控制中心（原子随身听等）点击「喜欢」按钮时回调。
         *
         * 这是 MediaSession 的标准喜欢入口：rating 为 [HeartRating]，
         * 其中 isHeart 表示用户希望把当前歌曲设为喜欢还是取消喜欢。
         */
        @OptIn(UnstableApi::class)
        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaId: String,
            rating: Rating
        ): ListenableFuture<SessionResult> {
            val heart = rating as? HeartRating
                ?: return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
            val songId = parseSongIdFromMediaId(mediaId)
            if (songId <= 0L) {
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_BAD_VALUE))
            }
            val processor = likeSongProcessor
            if (processor == null || processor.isLiked(songId) == heart.isHeart) {
                // 状态已经一致，直接返回成功
                refreshLikeButton()
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
            }
            val activity = ActivityUtils.getTopActivity()
            if (activity == null) {
                // 没有前台界面时无法完成登录校验
                return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_INVALID_STATE))
            }
            scope.launch {
                val res = processor.like(activity, songId)
                if (res.isSuccess()) {
                    refreshLikeButton()
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        /**
         * 不带 mediaId 的重载，作用于当前播放项。
         *
         * media3 原生控制器（MediaController#setRating）走这条路径；
         * 系统控制中心则走带 mediaId 的重载。
         */
        @OptIn(UnstableApi::class)
        override fun onSetRating(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            rating: Rating
        ): ListenableFuture<SessionResult> {
            return onSetRating(
                session,
                controller,
                player.currentMediaItem?.mediaId.orEmpty(),
                rating
            )
        }

        /** 喜欢按钮：绑定到 onSetRating，系统据此显示当前歌曲的喜欢状态。 */
        @OptIn(UnstableApi::class)
        private fun likeButton(): CommandButton {
            return CommandButton.Builder()
                .setDisplayName("喜欢")
                .setIconResId(R.drawable.ic_favorite_selector)
                .setSessionCommand(COMMAND_SET_RATING)
                .build()
        }

        /** 把当前歌曲的喜欢状态写入 MediaItem，系统控制中心据此显示按钮状态。 */
        fun refreshLikeButton() {
            if (isRefreshingLike) return
            val mediaItem = player.currentMediaItem ?: return
            val liked = likeSongProcessor?.isLiked(mediaItem.getSongId()) ?: false
            if (mediaItem.mediaMetadata.isLiked() == liked) return
            val updated = mediaItem.buildUpon()
                .setMediaMetadata(
                    mediaItem.mediaMetadata.buildUpon()
                        .setLiked(liked)
                        .build()
                )
                .build()
            val index = player.currentMediaItemIndex
            if (index < 0) return
            isRefreshingLike = true
            try {
                player.replaceMediaItem(index, updated)
            } finally {
                isRefreshingLike = false
            }
        }
    }

    /**
     * 播放器包装。
     *
     * 只统一「上一首」的语义（始终切上一首，不重启当前歌曲），
     * 索引交给 ExoPlayer 计算，从而遵循随机播放的打乱顺序。
     *
     * 此前用 currentMediaItemIndex - 1 直接取原始下标，绕过了随机顺序，
     * 导致随机播放时上一首与打乱结果不一致。
     */
    private class NotificationPlayer(player: Player) : ForwardingPlayer(player) {
        override fun seekToPrevious() {
            seekToPreviousMediaItem()
        }
    }
}
