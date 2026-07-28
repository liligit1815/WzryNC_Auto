package com.lispace.wzryncauto.device

class RootScreenshotProvider(
    private val executor: RootCommandExecutor,
) {
    suspend fun capture(timeoutMs: Long = 30_000): BinaryCommandResult {
        val result = executor.executeBinary("screencap -p", timeoutMs)
        if (result.isSuccess && !isPng(result.stdout)) {
            return result.copy(
                exitCode = -1,
                stderr = "screencap returned invalid PNG data",
            )
        }
        return result
    }

    companion object {
        private val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
        )

        fun isPng(bytes: ByteArray): Boolean =
            bytes.size > PNG_SIGNATURE.size &&
                PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
    }
}
