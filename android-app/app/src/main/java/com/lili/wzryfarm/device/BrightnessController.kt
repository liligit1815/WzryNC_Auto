package com.lili.wzryfarm.device

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
        val backlights = executor.executeText(READ_BACKLIGHTS).stdout
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
        snapshot.backlightValues.forEach { (path, value) ->
            val result = executor.executeText(
                "if [ -e ${shellQuote(path)} ]; then " +
                    "echo $value > ${shellQuote(path)}; fi",
            )
            if (!result.isSuccess) {
                errors += "背光节点恢复失败: $path: ${result.stderr.trim()}"
            }
        }
        restoreSetting(
            "screen_brightness",
            snapshot.systemBrightness,
            errors,
        )
        restoreSetting(
            "screen_brightness_mode",
            snapshot.automaticMode,
            errors,
        )
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
        private const val READ_BACKLIGHTS =
            "for node in /sys/class/backlight/*/brightness; do " +
                "[ -e \"\$node\" ] && printf '%s=' \"\$node\" && " +
                "cat \"\$node\"; done"
    }
}
