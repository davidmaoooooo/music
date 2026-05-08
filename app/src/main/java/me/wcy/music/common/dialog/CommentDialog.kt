package me.wcy.music.common.dialog

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import com.blankj.utilcode.util.SizeUtils
import me.wcy.music.common.bean.CommentData

object CommentDialog {
    private const val CONTENT_TAG = "comment_content"

    fun showLoading(context: Context): AlertDialog {
        val content = FrameLayout(context).apply {
            tag = CONTENT_TAG
            setPadding(0, dp(6f), 0, 0)
            addView(TextView(context).apply {
                text = "加载中..."
                textSize = 14f
                setTextColor(textColor(context))
                setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
            })
        }
        return AlertDialog.Builder(context)
            .setTitle("评论")
            .setView(content)
            .setPositiveButton("关闭", null)
            .show()
    }

    fun setComments(dialog: AlertDialog, comments: List<CommentData>) {
        val content = dialog.findViewById<FrameLayout>(android.R.id.content)
            ?.findViewWithTag<FrameLayout>(CONTENT_TAG)
            ?: return
        content.removeAllViews()
        if (comments.isEmpty()) {
            content.addView(TextView(dialog.context).apply {
                text = "暂无评论"
                textSize = 14f
                setTextColor(textColor(dialog.context))
                setPadding(dp(20f), dp(8f), dp(20f), dp(8f))
            })
            return
        }
        val context = dialog.context
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20f), dp(4f), dp(20f), dp(8f))
        }
        comments.forEachIndexed { index, comment ->
            if (index > 0) {
                container.addView(View(context).apply {
                    setBackgroundColor(0x1F000000)
                    if (isNightMode(context)) {
                        setBackgroundColor(0x33FFFFFF)
                    }
                }, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    1
                ).apply {
                    topMargin = dp(12f)
                    bottomMargin = dp(12f)
                })
            }
            container.addView(createCommentView(context, comment))
        }
        content.addView(ScrollView(context).apply { addView(container) })
    }

    private fun createCommentView(context: Context, comment: CommentData): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                orientation = LinearLayout.HORIZONTAL
                addView(TextView(context).apply {
                    text = comment.user.nickname
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(primaryTextColor(context))
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(context).apply {
                    text = "${comment.likedCount}赞"
                    textSize = 12f
                    setTextColor(secondaryTextColor(context))
                    gravity = Gravity.END
                })
            })
            addView(TextView(context).apply {
                text = comment.content
                textSize = 14f
                setTextColor(textColor(context))
                setLineSpacing(dp(2f).toFloat(), 1.0f)
            }, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(6f)
            })
        }
    }

    private fun dp(value: Float): Int {
        return SizeUtils.dp2px(value)
    }

    private fun isNightMode(context: Context): Boolean {
        val flags = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        if (flags == Configuration.UI_MODE_NIGHT_YES) return true
        return AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
    }

    private fun primaryTextColor(context: Context): Int {
        return if (isNightMode(context)) 0xF2FFFFFF.toInt() else 0xDE000000.toInt()
    }

    private fun textColor(context: Context): Int {
        return if (isNightMode(context)) 0xD9FFFFFF.toInt() else 0xB3000000.toInt()
    }

    private fun secondaryTextColor(context: Context): Int {
        return if (isNightMode(context)) 0x99FFFFFF.toInt() else 0x8A000000.toInt()
    }
}
