package me.wcy.music.source

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import me.wcy.music.R
import me.wcy.music.common.BaseMusicActivity
import me.wcy.music.common.ThemeColor
import top.wangchenyan.common.ext.toast
import top.wangchenyan.common.widget.TitleLayout

class ThirdPartySourceActivity : BaseMusicActivity() {
    private lateinit var listLayout: LinearLayout

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
        runCatching {
            ThirdPartySourceStore.importSource(this, uri)
        }.onSuccess {
            refresh()
            toast("音源已导入并启用")
        }.onFailure {
            toast(it.message ?: "音源导入失败")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())
        refresh()
    }

    override fun onResume() {
        super.onResume()
        if (::listLayout.isInitialized) refresh()
    }

    private fun createContentView(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.common_background_color))
            addView(TitleLayout(this@ThirdPartySourceActivity).apply {
                setTitleText("音源管理")
                setBackgroundColor(ThemeColor.primary(this@ThirdPartySourceActivity))
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            addView(LinearLayout(this@ThirdPartySourceActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(12))
                addView(TextView(this@ThirdPartySourceActivity).apply {
                    text = "第三方音源只接管歌曲播放链接，歌单、搜索、登录等内容仍使用原 API。"
                    setTextColor(getColor(R.color.common_text_h2_color))
                    textSize = 13f
                })
                addView(Button(this@ThirdPartySourceActivity).apply {
                    text = "导入音源脚本"
                    setOnClickListener {
                        importLauncher.launch(arrayOf("text/*", "application/javascript", "*/*"))
                    }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                ).apply { topMargin = dp(12) })
            })
            addView(ScrollView(this@ThirdPartySourceActivity).apply {
                listLayout = LinearLayout(this@ThirdPartySourceActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), 0, dp(16), dp(16))
                }
                addView(listLayout)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            ))
        }
    }

    private fun refresh() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        listLayout.removeAllViews()
        val sources = ThirdPartySourceStore.list()
        if (sources.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "暂无音源，请先导入脚本"
                setTextColor(getColor(R.color.common_text_h2_color))
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return
        }
        sources.forEach { source ->
            listLayout.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
                background = getDrawable(R.drawable.bg_card_stroke)
                val radio = RadioButton(this@ThirdPartySourceActivity).apply {
                    isChecked = source.enabled
                    setOnClickListener { enableSource(source) }
                }
                addView(radio)
                addView(LinearLayout(this@ThirdPartySourceActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(TextView(this@ThirdPartySourceActivity).apply {
                        text = source.name.ifBlank { "第三方音源" }
                        setTextColor(getColor(R.color.common_text_h1_color))
                        textSize = 16f
                    })
                    addView(TextView(this@ThirdPartySourceActivity).apply {
                        text = if (source.enabled) "已启用" else "未启用"
                        setTextColor(getColor(R.color.common_text_h2_color))
                        textSize = 12f
                    })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(this@ThirdPartySourceActivity).apply {
                    text = "删除"
                    setOnClickListener { deleteSource(source) }
                })
                setOnClickListener { enableSource(source) }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) })
        }
    }

    private fun enableSource(source: ThirdPartySourceInfo) {
        ThirdPartySourceStore.setEnabled(source.id)
        refresh()
        toast("已启用 ${source.name}")
    }

    private fun deleteSource(source: ThirdPartySourceInfo) {
        AlertDialog.Builder(this)
            .setTitle("删除音源")
            .setMessage("确认删除 ${source.name}？")
            .setPositiveButton("删除") { _, _ ->
                ThirdPartySourceStore.remove(this, source.id)
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
