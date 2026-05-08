package me.wcy.music.common.bean

import com.google.gson.annotations.SerializedName
import me.wcy.music.account.bean.ProfileData

data class CommentListData(
    @SerializedName("code")
    val code: Int = 0,
    @SerializedName("hotComments")
    val hotComments: List<CommentData> = emptyList(),
    @SerializedName("comments")
    val comments: List<CommentData> = emptyList(),
    @SerializedName("more")
    val more: Boolean = false,
    @SerializedName("total")
    val total: Int = 0
)

data class CommentData(
    @SerializedName("user")
    val user: ProfileData = ProfileData(),
    @SerializedName("content")
    val content: String = "",
    @SerializedName("likedCount")
    val likedCount: Int = 0,
    @SerializedName("time")
    val time: Long = 0
)
