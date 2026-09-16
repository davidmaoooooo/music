package me.wcy.music.appwidget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Created by wangchenyan.top on 2025/9/29.
 */
class MusicAppWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget
        get() = MusicAppWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // 首次添加小组件时不会触发切歌回调，主动加载一次当前歌曲封面
        WidgetRepository.refreshCover()
    }
}