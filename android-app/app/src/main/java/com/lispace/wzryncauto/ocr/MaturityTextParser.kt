package com.lispace.wzryncauto.ocr

import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

sealed interface MaturityReading {
    val rawText: String

    data class Time(
        val hour: Int,
        val minute: Int,
        val tomorrowHint: Boolean,
        override val rawText: String,
        val dayOffset: Int = if (tomorrowHint) 1 else 0,
        val relativeMinutes: Int? = null,
        val observedAt: LocalDateTime? = null,
    ) : MaturityReading

    data class Mature(override val rawText: String) : MaturityReading

    data class Unrecognized(
        override val rawText: String,
        val reason: String,
    ) : MaturityReading
}

object MaturityTextParser {
    private val absoluteTimePattern = Regex(
        """(?:(今天|明天|后天)\s*)?(\d{1,2})\s*(?:[:：点])\s*(\d{1,2})(?:\s*分)?""",
    )
    private val relativeTimePattern = Regex(
        """(?:(\d+)\s*(?:小时|时))?\s*(?:(\d+)\s*(?:分钟|分))?\s*后\s*成熟""",
    )

    fun parse(
        rawText: String,
        observedAt: LocalDateTime = LocalDateTime.now(),
    ): MaturityReading {
        val normalized = normalize(rawText)
        if ("可收获" in normalized || "已成熟" in normalized) {
            return MaturityReading.Mature(rawText)
        }

        relativeTimePattern.find(normalized)?.let { match ->
            val hours = match.groupValues[1].toIntOrNull() ?: 0
            val minutes = match.groupValues[2].toIntOrNull() ?: 0
            val totalMinutes = hours * 60 + minutes
            if (totalMinutes > 0) {
                val target = observedAt.plusMinutes(totalMinutes.toLong())
                val dayOffset = ChronoUnit.DAYS.between(
                    observedAt.toLocalDate(),
                    target.toLocalDate(),
                ).toInt()
                return MaturityReading.Time(
                    hour = target.hour,
                    minute = target.minute,
                    tomorrowHint = dayOffset > 0,
                    rawText = rawText,
                    dayOffset = dayOffset,
                    relativeMinutes = totalMinutes,
                    observedAt = observedAt,
                )
            }
        }

        val match = absoluteTimePattern.findAll(normalized).firstOrNull { candidate ->
            val contextStart = (candidate.range.first - MATURITY_CONTEXT_CHARS).coerceAtLeast(0)
            val contextEnd = (candidate.range.last + MATURITY_CONTEXT_CHARS + 1)
                .coerceAtMost(normalized.length)
            "成熟" in normalized.substring(contextStart, contextEnd)
        }
            ?: return MaturityReading.Unrecognized(rawText, "未找到成熟时间")
        val dayText = match.groupValues[1]
        val hour = match.groupValues[2].toIntOrNull()
        val minute = match.groupValues[3].toIntOrNull()
        if (hour == null || minute == null || hour !in 0..23 || minute !in 0..59) {
            return MaturityReading.Unrecognized(rawText, "时间超出有效范围")
        }
        val dayOffset = when (dayText) {
            "明天" -> 1
            "后天" -> 2
            else -> 0
        }
        return MaturityReading.Time(
            hour = hour,
            minute = minute,
            tomorrowHint = dayOffset > 0,
            rawText = rawText,
            dayOffset = dayOffset,
            observedAt = observedAt,
        )
    }

    internal fun normalize(value: String): String =
        value
            .replace('\n', ' ')
            .replace('Ｏ', '0')
            .replace('O', '0')
            .replace('o', '0')
            .replace(Regex("""\s+"""), " ")
            .trim()

    private const val MATURITY_CONTEXT_CHARS = 8
}
