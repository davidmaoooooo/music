package me.wcy.music.service.likesong

import android.app.Activity
import kotlinx.coroutines.flow.StateFlow
import top.wangchenyan.common.model.CommonResult


/**
 * Created by wangchenyan.top on 2024/3/21.
 */
interface LikeSongProcessor {
    
    fun init()

    fun updateLikeSongList()

    fun isLiked(id: Long): Boolean

    /**
     * 喜欢状态变更通知。
     *
     * 每次成功喜欢/取消喜欢后自增，供系统控制中心（原子随身听等）刷新按钮状态。
     */
    val likeStateVersion: StateFlow<Long>

    suspend fun like(activity: Activity, id: Long): CommonResult<Unit>
}