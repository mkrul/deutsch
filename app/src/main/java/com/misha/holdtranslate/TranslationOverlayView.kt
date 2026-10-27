package com.misha.holdtranslate

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

internal object TranslationChips {
    fun frame(context: Context, block: ScreenText, sorted: List<ScreenText>, index: Int, screen: Rect): Rect? {
        var left = block.bounds.left
        var top = block.bounds.top
        var width = block.bounds.width()
        val minWidth = context.dp(28)
        val margin = context.dp(8)
        if (width < minWidth) {
            left -= (minWidth - width) / 2
            width = minWidth
        }
        if (left < context.dp(4)) left = context.dp(4)
        val lineWidth = oneLineWidth(context, block.text)
        val room = screen.width() - margin - left
        if (lineWidth > room) {
            val shift = (lineWidth - room).coerceAtMost((left - context.dp(4)).coerceAtLeast(0))
            left -= shift
        }
        val maxWidth = (screen.width() - margin - left).coerceAtLeast(minWidth)
        width = max(width, min(lineWidth, maxWidth))
        if (left + width > screen.width() - margin) width = screen.width() - margin - left
        if (width < minWidth || top >= screen.height() - context.dp(8)) return null

        val textHeight = textHeight(context, block.text, width)
        val nextTop = nextTop(context, sorted, index, screen.height())
        val limit = min(nextTop - context.dp(2), block.bounds.top + context.dp(220))
        val desiredBottom = block.bounds.top + textHeight
        var bottom = min(desiredBottom, max(block.bounds.bottom, limit))
        var height = bottom - block.bounds.top
        if (top + height > screen.height() - context.dp(4)) height = screen.height() - context.dp(4) - top
        if (height < context.dp(18)) height = context.dp(18)
        if (top + height > screen.height() - context.dp(4)) height = screen.height() - context.dp(4) - top
        if (height < context.dp(18)) return null
        if (top < 0) {
            height += top
            top = 0
            if (height < context.dp(18)) return null
        }
        return Rect(left, top, left + width, top + height)
    }

    fun create(context: Context, text: String, width: Int, height: Int): ScrollView {
        val contentHeight = textHeight(context, text, width)
        val label = label(context, text)
        return ScrollView(context).apply {
            isVerticalScrollBarEnabled = contentHeight > height
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                cornerRadius = context.dpF(4f)
                setColor(Color.argb(242, 247, 243, 234))
                setStroke(context.dp(1), Color.argb(90, 27, 58, 75))
            }
            addView(label, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    private fun oneLineWidth(context: Context, text: String): Int {
        val label = label(context, text)
        label.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return label.measuredWidth.coerceAtLeast(context.dp(28))
    }

    private fun textHeight(context: Context, text: String, width: Int): Int {
        val label = label(context, text)
        label.measure(
            View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return label.measuredHeight.coerceAtLeast(context.dp(18))
    }

    private fun label(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(context.dp(6), context.dp(4), context.dp(10), context.dp(4))
            setLineSpacing(0f, 1.05f)
            breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
            hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun nextTop(context: Context, sorted: List<ScreenText>, index: Int, screenBottom: Int): Int {
        val block = sorted[index]
        var limit = screenBottom
        for (otherIndex in sorted.indices) {
            if (otherIndex == index) continue
            val other = sorted[otherIndex]
            if (other.bounds.top <= block.bounds.top) continue
            val overlap = min(block.bounds.right, other.bounds.right) - max(block.bounds.left, other.bounds.left)
            if (overlap < context.dp(8)) continue
            if (other.bounds.top < limit) limit = other.bounds.top
        }
        return limit
    }
}
