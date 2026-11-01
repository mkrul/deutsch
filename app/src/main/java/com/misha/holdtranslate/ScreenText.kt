package com.misha.holdtranslate

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

internal data class ScreenText(
    val text: String,
    val bounds: Rect,
    val source: String = text,
)

private val whitespace = Regex("\\s+")

internal fun normalizeScreenText(raw: String): String {
    return raw.replace('\u00A0', ' ')
        .replace(whitespace, " ")
        .trim()
}

internal object LineMerger {
    fun merge(items: List<ScreenText>, wordGapPx: Int): List<ScreenText> {
        return mergeHorizontal(dedupe(items), wordGapPx)
            .sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
    }

    private fun dedupe(items: List<ScreenText>): List<ScreenText> {
        val kept = ArrayList<ScreenText>(items.size)
        for (item in items) {
            val duplicate = kept.any { other ->
                other.text == item.text &&
                    abs(other.bounds.left - item.bounds.left) <= 6 &&
                    abs(other.bounds.top - item.bounds.top) <= 6 &&
                    abs(other.bounds.width() - item.bounds.width()) <= 12
            }
            if (!duplicate) kept.add(item)
        }
        return kept
    }

    private fun mergeHorizontal(items: List<ScreenText>, maxGapPx: Int): List<ScreenText> {
        val sorted = items.sortedWith(compareBy({ it.bounds.top }, { it.bounds.left }))
        val lines = mutableListOf<ScreenText>()
        for (item in sorted) {
            var best = -1
            var bestGap = Int.MAX_VALUE
            for (index in lines.indices) {
                val line = lines[index]
                if (line.text.length + item.text.length > 1400) continue
                if (!sameLine(line.bounds, item.bounds)) continue
                val gap = item.bounds.left - line.bounds.right
                if (gap < -8 || gap > maxGapPx) continue
                if (gap < bestGap) {
                    bestGap = gap
                    best = index
                }
            }
            if (best >= 0) {
                val prev = lines[best]
                val union = Rect(prev.bounds)
                union.union(item.bounds)
                lines[best] = ScreenText(join(prev.text, item.text), union)
            } else {
                lines.add(ScreenText(item.text, Rect(item.bounds)))
            }
        }
        return lines
    }

    private fun sameLine(a: Rect, b: Rect): Boolean {
        val h1 = a.height().coerceAtLeast(1)
        val h2 = b.height().coerceAtLeast(1)
        val taller = max(h1, h2).toFloat()
        val shorter = min(h1, h2).toFloat()
        if (taller / shorter > 2.4f) return false
        val overlap = min(a.bottom, b.bottom) - max(a.top, b.top)
        return overlap >= shorter * 0.45f
    }

    private fun join(left: String, right: String): String {
        if (left.endsWith("-")) return left + right
        val first = right.firstOrNull()
        if (first != null && first in ",.;:!?%)]}") return left + right
        val last = left.lastOrNull()
        if (last == '(' || last == '[') return left + right
        return "$left $right"
    }
}
