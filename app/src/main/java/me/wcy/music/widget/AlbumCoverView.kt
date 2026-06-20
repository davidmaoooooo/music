package me.wcy.music.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.blankj.utilcode.util.SizeUtils
import me.wcy.music.R
import kotlin.math.max
import kotlin.math.min

/**
 * Material 风格专辑封面。
 *
 * 旧版黑胶唱片视图绘制了唱片、指针和旋转动画，切换到歌词时容易和背景截图遮罩叠加造成卡顿。
 * 这里保留对 PlayingActivity 的公开方法，内部只绘制稳定的封面卡片。
 */
class AlbumCoverView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(getContext(), R.color.md_surface_variant)
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x22000000
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = SizeUtils.dp2px(1f).toFloat()
        color = ContextCompat.getColor(getContext(), R.color.md_outline)
    }
    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val shaderMatrix = Matrix()
    private val coverRect = RectF()

    private var coverBitmap: Bitmap? = null
    private val cornerRadius = SizeUtils.dp2px(8f).toFloat()
    private val shadowOffset = SizeUtils.dp2px(8f).toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        updateCoverRect()

        val shadowRect = RectF(coverRect).apply {
            offset(0f, shadowOffset / 2f)
        }
        canvas.drawRoundRect(shadowRect, cornerRadius, cornerRadius, shadowPaint)
        canvas.drawRoundRect(coverRect, cornerRadius, cornerRadius, surfacePaint)

        coverBitmap?.takeIf { !it.isRecycled }?.let { bitmap ->
            bitmapPaint.shader = BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                shaderMatrix.reset()
                val scale = max(
                    coverRect.width() / bitmap.width.toFloat(),
                    coverRect.height() / bitmap.height.toFloat()
                )
                val dx = coverRect.left + (coverRect.width() - bitmap.width * scale) / 2f
                val dy = coverRect.top + (coverRect.height() - bitmap.height * scale) / 2f
                shaderMatrix.setScale(scale, scale)
                shaderMatrix.postTranslate(dx, dy)
                setLocalMatrix(shaderMatrix)
            }
            canvas.drawRoundRect(coverRect, cornerRadius, cornerRadius, bitmapPaint)
            bitmapPaint.shader = null
        }

        canvas.drawRoundRect(coverRect, cornerRadius, cornerRadius, strokePaint)
    }

    private fun updateCoverRect() {
        val maxSize = SizeUtils.dp2px(320f).toFloat()
        val size = min(min(width, height).toFloat() * 0.86f, maxSize)
        val left = (width - size) / 2f
        val top = (height - size) / 2f
        coverRect.set(left, top, left + size, top + size)
    }

    fun initNeedle(isPlaying: Boolean) = Unit

    fun setCoverBitmap(bitmap: Bitmap) {
        coverBitmap = bitmap
        invalidate()
    }

    fun start() = Unit

    fun pause() = Unit

    fun reset() = Unit
}
