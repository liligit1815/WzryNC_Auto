package com.lispace.wzryncauto.device

data class CommandResult(
    val command: String,
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean,
) {
    val isSuccess: Boolean
        get() = !timedOut && exitCode == 0

    val isRoot: Boolean
        get() = isSuccess && ROOT_UID_REGEX.containsMatchIn(stdout)

    companion object {
        private val ROOT_UID_REGEX = Regex("""\buid=0\(root\)""")
    }
}

data class BinaryCommandResult(
    val command: String,
    val exitCode: Int?,
    val stdout: ByteArray,
    val stderr: String,
    val durationMs: Long,
    val timedOut: Boolean,
) {
    val isSuccess: Boolean
        get() = !timedOut && exitCode == 0
}
