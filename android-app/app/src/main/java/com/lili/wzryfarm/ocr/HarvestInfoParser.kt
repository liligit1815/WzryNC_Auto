package com.lili.wzryfarm.ocr

data class OcrTextItem(
    val text: String,
    val centerX: Float,
    val centerY: Float,
)

data class HarvestInfo(
    val experience: Int,
    val crops: Map<String, Int>,
    val rawText: String,
)

object HarvestInfoParser {
    private val cropNames = setOf(
        "番茄", "洋葱", "小麦", "土豆", "胡萝卜", "白菜", "玉米",
        "南瓜", "草莓", "西瓜", "辣椒", "茄子", "黄瓜", "大豆",
    )
    private val experiencePatterns = listOf(
        Regex("""XP\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)\s*XP""", RegexOption.IGNORE_CASE),
        Regex("""[+＋](\d+)\s*[经経]验"""),
        Regex("""[经経]验\s*[+＋](\d+)"""),
    )

    fun parse(items: List<OcrTextItem>): HarvestInfo? {
        val normalizedItems = items.map {
            it.copy(text = it.text.trim().replace('＋', '+'))
        }
        val raw = normalizedItems.joinToString(" ") { it.text }
        val experience = experiencePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0

        val numbers = normalizedItems.mapNotNull { item ->
            item.text.removePrefix("+").toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let { NumberCandidate(item.centerX, item.centerY, it) }
        }.toMutableList()
        val crops = linkedMapOf<String, Int>()

        normalizedItems
            .filter { it.text in cropNames }
            .sortedBy(OcrTextItem::centerY)
            .forEach { crop ->
                val best = numbers
                    .filterNot(NumberCandidate::used)
                    .mapNotNull { number ->
                        val verticalDistance = crop.centerY - number.y
                        val horizontalDistance = kotlin.math.abs(crop.centerX - number.x)
                        if (verticalDistance in 20f..180f && horizontalDistance <= 140f) {
                            Pair(horizontalDistance + verticalDistance * 0.15f, number)
                        } else {
                            null
                        }
                    }
                    .minByOrNull { it.first }
                    ?.second
                    ?: return@forEach
                best.used = true
                crops[crop.text] = (crops[crop.text] ?: 0) + best.value
            }

        if (experience == 0 && crops.isEmpty()) return null
        return HarvestInfo(experience, crops, raw)
    }

    private data class NumberCandidate(
        val x: Float,
        val y: Float,
        val value: Int,
        var used: Boolean = false,
    )
}
