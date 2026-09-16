package me.wcy.music.source

import android.content.DialogInterface
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

    /** 本地文件导入：支持一次选择多个音源脚本。 */
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
        }
        importFromUris(uris)
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
                    text = "从本地导入音源（可多选）"
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
                    text = "批量选择导入"
                    setOnClickListener { showBatchImportDialog() }
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

    /** 批量选择导入：勾选多个常用音源一次导入。 */
    private fun showBatchImportDialog() {
        val presets = ThirdPartySourcePresets.accelerated
        val layout = layoutInflater.inflate(R.layout.dialog_source_batch_import, null)
        val listLayout = layout.findViewById<LinearLayout>(R.id.llPresetList)
        val checkBoxes = mutableListOf<Pair<CheckBox, ThirdPartySourcePresets.Preset>>()
        presets.forEach { preset ->
            val checkBox = CheckBox(this).apply {
                text = preset.name
                textSize = 15f
                setTextColor(getColor(R.color.common_text_h1_color))
                setPadding(24, 18, 24, 18)
                isChecked = true
            }
            listLayout.addView(checkBox, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
            checkBoxes += checkBox to preset
        }
        AlertDialog.Builder(this)
            .setTitle("批量导入音源")
            .setView(layout)
            .setPositiveButton("导入选中") { _, _ ->
                val selected = checkBoxes.filter { it.first.isChecked }.map { it.second }
                if (selected.isEmpty()) {
                    toast("请至少选择一个音源")
                } else {
                    importFromUrls(selected.map { it.url })
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 链接导入：支持一次粘贴多个链接。 */
    private fun showUrlImportDialog() {
        val layout = layoutInflater.inflate(R.layout.dialog_source_url_import, null)
        val input = layout.findViewById<EditText>(R.id.etSourceUrl)
        val dialog = AlertDialog.Builder(this)
            .setTitle("从链接导入音源")
            .setView(layout)
            .setPositiveButton("导入", null)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener {
                val urls = input.text.toString()
                    .split('\n', ' ', ',', ';')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                if (urls.isEmpty()) {
                    toast("请输入链接")
                    return@setOnClickListener
                }
                dialog.dismiss()
                importFromUrls(urls)
            }
        }
        dialog.show()
    }

    /** 本地文件批量导入，导入后同样用付费歌曲自检。 */
    private fun importFromUris(uris: List<Uri>) {
        toast("正在导入并测试 ${uris.size} 个音源…")
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                uris.map { uri -> importSingleUri(uri) }
            }
            refresh()
            showImportResults(results)
        }
    }

    private fun importSingleUri(uri: Uri): ImportOutcome {
        val imported = runCatching {
            ThirdPartySourceStore.importSource(this, uri)
        }
        val source = imported.getOrNull()
        if (source == null) {
            return ImportOutcome(
                url = uri.lastPathSegment.orEmpty(),
                name = uri.lastPathSegment.orEmpty().ifBlank { "本地音源" },
                imported = false,
                error = imported.exceptionOrNull()?.message ?: "导入失败",
                verifyMessage = null
            )
        }
        val verified = runCatching { ThirdPartySourceVerifier.verify(source) }
        return ImportOutcome(
            url = uri.lastPathSegment.orEmpty(),
            name = source.name,
            imported = true,
            error = null,
            verifyMessage = verified.getOrNull()?.let { result ->
                if (result.ok) "可用" else result.message
            } ?: "自检异常"
        )
    }

    private fun importFromUrls(urls: List<String>) {
        val invalid = urls.filterNot { it.startsWith("http://") || it.startsWith("https://") }
        if (invalid.isNotEmpty()) {
            toast("链接需以 http:// 或 https:// 开头")
            return
        }
        toast("正在下载并测试 ${urls.size} 个音源…")
        lifecycleScope.launch {
            val results = withContext(Dispatchers.IO) {
                urls.map { url -> importSingle(url) }
            }
            refresh()
            showImportResults(results)
        }
    }

    private data class ImportOutcome(
        val url: String,
        val name: String,
        val imported: Boolean,
        val error: String?,
        val verifyMessage: String?
    )

    private fun importSingle(url: String): ImportOutcome {
        val imported = runCatching {
            ThirdPartySourceStore.importSourceFromUrl(this, url)
        }
        val source = imported.getOrNull()
        if (source == null) {
            return ImportOutcome(
                url = url,
                name = ThirdPartySourceDownloader.nameFromUrl(url),
                imported = false,
                error = imported.exceptionOrNull()?.message ?: "导入失败",
                verifyMessage = null
            )
        }
        // 导入后立刻用固定付费歌曲自检，判断音源是否可用
        val verified = runCatching { ThirdPartySourceVerifier.verify(source) }
        return ImportOutcome(
            url = url,
            name = source.name,
            imported = true,
            error = null,
            verifyMessage = verified.getOrNull()?.let { result ->
                if (result.ok) "可用" else result.message
            } ?: "自检异常"
        )
    }

    private fun showImportResults(results: List<ImportOutcome>) {
        val message = results.joinToString("\n\n") { item ->
            buildString {
                append(item.name)
                if (!item.imported) {
                    append("\n  导入失败：")
                    append(item.error)
                } else {
                    append("\n  自检：")
                    append(item.verifyMessage)
                }
            }
        }
        val anyFailed = results.any { !it.imported || it.verifyMessage != "可用" }
        AlertDialog.Builder(this)
            .setTitle("导入结果")
            .setMessage(message)
            .setPositiveButton("知道了", null)
            .show()
        if (anyFailed) {
            toast("部分音源不可用，详见导入结果")
        } else {
            toast("音源已导入并启用")
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
