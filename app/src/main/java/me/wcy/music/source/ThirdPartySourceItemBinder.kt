package me.wcy.music.source

import me.wcy.music.databinding.ItemThirdPartySourceBinding
import me.wcy.radapter3.RItemBinder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ThirdPartySourceItemBinder(
    private val listener: OnSourceClickListener
) : RItemBinder<ItemThirdPartySourceBinding, ThirdPartySourceInfo>() {
    private val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onBind(
        viewBinding: ItemThirdPartySourceBinding,
        item: ThirdPartySourceInfo,
        position: Int
    ) {
        viewBinding.tvName.text = item.name.ifBlank { "第三方音源" }
        viewBinding.tvSubTitle.text = buildString {
            append(if (item.enabled) "已启用" else "未启用")
            if (item.importTime > 0) {
                append(" · ")
                append(formatter.format(Date(item.importTime)))
            }
        }
        viewBinding.rbEnabled.isChecked = item.enabled
        viewBinding.root.setOnClickListener {
            listener.onEnable(item)
        }
        viewBinding.rbEnabled.setOnClickListener {
            listener.onEnable(item)
        }
        viewBinding.ivDelete.setOnClickListener {
            listener.onDelete(item)
        }
    }

    interface OnSourceClickListener {
        fun onEnable(item: ThirdPartySourceInfo)
        fun onDelete(item: ThirdPartySourceInfo)
    }
}
