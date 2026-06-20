package me.wcy.music.utils

import android.net.Uri
import androidx.core.os.bundleOf
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import me.wcy.music.common.bean.SongData
import me.wcy.music.net.datasource.OnlineMusicUriFetcher
import me.wcy.music.storage.db.entity.SongEntity
import me.wcy.music.utils.MusicUtils.asLargeCover
import me.wcy.music.utils.MusicUtils.asSmallCover
import top.wangchenyan.common.CommonApp

/**
 * Created by wangchenyan.top on 2023/9/18.
 */

const val SCHEME_NETEASE = "netease"
const val PARAM_ID = "id"
const val EXTRA_DURATION = "duration"
const val EXTRA_FILE_PATH = "file_path"
const val EXTRA_FILE_NAME = "file_name"
const val EXTRA_FILE_SIZE = "file_size"
const val EXTRA_BASE_COVER = "base_cover"
const val EXTRA_SOURCE_PLAYLIST_ID = "source_playlist_id"

fun SongData.getSimpleArtist(): String {
    return ar.joinToString("/") { it.name }
}

fun SongEntity.toMediaItem(): MediaItem {
    val metadataBuilder = MediaMetadata.Builder()
                .setTitle(title)
                .setArtist(artist)
                .setAlbumTitle(album)
                .setAlbumArtist(artist)
                .setBaseCover(albumCover)
                .setDuration(duration)
                .setFilePath(path)
                .setFileName(fileName)
                .setFileSize(fileSize)
    if (type == SongEntity.LOCAL && albumCover.isNotBlank()) {
        metadataBuilder.setArtworkUri(Uri.parse(albumCover))
    }
    return MediaItem.Builder()
        .setMediaId(uniqueId)
        .setUri(uri)
        .setMediaMetadata(metadataBuilder.build())
        .build()
}

fun MediaItem.toSongEntity(): SongEntity {
    return SongEntity(
        type = getSongType(),
        songId = getSongId(),
        title = mediaMetadata.title?.toString() ?: "",
        artist = mediaMetadata.artist?.toString() ?: "",
        artistId = 0,
        album = mediaMetadata.albumTitle?.toString() ?: "",
        albumId = 0,
        albumCover = mediaMetadata.getBaseCover() ?: "",
        duration = mediaMetadata.getDuration(),
        uri = localConfiguration?.uri?.toString() ?: "",
        path = mediaMetadata.getFilePath(),
        fileName = mediaMetadata.getFileName(),
        fileSize = mediaMetadata.getFileSize()
    )
}

fun SongData.toMediaItem(sourcePlaylistId: Long = 0L): MediaItem {
    OnlineMusicUriFetcher.rememberSongs(listOf(this))
    val uri = Uri.Builder()
        .scheme(SCHEME_NETEASE)
        .authority(CommonApp.app.packageName)
        .appendQueryParameter(PARAM_ID, id.toString())
        .appendQueryParameter("name", name)
        .appendQueryParameter("artist", getSimpleArtist())
        .appendQueryParameter("album", al.name)
        .appendQueryParameter("duration", dt.toString())
        .build()
    return MediaItem.Builder()
        .setMediaId(generateUniqueId(SongEntity.ONLINE, id))
        .setUri(uri)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(name)
                .setArtist(getSimpleArtist())
                .setAlbumTitle(al.name)
                .setAlbumArtist(getSimpleArtist())
                .setArtworkUri(Uri.parse(al.getLargeCover()))
                .setBaseCover(al.picUrl)
                .setDuration(dt)
                .setSourcePlaylistId(sourcePlaylistId)
                .build()
        )
        .build()
}

fun generateUniqueId(type: Int, songId: Long): String {
    return "$type#$songId"
}

fun MediaItem.isLocal(): Boolean {
    return getSongType() == SongEntity.LOCAL
}

fun MediaItem.getSongType(): Int {
    return mediaId.split("#").firstOrNull()?.toIntOrNull() ?: SongEntity.LOCAL
}

fun MediaItem.getSongId(): Long {
    return mediaId.split("#").getOrNull(1)?.toLongOrNull() ?: 0L
}

fun MediaMetadata.Builder.setDuration(duration: Long) = apply {
    val extras = build().extras ?: bundleOf()
    extras.putLong(EXTRA_DURATION, duration)
    setExtras(extras)
}

fun MediaMetadata.getDuration(): Long {
    return extras?.getLong(EXTRA_DURATION) ?: 0
}

fun MediaMetadata.Builder.setFilePath(value: String) = apply {
    val extras = build().extras ?: bundleOf()
    extras.putString(EXTRA_FILE_PATH, value)
    setExtras(extras)
}

fun MediaMetadata.getFilePath(): String {
    return extras?.getString(EXTRA_FILE_PATH) ?: ""
}

fun MediaMetadata.Builder.setFileName(name: String) = apply {
    val extras = build().extras ?: bundleOf()
    extras.putString(EXTRA_FILE_NAME, name)
    setExtras(extras)
}

fun MediaMetadata.getFileName(): String {
    return extras?.getString(EXTRA_FILE_NAME) ?: ""
}

fun MediaMetadata.Builder.setFileSize(size: Long) = apply {
    val extras = build().extras ?: bundleOf()
    extras.putLong(EXTRA_FILE_SIZE, size)
    setExtras(extras)
}

fun MediaMetadata.getFileSize(): Long {
    return extras?.getLong(EXTRA_FILE_SIZE) ?: 0
}

fun MediaMetadata.Builder.setBaseCover(value: String) = apply {
    val extras = build().extras ?: bundleOf()
    extras.putString(EXTRA_BASE_COVER, value)
    setExtras(extras)
}

fun MediaMetadata.getBaseCover(): String? {
    return extras?.getString(EXTRA_BASE_COVER)
}

fun MediaMetadata.Builder.setSourcePlaylistId(value: Long) = apply {
    val extras = build().extras ?: bundleOf()
    extras.putLong(EXTRA_SOURCE_PLAYLIST_ID, value)
    setExtras(extras)
}

fun MediaMetadata.getSourcePlaylistId(): Long {
    return extras?.getLong(EXTRA_SOURCE_PLAYLIST_ID) ?: 0L
}

fun MediaItem.getSourcePlaylistId(): Long {
    return mediaMetadata.getSourcePlaylistId()
}

fun MediaItem.getSmallCover(): String {
    val baseCover = mediaMetadata.getBaseCover()
    return if (isLocal()) {
        baseCover ?: ""
    } else {
        baseCover?.asSmallCover() ?: ""
    }
}

fun MediaItem.getLargeCover(): String {
    val baseCover = mediaMetadata.getBaseCover()
    return if (isLocal()) {
        baseCover ?: ""
    } else {
        baseCover?.asLargeCover() ?: ""
    }
}
