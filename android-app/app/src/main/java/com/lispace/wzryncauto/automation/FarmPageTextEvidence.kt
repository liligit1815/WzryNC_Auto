package com.lispace.wzryncauto.automation

import com.lispace.wzryncauto.ocr.HarvestScreenTextBox
import com.lispace.wzryncauto.ocr.HarvestUiObservation
import com.lispace.wzryncauto.ocr.findTextBox

/**
 * Identifies the farm by text that appears in known, independent screen areas.
 * A chat line containing several farm words cannot satisfy these constraints.
 */
internal object FarmPageTextEvidence {
    fun locate(ui: HarvestUiObservation): Set<String> {
        if (ui.sourceWidth <= ui.sourceHeight || ui.sourceHeight <= 0) return emptySet()
        return SPECS.mapNotNull { spec ->
            ui.findTextBox(spec.phrase)
                ?.takeIf { box -> isInside(box, ui, spec) }
                ?.let { box -> LocatedAnchor(spec.phrase, box) }
        }
            // A single OCR box containing several words is one piece of evidence.
            .distinctBy { anchor ->
                listOf(
                    anchor.box.left,
                    anchor.box.top,
                    anchor.box.right,
                    anchor.box.bottom,
                )
            }
            .mapTo(linkedSetOf(), LocatedAnchor::phrase)
    }

    private fun isInside(
        box: HarvestScreenTextBox,
        ui: HarvestUiObservation,
        spec: AnchorSpec,
    ): Boolean {
        val width = box.right - box.left
        val height = box.bottom - box.top
        return box.centerX in
            (ui.sourceWidth * spec.left).toInt() until
                (ui.sourceWidth * spec.right).toInt() &&
            box.centerY in
            (ui.sourceHeight * spec.top).toInt() until
                (ui.sourceHeight * spec.bottom).toInt() &&
            width in 1..(ui.sourceWidth * spec.maxWidth).toInt() &&
            height in 1..(ui.sourceHeight * spec.maxHeight).toInt()
    }

    private data class AnchorSpec(
        val phrase: String,
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val maxWidth: Double = 0.24,
        val maxHeight: Double = 0.14,
    )

    private data class LocatedAnchor(
        val phrase: String,
        val box: HarvestScreenTextBox,
    )

    private val SPECS = listOf(
        AnchorSpec("的农场", 0.08, 0.00, 0.55, 0.18, maxWidth = 0.45),
        AnchorSpec("仓库", 0.72, 0.06, 1.00, 0.38),
        AnchorSpec("社交", 0.72, 0.12, 1.00, 0.48),
        AnchorSpec("对局奖励", 0.55, 0.00, 0.88, 0.20),
        AnchorSpec("百科", 0.30, 0.78, 0.62, 1.00),
        AnchorSpec("种植", 0.70, 0.62, 0.98, 0.99),
        AnchorSpec("一键务农", 0.48, 0.35, 0.84, 0.90),
        AnchorSpec("农场升级", 0.48, 0.50, 0.82, 0.96),
        AnchorSpec("流光加速", 0.48, 0.30, 0.86, 0.92),
    )
}
