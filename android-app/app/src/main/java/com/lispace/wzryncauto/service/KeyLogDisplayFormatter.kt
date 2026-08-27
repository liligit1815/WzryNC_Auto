package com.lispace.wzryncauto.service

internal data class PersistedRuntimeLogEntry(
    val time: String,
    val message: String,
)

internal data class RoundLogEvent(
    val round: Int,
    val time: String,
    val message: String,
)

/** Formats the compact, user-facing two-line summary for each farm round. */
internal object KeyLogDisplayFormatter {
    private val roundStart = Regex("""第\s*(\d+)\s*轮开始""")
    private val persistedLine = Regex(
        """^\d{4}-\d{2}-\d{2}\s+(\d{2}:\d{2}:\d{2})\.\d{3}\s+(.+)$""",
    )

    fun roundFrom(message: String): Int? =
        roundStart.find(message)?.groupValues?.get(1)?.toIntOrNull()

    fun isVisible(message: String): Boolean =
        roundFrom(message) != null ||
            message == "执行一键务农" ||
            message.startsWith("本轮类型：") ||
            message.startsWith("成熟时间：") ||
            message.startsWith("下次操作时间：") ||
            message.startsWith("自动化失败：") ||
            message.startsWith("异常：")

    fun render(events: List<RoundLogEvent>): String {
        val summaries = linkedMapOf<Int, RoundSummary>()
        val standaloneFailures = mutableListOf<String>()
        events.forEach { event ->
            if (event.round <= 0) {
                if (isFailure(event.message)) {
                    standaloneFailures += "${event.time}：${event.message}"
                }
                return@forEach
            }
            val summary = summaries.getOrPut(event.round) { RoundSummary(event.round) }
            when {
                roundFrom(event.message) != null -> summary.startedAt = event.time
                event.message == "执行一键务农" -> summary.oneClickAt = event.time
                event.message.startsWith("本轮类型：") ->
                    summary.roundType = event.message.removePrefix("本轮类型：")
                event.message.startsWith("成熟时间：") ->
                    summary.maturityAt = event.message.removePrefix("成熟时间：")
                event.message.startsWith("下次操作时间：") ->
                    summary.nextActionAt = event.message.removePrefix("下次操作时间：")
                isFailure(event.message) ->
                    summary.failures += "${event.time}：${event.message}"
            }
        }

        return buildList {
            summaries.values.forEach { summary ->
                summary.render()?.let(::add)
                addAll(summary.failures)
            }
            addAll(standaloneFailures)
        }.joinToString("\n")
    }

    fun parsePersisted(line: String): PersistedRuntimeLogEntry? {
        val match = persistedLine.matchEntire(line) ?: return null
        return PersistedRuntimeLogEntry(
            time = match.groupValues[1],
            message = match.groupValues[2],
        )
    }

    private fun isFailure(message: String): Boolean =
        message.startsWith("自动化失败：") || message.startsWith("异常：")

    private data class RoundSummary(
        val round: Int,
        var startedAt: String? = null,
        var oneClickAt: String? = null,
        var roundType: String? = null,
        var maturityAt: String? = null,
        var nextActionAt: String? = null,
        val failures: MutableList<String> = mutableListOf(),
    ) {
        fun render(): String? {
            val startLine = startedAt?.let {
                buildString {
                    append("$it：第${round}轮开始")
                    roundType?.let { type -> append(" · $type") }
                }
            } ?: return null
            val actionLine = oneClickAt?.let { actionAt ->
                buildString {
                    append("$actionAt：执行一键务农")
                    maturityAt?.let {
                        append("，本次操作后作物成熟时间：$it")
                    }
                    nextActionAt?.let {
                        append("，下次操作时间：$it")
                    }
                }
            }
            return listOfNotNull(startLine, actionLine).joinToString("\n")
        }
    }
}
