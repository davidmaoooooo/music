package me.wcy.music.comment

import me.wcy.music.common.bean.CommentData
import me.wcy.music.databinding.ItemCommentBinding
import me.wcy.radapter3.RItemBinder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CommentItemBinder : RItemBinder<ItemCommentBinding, CommentData>() {
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

    override fun onBind(viewBinding: ItemCommentBinding, item: CommentData, position: Int) {
        viewBinding.tvUser.text = item.user.nickname.ifEmpty { "网易云用户" }
        viewBinding.tvContent.text = item.content
        viewBinding.tvLike.text = "${item.likedCount}赞"
        viewBinding.tvTime.text = if (item.time > 0) {
            dateFormat.format(Date(item.time))
        } else {
            ""
        }
    }
}
