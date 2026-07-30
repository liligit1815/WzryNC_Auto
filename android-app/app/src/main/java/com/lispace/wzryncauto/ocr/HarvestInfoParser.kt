package com.lispace.wzryncauto.ocr

import kotlin.math.roundToInt

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
    private val knownCropNames = setOf(
        "番茄", "洋葱", "小麦", "土豆", "胡萝卜", "白菜", "玉米",
        "南瓜", "草莓", "蓝莓", "西瓜", "辣椒", "茄子", "黄瓜", "大豆",
    )
    private val cropAliases = mapOf(
        "胡萝ト" to "胡萝卜",
        "胡蘿ト" to "胡萝卜",
        "胡萝下" to "胡萝卜",
    )
    private val tenThousandExperiencePatterns = listOf(
        Regex("""(\d+(?:\.\d+)?)\s*万\s*(?:农场)?[经経]验"""),
        Regex("""(?:农场)?[经経]验\s*(\d+(?:\.\d+)?)\s*万"""),
        Regex("""(\d+(?:\.\d+)?)\s*万"""),
    )
    private val experiencePatterns = listOf(
        Regex("""XP\s*[+＋]?\s*(\d+)""", RegexOption.IGNORE_CASE),
        Regex("""(\d+)\s*XP""", RegexOption.IGNORE_CASE),
        Regex("""[+＋]?\s*(\d+)\s*(?:农场)?[经経]验"""),
        Regex("""(?:农场)?[经経]验\s*[+＋]?\s*(\d+)"""),
    )
    private val nonCropTextParts = setOf(
        "恭喜", "获得", "点击", "继续", "农场", "经验", "経験",
        "收获", "奖励", "确定", "关闭",
    )
    // ML Kit occasionally substitutes a Katakana glyph for a visually similar
    // Chinese character (for example 胡萝卜 -> 胡萝ト). Preserve such unknown
    // names instead of dropping their quantities.
    private val plausibleCropName = Regex("""^[\p{IsHan}\p{IsKatakana}]{2,6}$""")

    fun parse(items: List<OcrTextItem>): HarvestInfo? {
        val normalizedItems = items.map {
            it.copy(text = normalizeText(it.text))
        }
        val raw = normalizedItems.joinToString(" ") { it.text }
        val containsExperienceLabel = raw.contains("经验") || raw.contains("経験")
        var experience = tenThousandExperiencePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(raw)?.groupValues?.get(1)?.toDoubleOrNull()
                ?.takeIf { containsExperienceLabel }
                ?.let { (it * 10_000).roundToInt() }
        } ?: experiencePatterns.firstNotNullOfOrNull { pattern ->
            pattern.find(raw)?.groupValues?.get(1)?.toIntOrNull()
        } ?: 0

        val numbers = normalizedItems.mapNotNull { item ->
            item.text.removePrefix("+").toIntOrNull()
                ?.takeIf { it > 0 }
                ?.let {
                    NumberCandidate(
                        x = item.centerX,
                        y = item.centerY,
                        value = it,
                        hasPlusPrefix = item.text.startsWith("+"),
                    )
                }
        }.toMutableList()
        val crops = linkedMapOf<String, Int>()

        // Reserve the number next to the experience label before pairing crop cards.
        // The textual regex above may already have parsed it, but it is still present
        // in the element list and must never be reused as a crop quantity.
        normalizedItems
            .filter(::isExperienceLabel)
            .forEach { label ->
                numbers
                    .filterNot(NumberCandidate::used)
                    .minByOrNull { number ->
                        kotlin.math.abs(label.centerX - number.x) +
                            kotlin.math.abs(label.centerY - number.y)
                    }
                    ?.takeIf { number ->
                        kotlin.math.abs(label.centerX - number.x) +
                            kotlin.math.abs(label.centerY - number.y) <= 400f
                    }
                    ?.used = true
            }

        normalizedItems
            .filter { isCropName(it.text) }
            .sortedBy(OcrTextItem::centerY)
            .forEach { crop ->
                val best = numbers
                    .filterNot(NumberCandidate::used)
                    .filterNot(NumberCandidate::hasPlusPrefix)
                    .mapNotNull { number ->
                        val verticalDistance = crop.centerY - number.y
                        val horizontalDistance = kotlin.math.abs(crop.centerX - number.x)
                        if (verticalDistance in 20f..180f && horizontalDistance <= 260f) {
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

        if (experience == 0) {
            val experienceLabels = normalizedItems.filter {
                it.text.contains("经验") ||
                    it.text.contains("経験") ||
                    it.text.equals("XP", ignoreCase = true)
            }
            experience = experienceLabels
                .flatMap { label ->
                    numbers.filterNot(NumberCandidate::used).map { number ->
                        val distance = kotlin.math.abs(label.centerX - number.x) +
                            kotlin.math.abs(label.centerY - number.y)
                        distance to number
                    }
                }
                .filter { (distance, _) -> distance <= 400f }
                .minByOrNull { it.first }
                ?.second
                ?.also { it.used = true }
                ?.value
                ?: numbers.firstOrNull {
                    !it.used && it.hasPlusPrefix
                }?.also { it.used = true }?.value
                ?: 0
        }

        if (experience == 0 && crops.isEmpty()) return null
        return HarvestInfo(experience, crops, raw)
    }

    private data class NumberCandidate(
        val x: Float,
        val y: Float,
        val value: Int,
        val hasPlusPrefix: Boolean,
        var used: Boolean = false,
    )

    private fun normalizeText(text: String): String {
        val trimmed = text.trim().replace('＋', '+')
        val normalizedExperience =
            Regex("""^[Tt][.,:：]?(\d{2})万$""").matchEntire(trimmed)?.let {
            "7.${it.groupValues[1]}万"
        } ?: trimmed
        return cropAliases[normalizedExperience] ?: normalizedExperience
    }

    private fun isExperienceLabel(item: OcrTextItem): Boolean =
        item.text.contains("经验") ||
            item.text.contains("経験") ||
            item.text.equals("XP", ignoreCase = true)

    /**
     * Crop names are discovered from the harvest-card layout instead of
     * requiring an exhaustive whitelist. The known-name list and aliases are
     * retained only as a high-confidence fast path and OCR correction layer.
     */
    private fun isCropName(text: String): Boolean {
        if (text in knownCropNames) return true
        if (!plausibleCropName.matches(text)) return false
        return nonCropTextParts.none(text::contains)
    }
}
