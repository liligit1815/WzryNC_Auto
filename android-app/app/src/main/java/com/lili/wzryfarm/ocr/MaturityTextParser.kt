package com.lili.wzryfarm.ocr

sealed interface MaturityReading {
    val rawText: String

    data class Time(
        val hour: Int,
        val minute: Int,
        val tomorrowHint: Boolean,
        override val rawText: String,
    ) : MaturityReading

    data class Mature(override val rawText: String) : MaturityReading

    data class Unrecognized(
        override val rawText: String,
        val reason: String,
    ) : MaturityReading
}

object MaturityTextParser {
    private val timePattern = Regex(
        """(?:明天)?(\d{1,2})\s*(?:[:：点])\s*(\d{1,2})(?:\s*分)?""",
    )

    fun parse(rawText: String): MaturityReading {
        val normalized = normalize(rawText)
        if ("可收获" in normalized || "已成熟" in normalized) {
            return MaturityReading.Mature(rawText)
        }
        val match = timePattern.find(normalized)
            ?: return MaturityReading.Unrecognized(rawText, "未找到成熟时间")
        val hour = match.groupValues[1].toIntOrNull()
        val minute = match.groupValues[2].toIntOrNull()
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            return MaturityReading.Unrecognized(rawText, "时间超出有效范围")
        }
        return MaturityReading.Time(
            hour = hour,
            minute = minute,
            tomorrowHint = "明天" in normalized,
            rawText = rawText,
        )
    }

    private fun normalize(value: String): String =
        value
            .replace('\n', ' ')
            .replace('Ｏ', '0')
            .replace('O', '0')
            .replace('o', '0')
            .replace(Regex("""\s+"""), " ")
            .trim()
}
