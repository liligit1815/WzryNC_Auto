package com.lispace.wzryncauto.vision

data class NormalizedRoi(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    init {
        require(left in 0.0..1.0 && right in 0.0..1.0)
        require(top in 0.0..1.0 && bottom in 0.0..1.0)
        require(left < right && top < bottom)
    }
}

data class TemplateSpec(
    val fileName: String,
    val threshold: Double,
    val roi: NormalizedRoi? = null,
)

object TemplateCatalog {
    private val specs = listOf(
        TemplateSpec("start_game.png", 0.75, NormalizedRoi(0.25, 0.55, 0.75, 1.0)),
        TemplateSpec("close_popup.png", 0.90, NormalizedRoi(0.78, 0.04, 1.0, 0.28)),
        TemplateSpec("close_popup_event.png", 0.78, NormalizedRoi(0.78, 0.04, 1.0, 0.28)),
        TemplateSpec(
            "rest_reminder_confirm.png",
            0.88,
            NormalizedRoi(0.40, 0.55, 0.62, 0.72),
        ),
        TemplateSpec("lainongchang.png", 0.75, NormalizedRoi(0.0, 0.55, 0.55, 1.0)),
        TemplateSpec("refresh_pos.png", 0.60, NormalizedRoi(0.75, 0.70, 1.0, 1.0)),
        TemplateSpec("oneclick_farm.png", 0.75, NormalizedRoi(0.45, 0.35, 0.80, 0.80)),
        TemplateSpec("harvest_continue.png", 0.85, NormalizedRoi(0.30, 0.70, 0.70, 1.0)),
        TemplateSpec("back_arrow.png", 0.60),
        TemplateSpec("crop_panel.png", 0.60),
        TemplateSpec("soil_sample.png", 0.60),
        TemplateSpec("statue_platform.png", 0.60),
    ).associateBy(TemplateSpec::fileName)

    fun get(fileName: String): TemplateSpec =
        specs[fileName] ?: TemplateSpec(fileName, 0.60)

    fun scales(
        screenshotWidth: Int,
        screenshotHeight: Int,
        resolutionSpecific: Boolean,
    ): List<Double> {
        require(screenshotWidth > 0 && screenshotHeight > 0)
        if (resolutionSpecific) return listOf(0.90, 0.95, 1.0, 1.05, 1.10)

        val predicted = minOf(
            screenshotWidth.toDouble() / 1280.0,
            screenshotHeight.toDouble() / 720.0,
        )
        return buildSet {
            addAll(listOf(0.75, 1.0, 1.25, 1.5, 2.0))
            listOf(0.85, 0.925, 1.0, 1.075, 1.15)
                .mapTo(this) { factor -> round3(predicted * factor) }
        }.sorted()
    }

    private fun round3(value: Double): Double =
        kotlin.math.round(value * 1000.0) / 1000.0
}
