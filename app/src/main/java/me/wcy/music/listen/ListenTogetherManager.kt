package me.wcy.music.listen

import android.net.Uri
import androidx.appcompat.app.AlertDialog
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import com.blankj.utilcode.util.ActivityUtils
import com.blankj.utilcode.util.GsonUtils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import me.wcy.music.account.AccountPreference
import me.wcy.music.net.HttpClient
import me.wcy.music.net.datasource.OnlineMusicUriFetcher
import me.wcy.music.service.PlayerController
import me.wcy.music.storage.preference.ConfigPreferences
import me.wcy.music.utils.getBaseCover
import me.wcy.music.utils.getDuration
import me.wcy.music.utils.getSongId
import me.wcy.music.utils.setBaseCover
import me.wcy.music.utils.setDuration
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import top.wangchenyan.common.CommonApp
import top.wangchenyan.common.ext.toUnMutable
import top.wangchenyan.common.utils.ToastUtils
import java.net.URLEncoder
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.abs

object ListenTogetherManager : CoroutineScope by MainScope() {
    private const val TYPE_SERVER_READY = "server_ready"
    private const val TYPE_PEER_JOINED = "peer_joined"
    private const val TYPE_PEER_LEFT = "peer_left"
    private const val TYPE_LEAVE = "leave"
    private const val TYPE_ERROR = "error"
    private const val TYPE_PING = "ping"
    private const val TYPE_PONG = "pong"
    private const val TYPE_EVENT_ACK = "event_ack"

    private const val TYPE_OWNER_COOKIE = "owner_cookie"
    private const val TYPE_PLAYLIST = "playlist"
    private const val TYPE_CURRENT_SONG = "current_song"
    private const val TYPE_PLAY_STATE = "play_state"
    private const val TYPE_SEEK = "seek"

    private const val HEARTBEAT_INTERVAL_MS = 10_000L
    private const val HEARTBEAT_TIMEOUT_MS = 30_000L
    private const val PAUSE_AUTO_LEAVE_MS = 30 * 60 * 1000L
    private const val APPLY_REMOTE_GUARD_MS = 1_500L
    private const val SEEK_DRIFT_MS = 1_000L
    private const val SERVER_READY_TIMEOUT_MS = 5_000L
    private const val RECONNECT_TOTAL_TIMEOUT_MS = 15_000L

    private val reconnectDelaysMs = longArrayOf(0L, 2_000L, 2_000L, 10_000L)
    private val clientId = UUID.randomUUID().toString()
    private val socketClient: OkHttpClient by lazy {
        HttpClient.okHttpClient.newBuilder()
            .pingInterval(25, TimeUnit.SECONDS)
            .build()
    }

    private var playerController: PlayerController? = null
    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private var reconnectJob: Job? = null
    private var serverReadyTimeoutJob: Job? = null
    private var pauseAutoLeaveJob: Job? = null
    private var remoteGuardJob: Job? = null

    private var applyingRemote = false
    private var manualLeaving = false
    private var failureDialogShowing = false
    private var acceptedOnce = false
    private var reconnectAttempts = 0
    private var reconnectStartedAt = 0L
    private var localVersion = 0L
    private var lastRemoteServerVersion = 0L
    private var lastPongAt = 0L
    private var lastRoomCode = ""
    private var lastWorkerUrl = ""
    private var lastMode = ""
    private var currentConnectionIsReconnect = false
    private var pendingRemotePlaying: Boolean? = null
    private var pendingRemotePositionMs: Long = -1L

    @Volatile
    private var temporaryOwnerCookie = ""

    private val _state = MutableStateFlow(ListenTogetherUiState())
    val state: StateFlow<ListenTogetherUiState> = _state.toUnMutable()

    val isActive: Boolean
        get() = ConfigPreferences.listenTogetherEnabled && lastRoomCode.isNotEmpty()

    fun bind(controller: PlayerController) {
        if (playerController === controller) return
        playerController = controller
    }

    fun createRoom(roomCode: String, workerUrl: String = ConfigPreferences.resolvedListenTogetherWorkerUrl) {
        if (!ConfigPreferences.listenTogetherEnabled) {
            ToastUtils.show("请先在调试设置中开启一起听功能")
            return
        }
        ListenTogetherDebugLogger.log("create_room_requested", mapOf(
            "roomCode" to roomCode,
            "workerUrl" to workerUrl,
            "isActiveBefore" to isActive
        ))
        connect(roomCode, workerUrl, "create", resetFirst = true)
    }

    fun joinRoom(roomCode: String, workerUrl: String = ConfigPreferences.resolvedListenTogetherWorkerUrl) {
        if (!ConfigPreferences.listenTogetherEnabled) {
            ToastUtils.show("请先在调试设置中开启一起听功能")
            return
        }
        ListenTogetherDebugLogger.log("join_room_requested", mapOf(
            "roomCode" to roomCode,
            "workerUrl" to workerUrl,
            "isActiveBefore" to isActive
        ))
        connect(roomCode, workerUrl, "join", resetFirst = true)
    }

    fun leave(notifyPeer: Boolean = true) {
        ListenTogetherDebugLogger.log("leave_requested", mapOf(
            "notifyPeer" to notifyPeer,
            "roomCode" to lastRoomCode,
            "mode" to lastMode,
            "role" to _state.value.role.name,
            "serverAccepted" to _state.value.serverAccepted,
            "connected" to _state.value.connected,
            "manualLeavingBefore" to manualLeaving
        ))
        manualLeaving = true
        if (notifyPeer) sendSimple(TYPE_LEAVE)
        clearActiveSession()
        _state.value = ListenTogetherUiState()
        lastRoomCode = ""
        lastWorkerUrl = ""
        lastMode = ""
    }

    fun getTemporaryOwnerCookie(url: HttpUrl): String? {
        if (!ConfigPreferences.listenTogetherEnabled) return null
        val cookie = temporaryOwnerCookie.takeIf { it.isNotEmpty() } ?: return null
        val isSongUrlRequest = url.encodedPath.contains("/api/song/enhance/player/url")
        return cookie.takeIf { isSongUrlRequest && url.host.endsWith("music.163.com") }
    }

    fun onLocalPlaylistChanged() {
        if (!ConfigPreferences.listenTogetherEnabled) return
        sendPlaylistEvent()
    }

    fun onLocalSongChanged(song: MediaItem?) {
        if (!ConfigPreferences.listenTogetherEnabled) return
        if (song == null) return
        sendCurrentSongEvent(song)
    }

    fun onManualSongChanged(song: MediaItem?) {
        if (!ConfigPreferences.listenTogetherEnabled) return
        if (song == null) return
        sendCurrentSongEvent(song)
    }

    fun onLocalSeek(positionMs: Long) {
        if (!ConfigPreferences.listenTogetherEnabled) return
        sendSeekEvent(positionMs)
    }

    fun onLocalPlayStateChanged(isPlaying: Boolean) {
        if (!ConfigPreferences.listenTogetherEnabled) return
        updatePauseAutoLeave(isPlaying)
        sendPlayStateEvent(isPlaying)
    }

    private fun connect(
        roomCode: String,
        workerUrl: String,
        mode: String,
        resetFirst: Boolean,
        cancelReconnectJob: Boolean = true
    ) {
        if (!ConfigPreferences.listenTogetherEnabled) {
            ListenTogetherDebugLogger.log("connect_rejected", mapOf("reason" to "feature_disabled"))
            ToastUtils.show("请先在调试设置中开启一起听功能")
            return
        }
        val code = roomCode.trim()
        val normalizedWorkerUrl = workerUrl.normalizeWorkerUrl()
        if (code.isEmpty()) {
            ListenTogetherDebugLogger.log("connect_rejected", mapOf("reason" to "empty_room_code"))
            ToastUtils.show("请输入房间号")
            return
        }
        if (!code.matches(Regex("\\d+"))) {
            ListenTogetherDebugLogger.log("connect_rejected", mapOf(
                "reason" to "non_numeric_room_code",
                "roomCode" to code
            ))
            ToastUtils.show("房间号只能使用阿拉伯数字")
            return
        }
        if (normalizedWorkerUrl.isEmpty()) {
            ListenTogetherDebugLogger.log("connect_rejected", mapOf(
                "reason" to "invalid_worker_url",
                "workerUrl" to workerUrl
            ))
            ToastUtils.show("一起听服务器地址格式不正确")
            return
        }

        if (resetFirst) {
            leave(notifyPeer = false)
            reconnectAttempts = 0
            reconnectStartedAt = 0L
        } else {
            cancelConnectionJobs(closeSocket = true, cancelReconnectJob = cancelReconnectJob)
        }

        manualLeaving = false
        lastRoomCode = code
        lastWorkerUrl = normalizedWorkerUrl
        lastMode = mode
        currentConnectionIsReconnect = !resetFirst && acceptedOnce
        ListenTogetherDebugLogger.log("connect_start", mapOf(
            "roomCode" to code,
            "mode" to mode,
            "reconnectConnection" to currentConnectionIsReconnect,
            "resetFirst" to resetFirst,
            "cancelReconnectJob" to cancelReconnectJob,
            "workerUrl" to workerUrl,
            "normalizedWorkerUrl" to normalizedWorkerUrl,
            "clientId" to clientId,
            "acceptedOnce" to acceptedOnce,
            "reconnectAttempts" to reconnectAttempts
        ))

        val requestUrl = normalizedWorkerUrl.withQuery(
            "room" to code,
            "clientId" to clientId,
            "deviceName" to displayName(),
            "mode" to mode
        )
        val request = Request.Builder().url(requestUrl).build()
        _state.value = _state.value.copy(
            roomCode = code,
            role = if (mode == "create") ListenTogetherRole.Owner else ListenTogetherRole.Guest,
            connected = false,
            serverAccepted = false,
            startedAt = _state.value.startedAt.takeIf { it > 0 } ?: System.currentTimeMillis(),
            statusText = if (mode == "create") "正在创建一起听房间" else "正在加入一起听房间",
            communicationText = "正在连接服务器"
        )
        webSocket = socketClient.newWebSocket(request, socketListener)
        startServerReadyTimeout()
    }

    private val socketListener = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            launch(Dispatchers.Main.immediate) {
                if (this@ListenTogetherManager.webSocket !== webSocket) return@launch
                ListenTogetherDebugLogger.log("websocket_open", mapOf(
                    "roomCode" to lastRoomCode,
                    "mode" to lastMode,
                    "responseCode" to response.code,
                    "responseMessage" to response.message
                ))
                _state.value = _state.value.copy(
                    connected = true,
                    communicationText = "WebSocket 已连接，等待服务器确认"
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            launch(Dispatchers.Main.immediate) {
                if (this@ListenTogetherManager.webSocket !== webSocket) return@launch
                logIncomingMessage(text)
                handleMessage(text)
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            launch(Dispatchers.Main.immediate) {
                if (this@ListenTogetherManager.webSocket !== webSocket) return@launch
                ListenTogetherDebugLogger.log("websocket_failure", mapOf(
                    "roomCode" to lastRoomCode,
                    "mode" to lastMode,
                    "manualLeaving" to manualLeaving,
                    "message" to (t.message ?: ""),
                    "throwable" to t.javaClass.name,
                    "responseCode" to response?.code
                ))
                if (!manualLeaving && lastRoomCode.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        connected = false,
                        serverAccepted = false,
                        communicationText = "连接断开：${t.message ?: "未知错误"}",
                        statusText = "一起听连接恢复中"
                    )
                    scheduleReconnect("failure")
                } else {
                    ToastUtils.show(t.message ?: "一起听连接失败")
                    leave(notifyPeer = false)
                }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            launch(Dispatchers.Main.immediate) {
                if (this@ListenTogetherManager.webSocket !== webSocket) return@launch
                ListenTogetherDebugLogger.log("websocket_closed", mapOf(
                    "roomCode" to lastRoomCode,
                    "mode" to lastMode,
                    "code" to code,
                    "reason" to reason,
                    "manualLeaving" to manualLeaving
                ))
                if (!manualLeaving && lastRoomCode.isNotEmpty()) {
                    _state.value = _state.value.copy(
                        connected = false,
                        serverAccepted = false,
                        communicationText = "连接已关闭：$reason",
                        statusText = "一起听连接恢复中"
                    )
                    scheduleReconnect("closed")
                }
            }
        }
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull() ?: return
        when (json["type"]?.asString) {
            TYPE_SERVER_READY -> handleServerReady(json)
            TYPE_EVENT_ACK -> handleEventAck(json)
            TYPE_PEER_JOINED -> handlePeerJoined(json)
            TYPE_PEER_LEFT -> handlePeerLeft(json)
            TYPE_ERROR -> handleServerError(json)
            TYPE_PONG -> handlePong(json)
            TYPE_OWNER_COOKIE,
            TYPE_PLAYLIST,
            TYPE_CURRENT_SONG,
            TYPE_PLAY_STATE,
            TYPE_SEEK -> handleRemoteEvent(json)
        }
    }

    private fun handleServerReady(json: JsonObject) {
        val acceptedRole = when (json["role"]?.asString.orEmpty()) {
            "owner" -> ListenTogetherRole.Owner
            "guest" -> ListenTogetherRole.Guest
            else -> _state.value.role
        }
        val reconnectConnection = currentConnectionIsReconnect
        ListenTogetherDebugLogger.log("server_ready", mapOf(
            "roomCode" to lastRoomCode,
            "acceptedRole" to acceptedRole.name,
            "reconnectConnection" to reconnectConnection,
            "serverRole" to json["role"]?.asString.orEmpty(),
            "serverTs" to json["ts"]?.asLong
        ))
        acceptedOnce = true
        reconnectAttempts = 0
        reconnectStartedAt = 0L
        serverReadyTimeoutJob?.cancel()
        serverReadyTimeoutJob = null
        ListenTogetherKeepAliveService.start(CommonApp.app)
        ListenTogetherDebugLogger.log("keep_alive_service_start_requested", mapOf(
            "roomCode" to lastRoomCode,
            "role" to acceptedRole.name
        ))
        _state.value = _state.value.copy(
            role = acceptedRole,
            connected = true,
            serverAccepted = true,
            communicationText = "服务器已接受请求",
            statusText = if (acceptedRole == ListenTogetherRole.Owner) {
                "房间已创建，等待对方加入"
            } else {
                "已加入房间，等待同步"
            }
        )
        startHeartbeat()
        startWatchdog()
        if (acceptedRole == ListenTogetherRole.Owner && !reconnectConnection) {
            sendOwnerCookieEvent()
            sendPlaylistEvent()
            playerController?.currentSong?.value?.let { sendCurrentSongEvent(it) }
            sendPlayStateEvent(playerController?.mediaController?.isPlaying == true)
        } else if (acceptedRole == ListenTogetherRole.Owner) {
            ListenTogetherDebugLogger.log("owner_initial_snapshot_skipped", mapOf(
                "reason" to "reconnect_wait_for_server_replay",
                "roomCode" to lastRoomCode
            ))
        }
        currentConnectionIsReconnect = false
    }

    private fun handleEventAck(json: JsonObject) {
        val version = json["serverVersion"]?.asLong ?: return
        val eventType = json["eventType"]?.asString.orEmpty()
        ListenTogetherDebugLogger.log("event_ack", mapOf(
            "eventType" to eventType,
            "serverVersion" to version,
            "serverTs" to json["ts"]?.asLong
        ))
        _state.value = _state.value.copy(
            communicationText = "服务器已同步 $eventType #$version"
        )
    }

    private fun handlePeerJoined(json: JsonObject) {
        val peerName = json["deviceName"]?.asString.orEmpty()
        ListenTogetherDebugLogger.log("peer_joined", mapOf(
            "peerName" to peerName,
            "role" to json["role"]?.asString.orEmpty(),
            "serverTs" to json["ts"]?.asLong
        ))
        _state.value = _state.value.copy(
            peerName = peerName.ifEmpty { _state.value.peerName },
            statusText = "正在一起听"
        )
        if (_state.value.role == ListenTogetherRole.Owner) {
            sendOwnerCookieEvent()
            sendPlaylistEvent()
            playerController?.currentSong?.value?.let { sendCurrentSongEvent(it) }
            sendPlayStateEvent(playerController?.mediaController?.isPlaying == true)
        }
    }

    private fun handlePeerLeft(json: JsonObject) {
        val peerName = json["deviceName"]?.asString.orEmpty()
        ListenTogetherDebugLogger.log("peer_left", mapOf(
            "peerName" to peerName,
            "senderId" to json["senderId"]?.asString.orEmpty(),
            "serverTs" to json["ts"]?.asLong
        ))
        ToastUtils.show(if (peerName.isEmpty()) "对方已退出一起听" else "$peerName 已退出一起听")
        leave(notifyPeer = false)
    }

    private fun handleServerError(json: JsonObject) {
        val message = json["message"]?.asString ?: "一起听房间不可用"
        ListenTogetherDebugLogger.log("server_error", mapOf(
            "message" to message,
            "acceptedOnce" to acceptedOnce,
            "manualLeaving" to manualLeaving,
            "roomCode" to lastRoomCode,
            "serverTs" to json["ts"]?.asLong
        ))
        if (!manualLeaving && acceptedOnce && lastRoomCode.isNotEmpty()) {
            _state.value = _state.value.copy(
                connected = false,
                serverAccepted = false,
                communicationText = "服务器异常：$message",
                statusText = "一起听连接恢复中"
            )
            scheduleReconnect("server error")
        } else {
            ToastUtils.show(message)
            leave(notifyPeer = false)
        }
    }

    private fun handlePong(json: JsonObject) {
        val sentAt = json["clientSentAt"]?.asLong ?: return
        val latency = (System.currentTimeMillis() - sentAt).coerceAtLeast(0L)
        val workerMs = ((json["workerSentAt"]?.asLong ?: 0L) - (json["workerReceivedAt"]?.asLong ?: 0L))
            .coerceAtLeast(0L)
        lastPongAt = System.currentTimeMillis()
        ListenTogetherDebugLogger.log("pong", mapOf(
            "clientSentAt" to sentAt,
            "latencyMs" to latency,
            "workerLatencyMs" to workerMs,
            "workerReceivedAt" to json["workerReceivedAt"]?.asLong,
            "workerSentAt" to json["workerSentAt"]?.asLong,
            "peerDeviceName" to json["peerDeviceName"]?.asString.orEmpty(),
            "peerStatus" to json["peerStatus"]
                ?.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.let { summarizePresenceStatus(it) }
        ))
        _state.value = _state.value.copy(
            connected = true,
            latencyMs = latency,
            workerLatencyMs = workerMs,
            communicationText = buildPongCommunicationText(json, latency)
        )
        val peerDeviceName = json["peerDeviceName"]?.asString.orEmpty()
        if (peerDeviceName.isNotBlank()) {
            _state.value = _state.value.copy(peerName = peerDeviceName)
        }
    }

    private fun handleRemoteEvent(json: JsonObject) {
        val senderId = json["senderId"]?.asString.orEmpty()
        if (senderId == clientId) {
            ListenTogetherDebugLogger.log("remote_event_ignored", remoteEventLogFields(json) + mapOf(
                "reason" to "self_sender"
            ))
            return
        }
        val version = json["serverVersion"]?.asLong ?: 0L
        if (version > 0 && version <= lastRemoteServerVersion) {
            ListenTogetherDebugLogger.log("remote_event_ignored", remoteEventLogFields(json) + mapOf(
                "reason" to "stale_version",
                "lastRemoteServerVersion" to lastRemoteServerVersion
            ))
            return
        }
        if (version > 0) lastRemoteServerVersion = version
        updatePeerNameFromRemote(json)
        ListenTogetherDebugLogger.log("remote_event_accept", remoteEventLogFields(json) + mapOf(
            "lastRemoteServerVersion" to lastRemoteServerVersion
        ))

        when (json["type"]?.asString) {
            TYPE_OWNER_COOKIE -> handleOwnerCookieEvent(json)
            TYPE_PLAYLIST -> handlePlaylistEvent(json)
            TYPE_CURRENT_SONG -> handleCurrentSongEvent(json)
            TYPE_PLAY_STATE -> handlePlayStateEvent(json)
            TYPE_SEEK -> handleSeekEvent(json)
        }
    }

    private fun handleOwnerCookieEvent(json: JsonObject) {
        if (_state.value.role != ListenTogetherRole.Guest) {
            ListenTogetherDebugLogger.log("owner_cookie_ignored", remoteEventLogFields(json) + mapOf(
                "reason" to "not_guest",
                "localRole" to _state.value.role.name
            ))
            return
        }
        val cookie = json.payload()["cookie"]?.asString.orEmpty()
        temporaryOwnerCookie = cookie
        ListenTogetherDebugLogger.log("owner_cookie_applied", remoteEventLogFields(json) +
            ListenTogetherDebugLogger.cookieSummary(cookie))
    }

    private fun handlePlaylistEvent(json: JsonObject) {
        val controller = playerController
        if (controller == null) {
            ListenTogetherDebugLogger.log("playlist_ignored", remoteEventLogFields(json) + mapOf(
                "reason" to "player_controller_missing"
            ))
            return
        }
        val payload = json.payload()
        val songs = payload["playlist"]?.takeIf { it.isJsonArray }?.let {
            GsonUtils.fromJson<List<ListenSong>>(
                it.toString(),
                object : TypeToken<List<ListenSong>>() {}.type
            )
        }.orEmpty()
        val playlist = songs.map { it.toMediaItem() }
        if (playlist.isEmpty()) {
            ListenTogetherDebugLogger.log("playlist_ignored", remoteEventLogFields(json) + mapOf(
                "reason" to "empty_playlist"
            ))
            return
        }

        val currentMediaId = payload["currentMediaId"]?.asString.orEmpty()
        val currentIndex = payload["currentIndex"]?.asInt ?: 0
        val current = playlist.firstOrNull { it.mediaId == currentMediaId }
            ?: playlist.getOrNull(currentIndex.coerceIn(0, playlist.lastIndex))
            ?: playlist.first()
        val playing = payload["playing"]?.asBoolean ?: controller.mediaController.isPlaying
        ListenTogetherDebugLogger.log("playlist_apply_start", remoteEventLogFields(json) + mapOf(
            "playlistSize" to playlist.size,
            "currentMediaId" to current.mediaId,
            "currentTitle" to current.mediaMetadata.title?.toString().orEmpty(),
            "currentIndex" to currentIndex,
            "playing" to playing
        ))

        applyRemote {
            controller.replaceAllFromRemote(playlist, current, playing)
        }
        updateCurrentUi(current)
        ListenTogetherDebugLogger.log("playlist_apply_done", remoteEventLogFields(json) + mapOf(
            "playlistSize" to playlist.size,
            "currentMediaId" to current.mediaId,
            "playing" to playing
        ))
    }

    private fun handleCurrentSongEvent(json: JsonObject) {
        val controller = playerController
        if (controller == null) {
            ListenTogetherDebugLogger.log("current_song_ignored", remoteEventLogFields(json) + mapOf(
                "reason" to "player_controller_missing"
            ))
            return
        }
        val payload = json.payload()
        val song = payload["song"]?.takeIf { it.isJsonObject }?.let {
            GsonUtils.fromJson(it.toString(), ListenSong::class.java)
        }
        if (song == null) {
            ListenTogetherDebugLogger.log("current_song_ignored", remoteEventLogFields(json) + mapOf(
                "reason" to "missing_song_payload"
            ))
            return
        }
        val target = song.toMediaItem()
        val playing = payload["playing"]?.asBoolean ?: controller.mediaController.isPlaying
        val positionMs = payload["positionMs"]?.asLong ?: 0L
        val eventVersion = json["serverVersion"]?.asLong ?: 0L
        ListenTogetherDebugLogger.log("current_song_apply_start", remoteEventLogFields(json) + mapOf(
            "mediaId" to target.mediaId,
            "title" to target.mediaMetadata.title?.toString().orEmpty(),
            "index" to (payload["index"]?.asInt ?: 0),
            "playing" to playing,
            "positionMs" to positionMs
        ))

        applyRemote {
            val playlist = ensureSongInPlaylist(target, payload["index"]?.asInt ?: 0)
            val desiredPlaying = pendingRemotePlaying ?: playing
            val desiredPositionMs = pendingRemotePositionMs.takeIf { it >= 0 } ?: positionMs
            controller.replaceAllFromRemote(playlist, target, desiredPlaying)
            launch {
                delay(350L)
                val finalPlaying = pendingRemotePlaying ?: desiredPlaying
                val finalPositionMs = pendingRemotePositionMs.takeIf { it >= 0 } ?: desiredPositionMs
                if (finalPositionMs > 0) controller.seekTo(finalPositionMs.toInt())
                applyPlayState(finalPlaying)
                ListenTogetherDebugLogger.log("current_song_delayed_apply_done", remoteEventLogFields(json) + mapOf(
                    "mediaId" to target.mediaId,
                    "eventVersion" to eventVersion,
                    "lastRemoteServerVersion" to lastRemoteServerVersion,
                    "playing" to finalPlaying,
                    "positionMs" to finalPositionMs
                ))
            }
        }
        updateCurrentUi(target)
        ListenTogetherDebugLogger.log("current_song_apply_done", remoteEventLogFields(json) + mapOf(
            "mediaId" to target.mediaId,
            "playing" to playing,
            "positionMs" to positionMs
        ))
    }

    private fun handlePlayStateEvent(json: JsonObject) {
        val payload = json.payload()
        val playing = payload["playing"]?.asBoolean
        if (playing == null) {
            ListenTogetherDebugLogger.log("play_state_ignored", remoteEventLogFields(json) + mapOf(
                "reason" to "missing_playing"
            ))
            return
        }
        val positionMs = payload["positionMs"]?.asLong ?: -1L
        pendingRemotePlaying = playing
        pendingRemotePositionMs = positionMs
        ListenTogetherDebugLogger.log("play_state_apply_start", remoteEventLogFields(json) + mapOf(
            "playing" to playing,
            "positionMs" to positionMs
        ))
        applyRemote {
            if (positionMs >= 0) maybeSeek(positionMs)
            applyPlayState(playing)
        }
        updatePauseAutoLeave(playing)
        ListenTogetherDebugLogger.log("play_state_apply_done", remoteEventLogFields(json) + mapOf(
            "playing" to playing,
            "positionMs" to positionMs
        ))
    }

    private fun handleSeekEvent(json: JsonObject) {
        val positionMs = json.payload()["positionMs"]?.asLong
        if (positionMs == null) {
            ListenTogetherDebugLogger.log("seek_ignored", remoteEventLogFields(json) + mapOf(
                "reason" to "missing_position"
            ))
            return
        }
        ListenTogetherDebugLogger.log("seek_apply_start", remoteEventLogFields(json) + mapOf(
            "positionMs" to positionMs
        ))
        pendingRemotePositionMs = positionMs
        applyRemote {
            maybeSeek(positionMs)
        }
        ListenTogetherDebugLogger.log("seek_apply_done", remoteEventLogFields(json) + mapOf(
            "positionMs" to positionMs
        ))
    }

    private fun ensureSongInPlaylist(song: MediaItem, requestedIndex: Int): List<MediaItem> {
        val current = playerController?.playlist?.value.orEmpty()
        if (current.isEmpty()) return listOf(song)
        val existingIndex = current.indexOfFirst { it.mediaId == song.mediaId }
        if (existingIndex >= 0) {
            return current.mapIndexed { index, item ->
                if (index == existingIndex) song else item
            }
        }
        val newPlaylist = current.toMutableList()
        val insertIndex = requestedIndex.coerceIn(0, newPlaylist.size)
        newPlaylist.add(insertIndex, song)
        return newPlaylist
    }

    private fun applyPlayState(playing: Boolean) {
        val controller = playerController ?: return
        val player = controller.mediaController
        if (playing) {
            if (player.playbackState == Player.STATE_IDLE) {
                player.prepare()
            }
            if (!player.isPlaying) {
                player.play()
            }
        } else if (player.isPlaying) {
            player.pause()
        }
    }

    private fun maybeSeek(positionMs: Long) {
        val controller = playerController ?: return
        if (abs(controller.playProgress.value - positionMs) > SEEK_DRIFT_MS) {
            controller.seekTo(positionMs.toInt())
        }
    }

    private fun applyRemote(block: () -> Unit) {
        applyingRemote = true
        remoteGuardJob?.cancel()
        try {
            block()
        } finally {
            remoteGuardJob = launch {
                delay(APPLY_REMOTE_GUARD_MS)
                applyingRemote = false
            }
        }
    }

    private fun sendOwnerCookieEvent() {
        if (_state.value.role != ListenTogetherRole.Owner) {
            ListenTogetherDebugLogger.log("send_owner_cookie_skipped", mapOf(
                "reason" to "not_owner",
                "role" to _state.value.role.name
            ))
            return
        }
        val cookie = AccountPreference.cookie
        if (cookie.isEmpty()) {
            ListenTogetherDebugLogger.log("send_owner_cookie_skipped", mapOf(
                "reason" to "empty_cookie"
            ))
            return
        }
        ListenTogetherDebugLogger.log("send_owner_cookie_prepare",
            ListenTogetherDebugLogger.cookieSummary(cookie))
        sendEvent(TYPE_OWNER_COOKIE, JsonObject().apply {
            addProperty("cookie", cookie)
        })
    }

    private fun sendPlaylistEvent() {
        if (!canSendEvent(TYPE_PLAYLIST)) return
        val controller = playerController
        if (controller == null) {
            ListenTogetherDebugLogger.log("send_event_skipped", mapOf(
                "type" to TYPE_PLAYLIST,
                "reason" to "player_controller_missing"
            ))
            return
        }
        val playlist = controller.playlist.value
        if (playlist.isEmpty()) {
            ListenTogetherDebugLogger.log("send_event_skipped", mapOf(
                "type" to TYPE_PLAYLIST,
                "reason" to "empty_playlist"
            ))
            return
        }
        val current = controller.currentSong.value ?: playlist.getOrNull(controller.mediaController.currentMediaItemIndex)
        val payload = JsonObject().apply {
            add("playlist", JsonParser.parseString(GsonUtils.toJson(playlist.map { it.toListenSong() })))
            addProperty("currentMediaId", current?.mediaId.orEmpty())
            addProperty("currentIndex", playlist.indexOfFirst { it.mediaId == current?.mediaId }.coerceAtLeast(0))
            addProperty("playing", controller.mediaController.isPlaying)
        }
        sendEvent(TYPE_PLAYLIST, payload)
        current?.let { updateCurrentUi(it) }
    }

    private fun sendCurrentSongEvent(song: MediaItem) {
        if (!canSendEvent(TYPE_CURRENT_SONG)) return
        val controller = playerController
        if (controller == null) {
            ListenTogetherDebugLogger.log("send_event_skipped", mapOf(
                "type" to TYPE_CURRENT_SONG,
                "reason" to "player_controller_missing",
                "mediaId" to song.mediaId
            ))
            return
        }
        val playlist = controller.playlist.value
        val payload = JsonObject().apply {
            add("song", JsonParser.parseString(GsonUtils.toJson(song.toListenSong())))
            addProperty("mediaId", song.mediaId)
            addProperty("index", playlist.indexOfFirst { it.mediaId == song.mediaId }.coerceAtLeast(0))
            addProperty("playing", controller.mediaController.isPlaying)
            addProperty("positionMs", controller.playProgress.value.coerceAtLeast(0L))
        }
        sendEvent(TYPE_CURRENT_SONG, payload)
        updateCurrentUi(song)
    }

    private fun sendPlayStateEvent(isPlaying: Boolean) {
        if (!canSendEvent(TYPE_PLAY_STATE)) return
        val position = playerController?.playProgress?.value ?: 0L
        sendEvent(TYPE_PLAY_STATE, JsonObject().apply {
            addProperty("playing", isPlaying)
            addProperty("positionMs", position.coerceAtLeast(0L))
        })
    }

    private fun sendSeekEvent(positionMs: Long) {
        if (!canSendEvent(TYPE_SEEK)) return
        sendEvent(TYPE_SEEK, JsonObject().apply {
            addProperty("positionMs", positionMs.coerceAtLeast(0L))
        })
    }

    private fun canSendEvent(type: String): Boolean {
        val canSend = ConfigPreferences.listenTogetherEnabled &&
            isActive &&
            _state.value.serverAccepted &&
            !applyingRemote
        if (!canSend) {
            ListenTogetherDebugLogger.log("send_event_skipped", mapOf(
                "type" to type,
                "reason" to when {
                    !ConfigPreferences.listenTogetherEnabled -> "feature_disabled"
                    !isActive -> "not_active"
                    !_state.value.serverAccepted -> "server_not_accepted"
                    applyingRemote -> "applying_remote"
                    else -> "unknown"
                },
                "roomCode" to lastRoomCode,
                "serverAccepted" to _state.value.serverAccepted,
                "applyingRemote" to applyingRemote
            ))
        }
        return canSend
    }

    private fun sendEvent(type: String, payload: JsonObject) {
        val clientSentAt = System.currentTimeMillis()
        val clientVersion = ++localVersion
        val json = JsonObject().apply {
            addProperty("type", type)
            addProperty("senderId", clientId)
            addProperty("deviceName", displayName())
            addProperty("clientVersion", clientVersion)
            addProperty("clientSentAt", clientSentAt)
            add("payload", payload)
        }
        val sent = webSocket?.send(GsonUtils.toJson(json)) == true
        ListenTogetherDebugLogger.log("send_event", mapOf(
            "type" to type,
            "sent" to sent,
            "clientVersion" to clientVersion,
            "clientSentAt" to clientSentAt,
            "roomCode" to lastRoomCode,
            "role" to _state.value.role.name,
            "payload" to summarizePayload(type, payload)
        ))
    }

    private fun updateCurrentUi(song: MediaItem) {
        _state.value = _state.value.copy(
            currentTitle = song.mediaMetadata.title?.toString().orEmpty(),
            currentArtist = song.mediaMetadata.artist?.toString().orEmpty(),
            statusText = "正在一起听"
        )
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        ListenTogetherDebugLogger.log("heartbeat_start", mapOf(
            "roomCode" to lastRoomCode,
            "intervalMs" to HEARTBEAT_INTERVAL_MS,
            "timeoutMs" to HEARTBEAT_TIMEOUT_MS
        ))
        heartbeatJob = launch {
            lastPongAt = System.currentTimeMillis()
            sendPing()
            while (!manualLeaving && ConfigPreferences.listenTogetherEnabled && lastRoomCode.isNotEmpty()) {
                delay(HEARTBEAT_INTERVAL_MS)
                if (System.currentTimeMillis() - lastPongAt > HEARTBEAT_TIMEOUT_MS) {
                    ListenTogetherDebugLogger.log("heartbeat_timeout", mapOf(
                        "roomCode" to lastRoomCode,
                        "lastPongAt" to lastPongAt,
                        "elapsedMs" to (System.currentTimeMillis() - lastPongAt)
                    ))
                    _state.value = _state.value.copy(
                        connected = false,
                        serverAccepted = false,
                        communicationText = "服务器心跳超时，正在重新进入房间",
                        statusText = "一起听连接恢复中"
                    )
                    scheduleReconnect("heartbeat timeout")
                    return@launch
                }
                if (!sendPing()) {
                    ListenTogetherDebugLogger.log("heartbeat_send_failed", mapOf(
                        "roomCode" to lastRoomCode
                    ))
                    scheduleReconnect("send failed")
                    return@launch
                }
            }
        }
    }

    private fun startWatchdog() {
        watchdogJob?.cancel()
        ListenTogetherDebugLogger.log("watchdog_start", mapOf(
            "roomCode" to lastRoomCode
        ))
        watchdogJob = launch {
            while (!manualLeaving && ConfigPreferences.listenTogetherEnabled && lastRoomCode.isNotEmpty()) {
                delay(20_000L)
                if (webSocket == null || System.currentTimeMillis() - lastPongAt > HEARTBEAT_TIMEOUT_MS) {
                    ListenTogetherDebugLogger.log("watchdog_reconnect", mapOf(
                        "roomCode" to lastRoomCode,
                        "webSocketNull" to (webSocket == null),
                        "lastPongAt" to lastPongAt,
                        "elapsedMs" to (System.currentTimeMillis() - lastPongAt)
                    ))
                    _state.value = _state.value.copy(
                        connected = false,
                        serverAccepted = false,
                        communicationText = "连接不活跃，正在重新进入房间",
                        statusText = "一起听连接恢复中"
                    )
                    scheduleReconnect("watchdog")
                    return@launch
                }
            }
        }
    }

    private fun startServerReadyTimeout() {
        serverReadyTimeoutJob?.cancel()
        val socket = webSocket
        serverReadyTimeoutJob = launch {
            val remainingReconnectMs = if (reconnectStartedAt > 0L) {
                (RECONNECT_TOTAL_TIMEOUT_MS - (System.currentTimeMillis() - reconnectStartedAt))
                    .coerceAtMost(SERVER_READY_TIMEOUT_MS)
                    .coerceAtLeast(0L)
            } else {
                SERVER_READY_TIMEOUT_MS
            }
            delay(remainingReconnectMs)
            if (!manualLeaving && webSocket === socket && !_state.value.serverAccepted && lastRoomCode.isNotEmpty()) {
                if (reconnectStartedAt > 0L &&
                    System.currentTimeMillis() - reconnectStartedAt >= RECONNECT_TOTAL_TIMEOUT_MS
                ) {
                    finishReconnectFailure("server_ready timeout")
                } else {
                    _state.value = _state.value.copy(
                        connected = false,
                        serverAccepted = false,
                        communicationText = "服务器确认超时，正在重新进入房间",
                        statusText = "一起听连接恢复中"
                    )
                    scheduleReconnect("server_ready timeout")
                }
            }
        }
    }

    private fun scheduleReconnect(reason: String) {
        if (!ConfigPreferences.listenTogetherEnabled) {
            ListenTogetherDebugLogger.log("reconnect_skipped", mapOf(
                "reason" to reason,
                "roomCode" to lastRoomCode,
                "skipReason" to "feature_disabled"
            ))
            clearActiveSession()
            return
        }
        if (reconnectJob?.isActive == true) {
            ListenTogetherDebugLogger.log("reconnect_schedule_ignored", mapOf(
                "reason" to reason,
                "roomCode" to lastRoomCode,
                "reconnectAttempts" to reconnectAttempts
            ))
            return
        }
        if (reconnectStartedAt == 0L) {
            reconnectStartedAt = System.currentTimeMillis()
            reconnectAttempts = 0
        }
        ListenTogetherDebugLogger.log("reconnect_scheduled", mapOf(
            "reason" to reason,
            "roomCode" to lastRoomCode,
            "mode" to lastMode,
            "startedAt" to reconnectStartedAt,
            "attemptsBefore" to reconnectAttempts
        ))
        reconnectJob = launch {
            if (System.currentTimeMillis() - reconnectStartedAt > RECONNECT_TOTAL_TIMEOUT_MS ||
                reconnectAttempts >= reconnectDelaysMs.size
            ) {
                ListenTogetherDebugLogger.log("reconnect_exhausted", mapOf(
                    "reason" to reason,
                    "roomCode" to lastRoomCode,
                    "attempts" to reconnectAttempts,
                    "elapsedMs" to (System.currentTimeMillis() - reconnectStartedAt)
                ))
                finishReconnectFailure(reason)
                return@launch
            }
            val attempt = reconnectAttempts + 1
            val delayMs = reconnectDelaysMs[reconnectAttempts]
            reconnectAttempts = attempt
            val remainingMs = RECONNECT_TOTAL_TIMEOUT_MS - (System.currentTimeMillis() - reconnectStartedAt)
            if (remainingMs <= 0L) {
                ListenTogetherDebugLogger.log("reconnect_no_time_left", mapOf(
                    "reason" to reason,
                    "roomCode" to lastRoomCode,
                    "attempt" to attempt
                ))
                finishReconnectFailure(reason)
                return@launch
            }
            ListenTogetherDebugLogger.log("reconnect_attempt_wait", mapOf(
                "reason" to reason,
                "roomCode" to lastRoomCode,
                "attempt" to attempt,
                "delayMs" to delayMs,
                "remainingMs" to remainingMs
            ))
            delay(delayMs.coerceAtMost(remainingMs))
            if (System.currentTimeMillis() - reconnectStartedAt > RECONNECT_TOTAL_TIMEOUT_MS) {
                ListenTogetherDebugLogger.log("reconnect_attempt_timeout", mapOf(
                    "reason" to reason,
                    "roomCode" to lastRoomCode,
                    "attempt" to attempt,
                    "elapsedMs" to (System.currentTimeMillis() - reconnectStartedAt)
                ))
                finishReconnectFailure(reason)
                return@launch
            }
            if (!manualLeaving && lastRoomCode.isNotEmpty() && lastWorkerUrl.isNotEmpty()) {
                ListenTogetherDebugLogger.log("reconnect_attempt_start", mapOf(
                    "reason" to reason,
                    "roomCode" to lastRoomCode,
                    "attempt" to attempt,
                    "mode" to reconnectMode(),
                    "workerUrl" to lastWorkerUrl
                ))
                _state.value = _state.value.copy(
                    connected = false,
                    serverAccepted = false,
                    communicationText = "正在重新进入原房间（第 $attempt/${reconnectDelaysMs.size} 次）",
                    statusText = "一起听连接恢复中"
                )
                reconnectToOriginalRoom()
            } else {
                ListenTogetherDebugLogger.log("reconnect_attempt_skipped", mapOf(
                    "reason" to reason,
                    "manualLeaving" to manualLeaving,
                    "roomCode" to lastRoomCode,
                    "workerUrlPresent" to lastWorkerUrl.isNotEmpty()
                ))
            }
        }
    }

    private fun reconnectToOriginalRoom() {
        val room = lastRoomCode
        val worker = lastWorkerUrl
        val mode = reconnectMode()
        if (room.isEmpty() || worker.isEmpty()) {
            ListenTogetherDebugLogger.log("reconnect_missing_room", mapOf(
                "roomCode" to room,
                "workerUrlPresent" to worker.isNotEmpty()
            ))
            finishReconnectFailure("missing room")
            return
        }
        ListenTogetherDebugLogger.log("reconnect_to_original_room", mapOf(
            "roomCode" to room,
            "mode" to mode,
            "workerUrl" to worker
        ))
        cancelConnectionJobs(closeSocket = true, cancelReconnectJob = false)
        lastPongAt = 0L
        connect(room, worker, mode, resetFirst = false, cancelReconnectJob = false)
    }

    private fun reconnectMode(): String {
        return when {
            lastMode == "create" -> "create"
            lastMode == "join" -> "join"
            _state.value.role == ListenTogetherRole.Owner -> "create"
            else -> "join"
        }
    }

    private fun finishReconnectFailure(reason: String) {
        ListenTogetherDebugLogger.log("reconnect_failure_finish", mapOf(
            "reason" to reason,
            "roomCode" to lastRoomCode,
            "mode" to lastMode,
            "attempts" to reconnectAttempts,
            "elapsedMs" to if (reconnectStartedAt > 0L) System.currentTimeMillis() - reconnectStartedAt else 0L
        ))
        val message = "一起听服务器无法连接，请重试"
        manualLeaving = true
        clearActiveSession()
        playerController?.mediaController?.pause()
        reconnectAttempts = 0
        reconnectStartedAt = 0L
        acceptedOnce = false
        _state.value = ListenTogetherUiState(
            statusText = message,
            communicationText = "已停止重连：$reason"
        )
        lastRoomCode = ""
        lastWorkerUrl = ""
        lastMode = ""
        showConnectionFailureDialog(message)
    }

    private fun showConnectionFailureDialog(message: String) {
        if (failureDialogShowing) {
            ToastUtils.show(message)
            return
        }
        val activity = ActivityUtils.getTopActivity()
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            ToastUtils.show(message)
            return
        }
        failureDialogShowing = true
        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("一起听连接失败")
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .setOnDismissListener { failureDialogShowing = false }
                .show()
        }.onFailure {
            failureDialogShowing = false
            ToastUtils.show(message)
        }
    }

    private fun clearActiveSession() {
        ListenTogetherDebugLogger.log("clear_active_session", mapOf(
            "roomCode" to lastRoomCode,
            "mode" to lastMode,
            "role" to _state.value.role.name,
            "connected" to _state.value.connected,
            "serverAccepted" to _state.value.serverAccepted,
            "temporaryOwnerCookiePresent" to temporaryOwnerCookie.isNotEmpty()
        ))
        cancelConnectionJobs(closeSocket = true)
        pauseAutoLeaveJob?.cancel()
        pauseAutoLeaveJob = null
        remoteGuardJob?.cancel()
        remoteGuardJob = null
        webSocket = null
        lastRemoteServerVersion = 0L
        pendingRemotePlaying = null
        pendingRemotePositionMs = -1L
        clearTemporaryOwnerCookie()
        OnlineMusicUriFetcher.clearPrefetchedUrls()
        ListenTogetherKeepAliveService.stop(CommonApp.app)
        ListenTogetherDebugLogger.log("keep_alive_service_stop_requested", mapOf(
            "roomCode" to lastRoomCode
        ))
    }

    private fun cancelConnectionJobs(closeSocket: Boolean, cancelReconnectJob: Boolean = true) {
        ListenTogetherDebugLogger.log("cancel_connection_jobs", mapOf(
            "closeSocket" to closeSocket,
            "cancelReconnectJob" to cancelReconnectJob,
            "heartbeatActive" to (heartbeatJob?.isActive == true),
            "watchdogActive" to (watchdogJob?.isActive == true),
            "reconnectActive" to (reconnectJob?.isActive == true),
            "serverReadyTimeoutActive" to (serverReadyTimeoutJob?.isActive == true),
            "webSocketPresent" to (webSocket != null)
        ))
        heartbeatJob?.cancel()
        heartbeatJob = null
        watchdogJob?.cancel()
        watchdogJob = null
        if (cancelReconnectJob) {
            reconnectJob?.cancel()
            reconnectJob = null
        }
        serverReadyTimeoutJob?.cancel()
        serverReadyTimeoutJob = null
        if (closeSocket) {
            webSocket?.cancel()
        }
        webSocket = null
    }

    private fun updatePauseAutoLeave(isPlaying: Boolean) {
        pauseAutoLeaveJob?.cancel()
        pauseAutoLeaveJob = null
        if (isPlaying || !isActive) return
        pauseAutoLeaveJob = launch {
            delay(PAUSE_AUTO_LEAVE_MS)
            if (!manualLeaving && lastRoomCode.isNotEmpty()) {
                ToastUtils.show("已暂停超过 30 分钟，已退出一起听")
                leave()
            }
        }
    }

    private fun sendSimple(type: String) {
        val clientSentAt = System.currentTimeMillis()
        val json = JsonObject().apply {
            addProperty("type", type)
            addProperty("senderId", clientId)
            addProperty("deviceName", displayName())
            addProperty("clientSentAt", clientSentAt)
        }
        val sent = webSocket?.send(GsonUtils.toJson(json)) == true
        ListenTogetherDebugLogger.log("send_simple", mapOf(
            "type" to type,
            "sent" to sent,
            "clientSentAt" to clientSentAt,
            "roomCode" to lastRoomCode
        ))
    }

    private fun sendPing(): Boolean {
        val now = System.currentTimeMillis()
        val json = JsonObject().apply {
            addProperty("type", TYPE_PING)
            addProperty("senderId", clientId)
            addProperty("deviceName", displayName())
            addProperty("clientSentAt", now)
            addProperty("ts", now)
            add("status", buildCurrentStatusPayload())
        }
        val sent = webSocket?.send(GsonUtils.toJson(json)) == true
        ListenTogetherDebugLogger.log("send_ping", mapOf(
            "sent" to sent,
            "clientSentAt" to now,
            "roomCode" to lastRoomCode,
            "status" to summarizePresenceStatus(json["status"]?.asJsonObject ?: JsonObject())
        ))
        return sent
    }

    private fun buildCurrentStatusPayload(): JsonObject {
        val controller = playerController
        val song = controller?.currentSong?.value
        return JsonObject().apply {
            addProperty("title", song?.mediaMetadata?.title?.toString().orEmpty())
            addProperty("artist", song?.mediaMetadata?.artist?.toString().orEmpty())
            addProperty("mediaId", song?.mediaId.orEmpty())
            addProperty("playing", controller?.mediaController?.isPlaying == true)
            addProperty("positionMs", controller?.playProgress?.value?.coerceAtLeast(0L) ?: 0L)
        }
    }

    private fun buildPongCommunicationText(json: JsonObject, latency: Long): String {
        val peerStatus = json["peerStatus"]?.takeIf { it.isJsonObject }?.asJsonObject
        val title = peerStatus?.get("title")?.asString.orEmpty()
        val playing = peerStatus?.get("playing")?.asBoolean
        return if (title.isNotEmpty() && playing != null) {
            val stateText = if (playing) "播放中" else "已暂停"
            "服务器通信正常，延迟 ${latency}ms；对方 $stateText：$title"
        } else {
            "服务器通信正常，延迟 ${latency}ms"
        }
    }

    private fun MediaItem.toListenSong(): ListenSong {
        val originalUri = localConfiguration?.uri?.withoutQueryParameter("playUrl")?.toString().orEmpty()
        return ListenSong(
            mediaId = mediaId,
            uri = originalUri,
            title = mediaMetadata.title?.toString().orEmpty(),
            artist = mediaMetadata.artist?.toString().orEmpty(),
            album = mediaMetadata.albumTitle?.toString().orEmpty(),
            cover = mediaMetadata.getBaseCover().orEmpty(),
            duration = mediaMetadata.getDuration(),
            songId = getSongId()
        )
    }

    private fun ListenSong.toMediaItem(): MediaItem {
        val parsedUri = uri.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) }
        return MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(parsedUri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setAlbumArtist(artist)
                    .setArtworkUri(cover.takeIf { it.isNotEmpty() }?.let { Uri.parse(it) })
                    .setBaseCover(cover)
                    .setDuration(duration)
                    .build()
            )
            .build()
    }

    private fun Uri.withoutQueryParameter(name: String): Uri {
        val builder = buildUpon().clearQuery()
        queryParameterNames
            .filter { it != name }
            .forEach { key ->
                getQueryParameters(key).forEach { value ->
                    builder.appendQueryParameter(key, value)
                }
            }
        return builder.build()
    }

    private fun clearTemporaryOwnerCookie() {
        temporaryOwnerCookie = ""
    }

    private fun displayName(): String {
        return AccountPreference.profile?.nickname
            ?.takeIf { it.isNotBlank() }
            ?: AccountPreference.profile?.userName?.takeIf { it.isNotBlank() }
            ?: "网易云用户"
    }

    private fun JsonObject.payload(): JsonObject {
        return get("payload")?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    }

    private fun updatePeerNameFromRemote(json: JsonObject) {
        val remoteName = json["deviceName"]?.asString.orEmpty().trim()
        if (remoteName.isEmpty() || remoteName == "Cloudflare Worker") return
        if (remoteName == _state.value.peerName) return
        _state.value = _state.value.copy(
            peerName = remoteName,
            statusText = "正在一起听"
        )
    }

    private fun logIncomingMessage(text: String) {
        val json = runCatching { JsonParser.parseString(text).asJsonObject }.getOrNull()
        if (json == null) {
            ListenTogetherDebugLogger.log("recv_invalid_json", mapOf(
                "length" to text.length,
                "preview" to text.take(200)
            ))
            return
        }
        val type = json["type"]?.asString.orEmpty()
        val fields = mutableMapOf<String, Any?>(
            "type" to type,
            "senderId" to json["senderId"]?.asString.orEmpty(),
            "senderRole" to json["senderRole"]?.asString.orEmpty(),
            "deviceName" to json["deviceName"]?.asString.orEmpty(),
            "serverVersion" to json["serverVersion"]?.asLong,
            "clientVersion" to json["clientVersion"]?.asLong,
            "clientSentAt" to json["clientSentAt"]?.asLong,
            "serverAt" to json["serverAt"]?.asLong,
            "workerSentAt" to json["workerSentAt"]?.asLong,
            "replay" to (json["replay"]?.asBoolean ?: false)
        )
        val payload = json.payload()
        if (payload.entrySet().isNotEmpty()) {
            fields["payload"] = summarizePayload(type, payload)
        }
        ListenTogetherDebugLogger.log("recv_message", fields)
    }

    private fun remoteEventLogFields(json: JsonObject): Map<String, Any?> {
        val type = json["type"]?.asString.orEmpty()
        return mapOf(
            "type" to type,
            "senderId" to json["senderId"]?.asString.orEmpty(),
            "senderRole" to json["senderRole"]?.asString.orEmpty(),
            "deviceName" to json["deviceName"]?.asString.orEmpty(),
            "serverVersion" to (json["serverVersion"]?.asLong ?: 0L),
            "clientVersion" to (json["clientVersion"]?.asLong ?: 0L),
            "clientSentAt" to json["clientSentAt"]?.asLong,
            "serverAt" to json["serverAt"]?.asLong,
            "workerSentAt" to json["workerSentAt"]?.asLong,
            "replay" to (json["replay"]?.asBoolean ?: false),
            "payload" to summarizePayload(type, json.payload())
        )
    }

    private fun summarizePayload(type: String, payload: JsonObject): Map<String, Any?> {
        return when (type) {
            TYPE_OWNER_COOKIE -> ListenTogetherDebugLogger.cookieSummary(
                payload["cookie"]?.asString.orEmpty()
            )
            TYPE_PLAYLIST -> {
                val playlist = payload["playlist"]?.takeIf { it.isJsonArray }?.asJsonArray
                mapOf(
                    "playlistSize" to (playlist?.size() ?: 0),
                    "currentMediaId" to payload["currentMediaId"]?.asString.orEmpty(),
                    "currentIndex" to payload["currentIndex"]?.asInt,
                    "playing" to payload["playing"]?.asBoolean,
                    "firstSong" to playlist?.firstOrNull()?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                        summarizeSong(it)
                    }
                )
            }
            TYPE_CURRENT_SONG -> mapOf(
                "mediaId" to payload["mediaId"]?.asString.orEmpty(),
                "index" to payload["index"]?.asInt,
                "playing" to payload["playing"]?.asBoolean,
                "positionMs" to payload["positionMs"]?.asLong,
                "song" to payload["song"]?.takeIf { it.isJsonObject }?.asJsonObject?.let {
                    summarizeSong(it)
                }
            )
            TYPE_PLAY_STATE -> mapOf(
                "playing" to payload["playing"]?.asBoolean,
                "positionMs" to payload["positionMs"]?.asLong
            )
            TYPE_SEEK -> mapOf(
                "positionMs" to payload["positionMs"]?.asLong
            )
            else -> mapOf(
                "keys" to payload.entrySet().map { it.key }
            )
        }
    }

    private fun summarizePresenceStatus(status: JsonObject): Map<String, Any?> {
        return mapOf(
            "title" to status["title"]?.asString.orEmpty(),
            "artist" to status["artist"]?.asString.orEmpty(),
            "mediaId" to status["mediaId"]?.asString.orEmpty(),
            "playing" to (status["playing"]?.asBoolean ?: false),
            "positionMs" to (status["positionMs"]?.asLong ?: 0L)
        )
    }

    private fun summarizeSong(song: JsonObject): Map<String, Any?> {
        return mapOf(
            "mediaId" to song["mediaId"]?.asString.orEmpty(),
            "songId" to song["songId"]?.asLong,
            "title" to song["title"]?.asString.orEmpty(),
            "artist" to song["artist"]?.asString.orEmpty(),
            "duration" to song["duration"]?.asLong
        )
    }

    private fun String.withQuery(vararg params: Pair<String, String>): String {
        val separator = if (contains("?")) "&" else "?"
        return this + separator + params.joinToString("&") {
            "${it.first}=${URLEncoder.encode(it.second, "UTF-8")}"
        }
    }

    private fun String.normalizeWorkerUrl(): String {
        val trimmed = trim().trimEnd('/')
        val withScheme = when {
            trimmed.startsWith("https://") ||
                trimmed.startsWith("http://") ||
                trimmed.startsWith("wss://") ||
                trimmed.startsWith("ws://") -> trimmed
            trimmed.isNotEmpty() -> "https://$trimmed"
            else -> return ""
        }
        val wsUrl = when {
            withScheme.startsWith("https://") -> "wss://${withScheme.removePrefix("https://")}"
            withScheme.startsWith("http://") -> "ws://${withScheme.removePrefix("http://")}"
            withScheme.startsWith("wss://") || withScheme.startsWith("ws://") -> withScheme
            else -> return ""
        }
        return if (wsUrl.endsWith("/ws")) wsUrl else "$wsUrl/ws"
    }
}

data class ListenTogetherUiState(
    val roomCode: String = "",
    val role: ListenTogetherRole = ListenTogetherRole.None,
    val connected: Boolean = false,
    val serverAccepted: Boolean = false,
    val peerName: String = "",
    val startedAt: Long = 0L,
    val currentTitle: String = "",
    val currentArtist: String = "",
    val statusText: String = "未开启一起听",
    val communicationText: String = "未连接服务器",
    val latencyMs: Long = -1L,
    val workerLatencyMs: Long = -1L
)

enum class ListenTogetherRole {
    None,
    Owner,
    Guest
}

data class ListenSong(
    val mediaId: String = "",
    val uri: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val cover: String = "",
    val duration: Long = 0L,
    val songId: Long = 0L
)
