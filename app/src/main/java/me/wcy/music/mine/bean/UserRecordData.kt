package me.wcy.music.mine.bean

import com.google.gson.annotations.SerializedName
import me.wcy.music.common.bean.SongData

data class UserRecordListData(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("weekData")
    val weekData: List<UserRecordItemData> = emptyList(),
    @SerializedName("allData")
    val allData: List<UserRecordItemData> = emptyList()
)

data class UserRecordItemData(
    @SerializedName("song")
    val song: SongData = SongData(),
    @SerializedName("playCount")
    val playCount: Int = 0,
    @SerializedName("score")
    val score: Int = 0
)
