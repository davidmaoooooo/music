package me.wcy.music.listen

import android.content.Context
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.app.Activity
import androidx.appcompat.app.AlertDialog
import androidx.core.view.setPadding
import com.blankj.utilcode.util.SizeUtils
import com.blankj.utilcode.util.ToastUtils
import me.wcy.music.R
import me.wcy.music.common.ThemeColor
import me.wcy.music.storage.preference.ConfigPreferences
import top.wangchenyan.common.ext.getColor

class ListenTogetherDialog(private val context: Context) {
    fun show() {
        if (!ConfigPreferences.listenTogetherEnabled) {
            ToastUtils.showShort("请先在调试设置中开启一起听功能")
            return
        }
        val roomInput = EditText(context).apply {
            hint = "房间号"
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            background = context.getDrawable(R.drawable.bg_dialog_input)
            setPadding(SizeUtils.dp2px(14f), 0, SizeUtils.dp2px(14f), 0)
            setTextColor(context.getColor(R.color.common_text_h1_color))
            setHintTextColor(context.getColor(R.color.common_text_h2_color))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                SizeUtils.dp2px(48f)
            ).apply {
                topMargin = SizeUtils.dp2px(18f)
            }
        }
        val status = TextView(context).apply {
            text = ListenTogetherManager.state.value.statusText
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(context.getColor(R.color.common_text_h2_color))
            textSize = 13f
            setLineSpacing(SizeUtils.dp2px(2f).toFloat(), 1f)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = SizeUtils.dp2px(8f)
            }
        }
        val title = TextView(context).apply {
            text = "一起听"
            setTextColor(context.getColor(R.color.common_text_h1_color))
            textSize = 21f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(R.drawable.bg_dialog_surface)
            setPadding(SizeUtils.dp2px(24f))
            addView(title)
            addView(status)
            addView(roomInput)
        }
        val dialog = AlertDialog.Builder(context)
            .setView(content)
            .show()
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                SizeUtils.dp2px(48f)
            ).apply {
                topMargin = SizeUtils.dp2px(20f)
            }
        }
        val exitButton = createDialogButton("退出", false)
        val joinButton = createDialogButton("加入房间", false)
        val createButton = createDialogButton("创建房间", true)
        buttonRow.addView(exitButton)
        buttonRow.addView(joinButton)
        buttonRow.addView(createButton)
        content.addView(buttonRow)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
        createButton.setOnClickListener {
            (context as? Activity)?.let { ListenTogetherPowerGuide.showIfNeeded(it) }
            ListenTogetherManager.createRoom(
                roomInput.text.toString(),
                ConfigPreferences.resolvedListenTogetherWorkerUrl
            )
            dialog.dismiss()
        }
        joinButton.setOnClickListener {
            (context as? Activity)?.let { ListenTogetherPowerGuide.showIfNeeded(it) }
            ListenTogetherManager.joinRoom(
                roomInput.text.toString(),
                ConfigPreferences.resolvedListenTogetherWorkerUrl
            )
            dialog.dismiss()
        }
        exitButton.setOnClickListener {
            ListenTogetherManager.leave()
            dialog.dismiss()
        }
    }

    private fun createDialogButton(text: String, primary: Boolean): TextView {
        return TextView(context).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 13f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            includeFontPadding = false
            background = context.getDrawable(
                if (primary) R.drawable.bg_dialog_primary_button else R.drawable.bg_dialog_tonal_button
            )
            setTextColor(
                if (primary) context.getColor(R.color.white) else ThemeColor.primary(context)
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.MATCH_PARENT,
                1f
            ).apply {
                marginStart = SizeUtils.dp2px(4f)
                marginEnd = SizeUtils.dp2px(4f)
            }
        }
    }
}
