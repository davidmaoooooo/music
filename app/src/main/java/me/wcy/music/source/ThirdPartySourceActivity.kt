package me.wcy.music.source

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.wcy.music.R
import me.wcy.music.common.BaseMusicActivity
import me.wcy.music.common.ThemeColor
import top.wangchenyan.common.ext.toast
import top.wangchenyan.common.widget.TitleLayout

class ThirdPartySourceActivity : BaseMusicActivity() {
    private lateinit var listLayout: LinearLayout
    private lateinit var statusView: TextView

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
                    text = "第三方音源只接管歌曲播放链接，歌单、搜索、登录等内容仍使用原 API。\n" +
                        "可同时启用多个音源，播放时按列表顺序依次请求，失败自动回退到下一个。"
                    setTextColor(getColor(R.color.common_text_h2_color))
                    textSize = 13f
                })
                addView(TextView(this@ThirdPartySourceActivity).apply {
                    statusView = this
                    setTextColor(getColor(R.color.common_text_h2_color))
                    textSize = 13f
                    setPadding(0, dp(8), 0, 0)
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
                addView(Button(this@ThirdPartySourceActivity).apply {
                    text = "从链接导入音源"
                    setOnClickListener { showUrlImportDialog() }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                ).apply { topMargin = dp(8) })
                addView(Button(this@ThirdPartySourceActivity).apply {
                    text = "常用音源一键导入"
                    setOnClickListener { showPresetDialog() }
                }, LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(44)
                ).apply { topMargin = dp(8) })
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

    private fun showPresetDialog() {
        val presets = ThirdPartySourcePresets.accelerated
        val labels = presets.map { "${it.name}\n${it.url}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("选择要导入的音源")
            .setItems(labels) { _, which ->
                importFromUrl(presets[which].url)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showUrlImportDialog() {
        val input = EditText(this).apply {
            hint = "https://example.com/lx-music-source.js"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine(true)
            setPadding(32, 24, 32, 24)
        }
        AlertDialog.Builder(this)
            .setTitle("从链接导入音源")
            .setMessage("填写音源脚本的直链地址（需以 http:// 或 https:// 开头）")
            .setView(input)
            .setPositiveButton("导入") { _, _ ->
                importFromUrl(input.text.toString())
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun importFromUrl(url: String) {
        val target = url.trim()
        if (target.isEmpty()) {
            toast("请输入链接")
            return
        }
        if (!target.startsWith("http://") && !target.startsWith("https://")) {
            toast("链接需以 http:// 或 https:// 开头")
            return
        }
        toast("正在下载音源…")
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { ThirdPartySourceStore.importSourceFromUrl(this@ThirdPartySourceActivity, target) }
            }
            result.onSuccess {
                refresh()
                toast("音源已导入并启用")
            }.onFailure {
                toast(it.message ?: "音源导入失败")
            }
        }
    }

    private fun refresh() {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        listLayout.removeAllViews()
        val sources = ThirdPartySourceStore.list()
        updateStatus(sources)
        if (sources.isEmpty()) {
            listLayout.addView(TextView(this).apply {
                text = "暂无音源，请先导入脚本或通过链接导入"
                setTextColor(getColor(R.color.common_text_h2_color))
                gravity = Gravity.CENTER
                setPadding(0, dp(32), 0, 0)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            return
        }
        val priorityId = sources.firstOrNull { it.enabled }?.id
        val enabledOrder = sources.filter { it.enabled }.map { it.id }
        sources.forEach { source ->
            listLayout.addView(
                createSourceRow(source, enabledOrder.indexOf(source.id), priorityId),
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(10) }
            )
        }
    }

    private fun updateStatus(sources: List<ThirdPartySourceInfo>) {
        val enabledCount = sources.count { it.enabled }
        statusView.text = when {
            sources.isEmpty() -> ""
            enabledCount == 0 -> "当前没有启用任何音源"
            else -> "已启用 $enabledCount 个音源，请求顺序：" +
                sources.filter { it.enabled }.joinToString(" → ") { it.name }
        }
    }

    private fun createSourceRow(
        source: ThirdPartySourceInfo,
        enabledIndex: Int,
        priorityId: String?
    ): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(8), dp(10))
            background = getDrawable(R.drawable.bg_card_stroke)

            val checkBox = CheckBox(this@ThirdPartySourceActivity).apply {
                isChecked = source.enabled
                setOnClickListener {
                    ThirdPartySourceStore.setEnabled(source.id, isChecked)
                    refresh()
                }
            }
            addView(checkBox)

            addView(LinearLayout(this@ThirdPartySourceActivity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(this@ThirdPartySourceActivity).apply {
                    text = buildString {
                        if (source.enabled && enabledIndex >= 0) {
                            append("${enabledIndex + 1}. ")
                        }
                        append(source.name.ifBlank { "第三方音源" })
                    }
                    setTextColor(getColor(R.color.common_text_h1_color))
                    textSize = 16f
                })
                addView(TextView(this@ThirdPartySourceActivity).apply {
                    text = buildSourceSubtitle(source)
                    setTextColor(getColor(R.color.common_text_h2_color))
                    textSize = 12f
                })
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            addView(Button(this@ThirdPartySourceActivity).apply {
                text = "优先"
                isEnabled = source.enabled && source.id != priorityId
                setOnClickListener {
                    ThirdPartySourceStore.setPriority(source.id)
                    refresh()
                    toast("已把 ${source.name} 设为优先音源")
                }
            })
            addView(Button(this@ThirdPartySourceActivity).apply {
                text = "删除"
                setOnClickListener { deleteSource(source) }
            })
        }
    }

    private fun buildSourceSubtitle(source: ThirdPartySourceInfo): String {
        return buildString {
            append(if (source.enabled) "已启用" else "未启用")
            source.version?.takeIf { it.isNotBlank() }?.let { append(" · v$it") }
            source.author?.takeIf { it.isNotBlank() }?.let { append(" · $it") }
            if (source.importTime > 0) {
                append(" · ")
                append(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
                    .format(java.util.Date(source.importTime)))
            }
        }
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
