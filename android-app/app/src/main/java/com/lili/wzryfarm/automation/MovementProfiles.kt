package com.lili.wzryfarm.automation

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
            screenWidth = 1280,
            screenHeight = 720,
            spawnToStatue = SwipeGesture(160, 486, 60, 313, 1500),
            statueToFarmland = SwipeGesture(160, 486, 160, 286, 1200),
        ),
    ).associateBy { Pair(it.screenWidth, it.screenHeight) }

    fun requireFor(width: Int, height: Int): FarmMovementProfile =
        requireNotNull(profiles[Pair(width, height)]) {
            "Unsupported farm resolution: ${width}x$height"
        }
}
