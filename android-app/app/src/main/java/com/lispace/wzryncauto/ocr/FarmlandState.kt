package com.lispace.wzryncauto.ocr

import java.time.LocalDateTime

data class FarmlandCardEvidence(
    val normalizedText: String,
    val tokens: List<String>,
    val progress: String?,
) {
    companion object {
        private val progressPattern = Regex("""\d+\s*/\s*\d+""")

        fun from(rawText: String): FarmlandCardEvidence {
            val normalized = MaturityTextParser.normalize(rawText)
            return FarmlandCardEvidence(
                normalizedText = normalized,
                tokens = normalized.split(' ').filter(String::isNotBlank),
                progress = progressPattern.find(normalized)?.value?.replace(" ", ""),
            )
        }
    }
}

sealed interface FarmlandState {
    val rawText: String

    data class Planted(
        val maturity: MaturityReading.Time,
        val evidence: FarmlandCardEvidence = FarmlandCardEvidence.from(maturity.rawText),
    ) : FarmlandState {
        override val rawText: String = maturity.rawText
    }

    data class Mature(
        val maturity: MaturityReading.Mature,
    ) : FarmlandState {
        override val rawText: String = maturity.rawText
    }

    data class Empty(
        val level: Int?,
        override val rawText: String,
    ) : FarmlandState

    data class Unknown(
        override val rawText: String,
        val reason: String,
    ) : FarmlandState
}

object FarmlandStateParser {
    private val emptyFarmlandPattern = Regex("""农\s*田(?:\s*(\d+)\s*级)?""")

    fun parse(
        rawText: String,
        observedAt: LocalDateTime = LocalDateTime.now(),
    ): FarmlandState = parse(
        rawText = rawText,
        maturity = MaturityTextParser.parse(rawText, observedAt),
    )

    fun parse(
        rawText: String,
        maturity: MaturityReading,
    ): FarmlandState {
        val normalized = MaturityTextParser.normalize(rawText)
        emptyFarmlandPattern.find(normalized)?.let { match ->
            return FarmlandState.Empty(
                level = match.groupValues[1].toIntOrNull(),
                rawText = rawText,
            )
        }
        return when (maturity) {
            is MaturityReading.Time -> FarmlandState.Planted(maturity)
            is MaturityReading.Mature -> FarmlandState.Mature(maturity)
            is MaturityReading.Unrecognized -> FarmlandState.Unknown(
                rawText = rawText,
                reason = maturity.reason,
            )
        }
    }
}
