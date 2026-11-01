package com.misha.holdtranslate

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

internal object TranslationChips {
    fun frame(context: Context, block: ScreenText, sorted: List<ScreenText>, index: Int, screen: Rect): Rect? {
        var left = block.bounds.left
        var top = block.bounds.top
        val minWidth = context.dp(28)
        val margin = context.dp(8)
        if (left < context.dp(4)) left = context.dp(4)
        val lineWidth = oneLineWidth(context, block.text)
        val room = screen.width() - margin - left
        if (lineWidth > room) {
            val shift = (lineWidth - room).coerceAtMost((left - context.dp(4)).coerceAtLeast(0))
            left -= shift
        }
        val maxWidth = (screen.width() - margin - left).coerceAtLeast(minWidth)
        var width = min(lineWidth, maxWidth)
        if (left + width > screen.width() - margin) width = screen.width() - margin - left
        if (width < minWidth || top >= screen.height() - context.dp(8)) return null

        val textHeight = textHeight(context, block.text, width)
        val nextTop = nextTop(context, sorted, index, screen.height())
        val limit = min(nextTop - context.dp(2), block.bounds.top + context.dp(220))
        val desiredBottom = block.bounds.top + textHeight
        var bottom = min(desiredBottom, max(block.bounds.bottom, limit))
        var height = bottom - block.bounds.top
        if (top + height > screen.height() - context.dp(4)) height = screen.height() - context.dp(4) - top
        if (height <= 0) return null
        if (top < 0) {
            height += top
            top = 0
            if (height <= 0) return null
        }
        return Rect(left, top, left + width, top + height)
    }

    fun speakerWidth(context: Context): Int = context.dp(22)

    fun indicatorWidth(context: Context): Int = context.dp(22)

    fun controlHeight(context: Context): Int = context.dp(26)

    fun controlsWidth(context: Context): Int {
        return speakerWidth(context) + context.dp(1) + indicatorWidth(context)
    }

    private fun iconParams(width: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_VERTICAL
        }
    }

    fun create(
        context: Context,
        text: String,
        textWidth: Int,
        expandedHeight: Int,
        onSpeak: (TextView) -> Unit,
        onOpenChanged: (TranslationChip, Boolean) -> Unit,
    ): TranslationChip {
        val measured = measureLabel(context, text, textWidth)
        val label = label(context, text)
        val scroll = ScrollView(context).apply {
            visibility = View.GONE
            isVerticalScrollBarEnabled = measured.lineCount > 1 && measured.height > expandedHeight
            isScrollbarFadingEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            addView(label, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val speaker = TextView(context).apply {
            this.text = context.getString(R.string.speaker)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.cd_speaker)
            isClickable = true
            isFocusable = false
            setOnClickListener { onSpeak(this) }
        }
        val indicator = TextView(context).apply {
            this.text = context.getString(R.string.mark)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            gravity = Gravity.CENTER
            contentDescription = context.getString(R.string.cd_show_english)
            isClickable = true
            isFocusable = false
        }
        val divider = View(context).apply {
            setBackgroundColor(Color.argb(90, 27, 58, 75))
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = GradientDrawable().apply {
                cornerRadius = context.dpF(4f)
                setColor(Color.argb(242, 247, 243, 234))
                setStroke(context.dp(1), Color.argb(90, 27, 58, 75))
            }
            addView(speaker, iconParams(speakerWidth(context)))
            addView(divider, LinearLayout.LayoutParams(context.dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(indicator, iconParams(indicatorWidth(context)))
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        lateinit var chip: TranslationChip
        indicator.setOnClickListener { chip.setOpen(!chip.open) }
        chip = TranslationChip(root, scroll, indicator, onOpenChanged)
        return chip
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
        return label.measuredHeight
    }

    fun measureLabel(context: Context, text: String, width: Int): LabelMeasure {
        val view = label(context, text)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width.coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        return LabelMeasure(view.measuredHeight, view.layout?.lineCount ?: 1)
    }

    private fun label(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            includeFontPadding = false
            setPadding(context.dp(6), context.dp(3), context.dp(10), context.dp(3))
            setLineSpacing(0f, 1f)
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

internal class LabelMeasure(
    val height: Int,
    val lineCount: Int,
)

internal class TranslationChip(
    val view: LinearLayout,
    private val scroll: View,
    private val indicator: TextView,
    private val onOpenChanged: (TranslationChip, Boolean) -> Unit,
) {
    var open: Boolean = false
        private set

    fun setOpen(next: Boolean) {
        if (open == next) return
        open = next
        scroll.visibility = if (next) View.VISIBLE else View.GONE
        indicator.contentDescription = indicator.context.getString(
            if (next) R.string.cd_hide_english else R.string.cd_show_english,
        )
        onOpenChanged(this, next)
    }
}

internal data class PlacedTranslation(
    val block: ScreenText,
    val window: Rect,
    val textWidth: Int,
    val collapsedWidth: Int,
    val collapsedHeight: Int,
)

internal object TranslationPages {
    fun layout(context: Context, items: List<ScreenText>, screen: Rect): List<List<PlacedTranslation>> {
        val sorted = items.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val controls = TranslationChips.controlsWidth(context)
        val controlHeight = TranslationChips.controlHeight(context)
        val edge = context.dp(4)
        val placed = ArrayList<PlacedTranslation>(sorted.size)
        for (index in sorted.indices) {
            val block = sorted[index]
            val frame = TranslationChips.frame(context, block, sorted, index, screen) ?: continue
            var left = frame.left - controls
            var textWidth = frame.width()
            if (left < edge) left = edge
            val maxWidth = screen.width() - edge - left
            if (controls + textWidth > maxWidth) {
                textWidth = (maxWidth - controls).coerceAtLeast(context.dp(28))
            }
            val expandedWidth = controls + textWidth
            val label = TranslationChips.measureLabel(context, block.text, textWidth)
            var chipTop = frame.top
            var chipHeight = frame.height()
            if (label.lineCount <= 1) {
                chipHeight = label.height
                val bottomLimit = screen.height() - edge
                if (chipTop + chipHeight > bottomLimit) chipTop = (bottomLimit - chipHeight).coerceAtLeast(0)
                if (chipTop + chipHeight > bottomLimit) chipHeight = bottomLimit - chipTop
            }
            if (chipHeight <= 0) continue
            placed.add(
                PlacedTranslation(
                    block,
                    Rect(left, chipTop, left + expandedWidth, chipTop + chipHeight),
                    textWidth,
                    controls.coerceAtMost(expandedWidth),
                    controlHeight.coerceAtMost(chipHeight),
                ),
            )
        }
        return paginate(placed)
    }

    private fun paginate(placed: List<PlacedTranslation>): List<List<PlacedTranslation>> {
        val pages = ArrayList<MutableList<PlacedTranslation>>()
        for (chip in placed) {
            val page = pages.firstOrNull { group ->
                group.none { overlaps(it.window, chip.window) }
            }
            if (page != null) {
                page.add(chip)
            } else {
                pages.add(mutableListOf(chip))
            }
        }
        return pages
    }

    private fun overlaps(a: Rect, b: Rect): Boolean {
        return a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
    }
}
