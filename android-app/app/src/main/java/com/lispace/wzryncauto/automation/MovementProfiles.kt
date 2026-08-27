package com.lispace.wzryncauto.automation

data class SwipeGesture(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Int,
)

data class FarmMovementProfile(
    val screenWidth: Int,
    val screenHeight: Int,
    val spawnToStatue: SwipeGesture,
    val statueToFarmland: SwipeGesture,
)

object MovementProfiles {
    private val profiles = listOf(
        FarmMovementProfile(
            screenWidth = 2400,
            screenHeight = 1080,
            spawnToStatue = SwipeGesture(430, 755, 305, 538, 1500),
            statueToFarmland = SwipeGesture(430, 755, 430, 555, 1200),
        ),
        FarmMovementProfile(
            screenWidth = 2560,
            screenHeight = 1600,
            spawnToStatue = SwipeGesture(385, 1165, 200, 844, 1500),
            statueToFarmland = SwipeGesture(385, 1165, 385, 869, 1200),
        ),
        FarmMovementProfile(
            screenWidth = 2560,
            screenHeight = 1564,
            spawnToStatue = SwipeGesture(385, 1138, 200, 825, 1500),
            statueToFarmland = SwipeGesture(385, 1138, 385, 849, 1200),
        ),
        FarmMovementProfile(
            screenWidth = 1280,
            screenHeight = 720,
            spawnToStatue = SwipeGesture(160, 486, 60, 313, 1500),
            statueToFarmland = SwipeGesture(160, 486, 160, 286, 1200),
        ),
    ).associateBy { Pair(it.screenWidth, it.screenHeight) }

    fun requireFor(width: Int, height: Int): FarmMovementProfile {
        require(width > 0 && height > 0) {
            "Invalid farm resolution: ${width}x$height"
        }
        profiles[Pair(width, height)]?.let { return it }

        val targetAspect = width.toDouble() / height
        val reference = profiles.values.minWith(
            compareBy<FarmMovementProfile>(
                { kotlin.math.abs(it.screenWidth.toDouble() / it.screenHeight - targetAspect) },
                {
                    kotlin.math.abs(kotlin.math.ln(width.toDouble() / it.screenWidth)) +
                        kotlin.math.abs(kotlin.math.ln(height.toDouble() / it.screenHeight))
                },
            ),
        )
        val scaleX = width.toDouble() / reference.screenWidth
        val scaleY = height.toDouble() / reference.screenHeight
        return FarmMovementProfile(
            screenWidth = width,
            screenHeight = height,
            spawnToStatue = reference.spawnToStatue.scaled(scaleX, scaleY),
            statueToFarmland = reference.statueToFarmland.scaled(scaleX, scaleY),
        ).also(::requireSafeProfile)
    }

    private fun requireSafeProfile(profile: FarmMovementProfile) {
        listOf(profile.spawnToStatue, profile.statueToFarmland).forEach { gesture ->
            require(
                gesture.startX in 0 until profile.screenWidth &&
                    gesture.endX in 0 until profile.screenWidth &&
                    gesture.startY in 0 until profile.screenHeight &&
                    gesture.endY in 0 until profile.screenHeight,
            ) {
                "Scaled movement exceeds ${profile.screenWidth}x${profile.screenHeight}: $gesture"
            }
        }
    }

    private fun SwipeGesture.scaled(scaleX: Double, scaleY: Double) =
        SwipeGesture(
            startX = (startX * scaleX).toInt(),
            startY = (startY * scaleY).toInt(),
            endX = (endX * scaleX).toInt(),
            endY = (endY * scaleY).toInt(),
            durationMs = durationMs,
        )
}
