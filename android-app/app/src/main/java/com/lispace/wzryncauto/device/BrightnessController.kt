package com.lispace.wzryncauto.device

import kotlinx.coroutines.delay

data class BrightnessSnapshot(
    val systemBrightness: Int,
    val automaticMode: Int,
    val backlightValues: Map<String, Int>,
)

class BrightnessController(
    private val executor: RootCommandExecutor,
) {
    suspend fun captureSnapshot(): Result<BrightnessSnapshot> = runCatching {
        val systemBrightness = readInt(
            "settings get system screen_brightness",
        )
        val automaticMode = readInt(
            "settings get system screen_brightness_mode",
        )
        val backlightResult = executor.executeText(READ_BACKLIGHTS)
        check(backlightResult.isSuccess) {
            backlightResult.stderr.ifBlank { "Unable to read backlight nodes" }
        }
        val backlights = backlightResult.stdout
            .lineSequence()
            .mapNotNull(::parseBacklight)
            .toMap()
        BrightnessSnapshot(
            systemBrightness = systemBrightness,
            automaticMode = automaticMode,
            backlightValues = backlights,
        )
    }

    suspend fun setSystemLow(): CommandResult = executor.executeText(
        "settings put system screen_brightness_mode 0; " +
            "settings put system screen_brightness 1",
    )

    suspend fun setRootLow(): CommandResult = executor.executeText(
        "settings put system screen_brightness_mode 0; " +
            "for node in /sys/class/backlight/*/brightness; do " +
            "[ -e \"\$node\" ] && echo 1 > \"\$node\"; done",
    )

    suspend fun restore(snapshot: BrightnessSnapshot): List<String> {
        val errors = mutableListOf<String>()
        // Restore Android's logical setting first. The display service may
        // asynchronously rewrite the kernel nodes for a short period.
        restoreSetting(
            "screen_brightness_mode",
            snapshot.automaticMode,
            errors,
        )
        restoreSetting(
            "screen_brightness",
            snapshot.systemBrightness,
            errors,
        )
        delay(RESTORE_SETTLE_BEFORE_BACKLIGHT_MS)
        snapshot.backlightValues.forEach { (path, value) ->
            val result = executor.executeText(
                "if [ -e ${shellQuote(path)} ]; then " +
                    "echo $value > ${shellQuote(path)}; fi",
            )
            if (!result.isSuccess) {
                errors += "背光节点恢复失败: $path: ${result.stderr.trim()}"
            }
        }
        return errors
    }

    suspend fun restoreVerified(snapshot: BrightnessSnapshot): List<String> {
        val commandErrors = restore(snapshot)
        if (commandErrors.isNotEmpty()) return commandErrors
        var lastErrors = listOf("亮度恢复尚未稳定")
        repeat(RESTORE_VERIFY_ATTEMPTS) { attempt ->
            val actual = captureSnapshot().getOrElse {
                lastErrors = listOf("亮度恢复后读取失败: ${it.message}")
                if (attempt < RESTORE_VERIFY_ATTEMPTS - 1) {
                    delay(RESTORE_VERIFY_INTERVAL_MS)
                }
                return@repeat
            }
            val errors = verifySnapshot(snapshot, actual)
            if (errors.isEmpty()) return emptyList()
            lastErrors = errors
            if (attempt < RESTORE_VERIFY_ATTEMPTS - 1) {
                delay(RESTORE_VERIFY_INTERVAL_MS)
            }
        }
        return lastErrors
    }

    private fun verifySnapshot(
        snapshot: BrightnessSnapshot,
        actual: BrightnessSnapshot,
    ): List<String> {
        val errors = mutableListOf<String>()
        if (snapshot.automaticMode == 0 && actual.systemBrightness != snapshot.systemBrightness) {
            errors += "screen_brightness 恢复值不一致: ${actual.systemBrightness}"
        }
        if (actual.automaticMode != snapshot.automaticMode) {
            errors += "screen_brightness_mode 恢复值不一致: ${actual.automaticMode}"
        }
        if (snapshot.automaticMode == 0) {
            snapshot.backlightValues.forEach { (path, expected) ->
                val restored = actual.backlightValues[path]
                when {
                    restored == null -> errors += "背光节点恢复后缺失: $path"
                    restored != expected -> {
                        errors += "背光节点恢复值不一致: $path=$restored"
                    }
                }
            }
        }
        return errors
    }

    private suspend fun readInt(command: String): Int {
        val result = executor.executeText(command)
        check(result.isSuccess) { result.stderr.ifBlank { "Command failed" } }
        return result.stdout.trim().toInt()
    }

    private suspend fun restoreSetting(
        name: String,
        value: Int,
        errors: MutableList<String>,
    ) {
        val result = executor.executeText(
            "settings put system $name $value",
        )
        if (!result.isSuccess) {
            errors += "$name 恢复失败: ${result.stderr.trim()}"
        }
    }

    private fun parseBacklight(line: String): Pair<String, Int>? {
        val separator = line.lastIndexOf('=')
        if (separator <= 0) return null
        val path = line.substring(0, separator)
        val value = line.substring(separator + 1).trim().toIntOrNull()
            ?: return null
        return path to value
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    companion object {
        private const val RESTORE_SETTLE_BEFORE_BACKLIGHT_MS = 350L
        private const val RESTORE_VERIFY_ATTEMPTS = 6
        private const val RESTORE_VERIFY_INTERVAL_MS = 500L
        private const val READ_BACKLIGHTS =
            "for node in /sys/class/backlight/*/brightness; do " +
                "[ -e \"\$node\" ] && printf '%s=' \"\$node\" && " +
                "cat \"\$node\"; done"
    }
}
