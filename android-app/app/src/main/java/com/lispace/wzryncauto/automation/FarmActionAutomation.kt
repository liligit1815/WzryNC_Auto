package com.lispace.wzryncauto.automation

import com.lispace.wzryncauto.ocr.FarmlandState
import com.lispace.wzryncauto.ocr.HarvestInfo
import com.lispace.wzryncauto.ocr.HarvestOcrObservation
import com.lispace.wzryncauto.ocr.HarvestScreenTextBox
import com.lispace.wzryncauto.ocr.HarvestUiObservation
import com.lispace.wzryncauto.ocr.MaturityReading
import com.lispace.wzryncauto.ocr.findTextBox
import kotlinx.coroutines.CancellationException
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.max

data class FarmActionResult(
    val harvested: Boolean,
    val harvestInfo: HarvestInfo?,
    val farmlandState: FarmlandState,
    val firstWaterAt: LocalDateTime,
) {
    val maturity: MaturityReading?
        get() = when (farmlandState) {
            is FarmlandState.Planted -> farmlandState.maturity
            is FarmlandState.Mature -> farmlandState.maturity
            is FarmlandState.Empty,
            is FarmlandState.Unknown,
            -> null
        }
}

/**
 * Continues a round from the verified farm spawn to the farmland.
 *
 * Every visual gate in this phase is OCR based. Movement remains structurally
 * unreachable until the current page is proven and any visible popup has
 * either been handled or proven absent on consecutive frames.
 */
class FarmActionAutomation(
    private val runtime: AutomationRuntime,
    private val control: AutomationControl = AlwaysRunningControl,
    private val onState: (AutomationState, String) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
    private val now: () -> LocalDateTime = LocalDateTime::now,
    private val oneClickGuard: OneClickActionGuard = AllowOneClickActionGuard,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private var state = AutomationState.RESETTING_POSITION

    suspend fun run(): FarmActionResult {
        operation("确认农场页面")
        val spawn = requireFarmReady()
        val profile = MovementProfiles.requireFor(spawn.sourceWidth, spawn.sourceHeight)

        transition(AutomationState.MOVING_TO_STATUE, "从初始位置移动到雕像")
        operation(describe(profile.spawnToStatue))
        runtime.swipe(profile.spawnToStatue)
        awaitActionStability("移动到雕像后")

        transition(AutomationState.VERIFYING_ONE_CLICK_FARM, "确认一键务农按钮")
        val freshButton = requireOneClickFarmTarget(timeoutMs = ONE_CLICK_TIMEOUT_MS)

        transition(AutomationState.ONE_CLICK_FARMING, "准备执行一键务农")
        operation("点击一键务农（${freshButton.centerX}, ${freshButton.centerY}）")
        val guardTarget = VerifiedActionTarget(
            label = ONE_CLICK_TEXT,
            centerX = freshButton.centerX,
            centerY = freshButton.centerY,
        )
        check(oneClickGuard.beforeTap(guardTarget)) {
            "本轮一键务农已越过发送边界，禁止重复点击"
        }
        runtime.tap(freshButton.centerX, freshButton.centerY)
        val firstWaterAt = now()
        // Record this only after the tap command succeeds. The persisted log
        // timestamp and the compact round summary therefore represent the
        // actual one-click action instead of the preceding preparation step.
        operation("执行一键务农")
        wait(POST_CLICK_EXTRA_WAIT_MS)
        awaitActionStability(
            label = "一键务农点击后",
            timeoutMs = ONE_CLICK_STABILITY_TIMEOUT_MS,
        )

        transition(AutomationState.HANDLING_HARVEST, "处理收获弹窗")
        val harvestInfo = handleHarvestPopup()
        // Keep the durable action in SENT state until a fresh post-tap screen
        // proves either a harvest modal or a stable farm page. A crash in this
        // window must never cause the one-click action to be sent again.
        oneClickGuard.afterTapAccepted(firstWaterAt)

        transition(AutomationState.MOVING_TO_FARMLAND, "从雕像移动到土地")
        operation(describe(profile.statueToFarmland))
        runtime.swipe(profile.statueToFarmland)
        awaitActionStability("移动到土地后")

        transition(AutomationState.VERIFYING_FARMLAND, "确认已到达土地")
        val farmlandState = requireFarmlandReading()

        if (farmlandState is FarmlandState.Planted || farmlandState is FarmlandState.Mature) {
            transition(AutomationState.READING_MATURITY, "已到达土地，成熟信息已确认")
        }
        return FarmActionResult(
            harvested = harvestInfo.detected,
            harvestInfo = harvestInfo.info,
            farmlandState = farmlandState,
            firstWaterAt = firstWaterAt,
        )
    }

    private suspend fun requireFarmReady(): HarvestUiObservation {
        var previousAnchors: Set<String>? = null
        var elapsed = 0L
        val startedAtNanos = monotonicNanos()
        var attempts = 0
        val maxAttempts = FARM_READY_TIMEOUT_MS / FARM_READY_RETRY_MS + 2
        while (elapsed <= FARM_READY_TIMEOUT_MS && attempts++ < maxAttempts) {
            control.awaitRunnable()
            val observation = readUiAfterDismissingRestPopup("确认农场页面")
            elapsed = max(elapsed, elapsedSince(startedAtNanos))
            if (elapsed > FARM_READY_TIMEOUT_MS) break
            val ui = observation?.ui
            val blockingReason = ui?.let(::blockingPopupReason)
            val anchors = if (ui != null && blockingReason == null && isLandscape(ui)) {
                farmPageAnchors(ui)
            } else {
                emptyList()
            }
            val anchorSet = anchors.toSet()
            if (
                anchorSet.size >= FARM_READY_MIN_ANCHORS &&
                previousAnchors != null &&
                anchorSet.intersect(previousAnchors.orEmpty()).isNotEmpty()
            ) {
                onLog(
                    "农场文字锚点已连续确认：" +
                        anchors.joinToString("、"),
                )
                return ui!!
            } else {
                onLog(
                    when {
                        ui == null -> "农场页面 OCR 暂不可用"
                        !isLandscape(ui) ->
                            "农场画面不是横屏：${ui.sourceWidth}×${ui.sourceHeight}"
                        blockingReason != null -> "农场页面存在未处理弹窗：$blockingReason"
                        else -> "农场文字锚点不足：${anchors.joinToString("、").ifBlank { "<无>" }}"
                    },
                )
            }
            previousAnchors = anchorSet.takeIf { it.size >= FARM_READY_MIN_ANCHORS }
            if (elapsed == FARM_READY_TIMEOUT_MS) break
            val delay = FARM_READY_RETRY_MS.coerceAtMost(FARM_READY_TIMEOUT_MS - elapsed)
            wait(delay)
            elapsed += delay
        }
        throw AutomationFailure("未连续两帧确认农场页面，禁止执行摇杆移动")
    }

    private suspend fun requireOneClickFarmTarget(timeoutMs: Long): OcrTarget {
        var elapsed = 0L
        val startedAtNanos = monotonicNanos()
        var attempts = 0
        var consecutiveContextMisses = 0
        var previousContextPhrase: String? = null
        var rootConfirmAttempts = 0
        val maxAttempts = timeoutMs / ONE_CLICK_RETRY_MS + 2
        while (elapsed <= timeoutMs && attempts++ < maxAttempts) {
            control.awaitRunnable()
            val observation = readUiAfterDismissingRestPopup("识别一键务农")
            elapsed = max(elapsed, elapsedSince(startedAtNanos))
            val ui = observation?.ui
            val blockingReason = ui?.let(::blockingPopupReason)
            if (blockingReason != null) {
                consecutiveContextMisses = 0
                previousContextPhrase = null
                onLog("一键务农识别被弹窗阻断：$blockingReason")
            } else {
                val evidence = ui?.let(::locateOneClickEvidence)
                evidence?.target?.let { target ->
                    onLog(
                        "识别到一键务农，立即点击（${target.centerX}, ${target.centerY}）",
                    )
                    return target
                }
                if (evidence?.context != null) {
                    val contextPhrase = evidence.contextPhrase
                    consecutiveContextMisses = if (contextPhrase == previousContextPhrase) {
                        consecutiveContextMisses + 1
                    } else {
                        1
                    }
                    previousContextPhrase = contextPhrase
                    onLog(
                        "一键务农文字暂时漏检，操作区上下文“$contextPhrase”已识别" +
                            "（$consecutiveContextMisses/$ONE_CLICK_STREAM_MISSES_BEFORE_ROOT）",
                    )
                    if (
                        consecutiveContextMisses >= ONE_CLICK_STREAM_MISSES_BEFORE_ROOT &&
                        rootConfirmAttempts == 0
                    ) {
                        while (rootConfirmAttempts < ONE_CLICK_ROOT_CONFIRM_ATTEMPTS) {
                            rootConfirmAttempts += 1
                            locateOneClickTargetFromRoot(rootConfirmAttempts)?.let { return it }
                            elapsed = max(elapsed, elapsedSince(startedAtNanos))
                            if (rootConfirmAttempts < ONE_CLICK_ROOT_CONFIRM_ATTEMPTS) {
                                wait(ONE_CLICK_ROOT_CONFIRM_RETRY_MS)
                            }
                        }
                        onLog(
                            "ROOT 截图已完成 $ONE_CLICK_ROOT_CONFIRM_ATTEMPTS 次安全复核，" +
                                "仍未确认一键务农",
                        )
                        consecutiveContextMisses = 0
                        previousContextPhrase = null
                    }
                } else {
                    consecutiveContextMisses = 0
                    previousContextPhrase = null
                    onLog("未找到安全的一键务农文字")
                }
            }
            // A capture that started inside the primary-channel budget may
            // finish just after the deadline. Its completed OCR evidence must
            // be evaluated above before the timeout prevents another capture.
            if (elapsed >= timeoutMs) break
            val delay = ONE_CLICK_RETRY_MS.coerceAtMost(timeoutMs - elapsed)
            wait(delay)
            elapsed += delay
        }
        throw AutomationFailure("移动后未找到安全的一键务农文字")
    }

    private suspend fun locateOneClickTargetFromRoot(attempt: Int): OcrTarget? {
        onLog(
            "屏幕流连续漏检一键务农，切换 ROOT 截图复核" +
                "（$attempt/$ONE_CLICK_ROOT_CONFIRM_ATTEMPTS）",
        )
        val observation = readUiFromRoot("ROOT 复核一键务农") ?: return null
        val ui = observation.ui
        blockingPopupReason(ui)?.let { reason ->
            onLog("ROOT 截图复核被弹窗阻断：$reason")
            return null
        }
        val evidence = locateOneClickEvidence(ui)
        evidence?.target?.let { target ->
            onLog(
                "ROOT 截图确认一键务农（${target.centerX}, ${target.centerY}）",
            )
            return target
        }
        onLog(
            if (evidence?.context != null) {
                "ROOT 截图仍只识别到操作区上下文“${evidence.contextPhrase}”"
            } else {
                "ROOT 截图未找到安全的一键务农文字"
            },
        )
        return null
    }

    private fun locateOneClickEvidence(ui: HarvestUiObservation): OneClickEvidence? {
        if (!isLandscape(ui)) return null
        val context = ONE_CLICK_CONTEXTS.firstNotNullOfOrNull { phrase ->
            ui.findTextBox(phrase)
                ?.takeIf { isSafeBox(it, ui, ONE_CLICK_CONTEXT_SPEC) }
                ?.let { box -> OcrTarget(phrase, box.centerX, box.centerY) }
        }
        val exactBox = ui.findTextBox(ONE_CLICK_TEXT)
        val correctedMatch = if (exactBox == null) {
            ONE_CLICK_OCR_VARIANTS.firstNotNullOfOrNull { variant ->
                ui.findTextBox(variant)?.let { box -> OneClickTextMatch(variant, box) }
            }
        } else {
            null
        }
        val box = exactBox ?: correctedMatch?.box
        if (box != null) {
            if (!isSafeBox(box, ui, ONE_CLICK_SPEC)) {
                onLog(
                    "一键务农文字坐标不在安全区：" +
                        "(${box.centerX}, ${box.centerY})/${ui.sourceWidth}×${ui.sourceHeight}",
                )
                return null
            }
            if (correctedMatch != null && context == null) {
                onLog(
                    "识别到一键务农 OCR 近似文字“${correctedMatch.rawText}”，" +
                        "但缺少流光加速/农场升级上下文，拒绝纠错点击",
                )
                return null
            }
            correctedMatch?.let {
                onLog(
                    "一键务农 OCR 纠错：“${it.rawText}”→“$ONE_CLICK_TEXT”" +
                        "（上下文：${context?.phrase}）",
                )
            }
            if (context == null) {
                onLog("识别到一键务农文字，但缺少流光加速/农场升级上下文")
            }
            return OneClickEvidence(
                target = OcrTarget(ONE_CLICK_TEXT, box.centerX, box.centerY),
                context = context,
                contextPhrase = context?.phrase,
            )
        }
        return context?.let {
            OneClickEvidence(
                context = it,
                contextPhrase = it.phrase,
            )
        }
    }

    private suspend fun handleHarvestPopup(): HarvestOutcome {
        val startedAtNanos = monotonicNanos()
        var absentFrames = 0
        var previousAbsentAnchors: Set<String>? = null
        repeat(HARVEST_DETECT_ATTEMPTS) { attempt ->
            val frame = observeHarvestFrame()
            requirePhaseTime(startedAtNanos, HARVEST_PHASE_TIMEOUT_MS, "收获弹窗识别超时")
            if (frame.target != null) return closeHarvestPopup(frame)
            if (frame.isAbsentCandidate) {
                val currentAnchors = frame.farmAnchors.toSet()
                absentFrames = if (
                    previousAbsentAnchors != null &&
                    currentAnchors.intersect(previousAbsentAnchors.orEmpty()).isNotEmpty()
                ) {
                    absentFrames + 1
                } else {
                    1
                }
                previousAbsentAnchors = currentAnchors
                onLog("收获弹窗缺席候选（$absentFrames/$HARVEST_ABSENT_QUORUM）")
                if (absentFrames >= HARVEST_ABSENT_QUORUM) {
                    onLog("连续三帧确认无收获弹窗，继续移动到土地")
                    return HarvestOutcome(detected = false, info = null)
                }
            } else {
                absentFrames = 0
                previousAbsentAnchors = null
                onLog(
                    "收获弹窗状态不明（${attempt + 1}/$HARVEST_DETECT_ATTEMPTS）：" +
                        frame.reason,
                )
            }
            if (attempt < HARVEST_DETECT_ATTEMPTS - 1) wait(HARVEST_DETECT_RETRY_MS)
        }
        throw AutomationFailure("收获弹窗状态无法确认，禁止继续移动")
    }

    private suspend fun closeHarvestPopup(initial: HarvestFrame): HarvestOutcome {
        val startedAtNanos = monotonicNanos()
        operation(
            if (initial.kind == HarvestPopupKind.COMPLETE) {
                "检测到新版收获弹窗"
            } else {
                "检测到带奖励上下文的旧版收获弹窗"
            },
        )
        wait(HARVEST_REWARD_INITIAL_DELAY_MS)
        val observedFrames = mutableListOf(initial)
        val readings = mutableListOf<HarvestInfo>()
        initial.observation?.parsed?.let(readings::add)
        repeat(HARVEST_OCR_ATTEMPTS - 1) { index ->
                val frame = observeHarvestFrame()
                observedFrames += frame
                frame.observation?.parsed?.let(readings::add)
                requirePhaseTime(
                    startedAtNanos,
                    HARVEST_PHASE_TIMEOUT_MS,
                    "收获弹窗处理超时",
                )
                if (index < HARVEST_OCR_ATTEMPTS - 2) wait(HARVEST_OCR_RETRY_MS)
        }
        val info = mergeHarvestReadings(readings)
        onLog(
            "收获 OCR（${readings.size}/$HARVEST_OCR_ATTEMPTS 帧）：" +
                "${info?.rawText?.replace('\n', ' ')?.take(200) ?: "<未解析奖励>"}",
        )

        var target: OcrTarget? = null
        var confirmationAbsentFrames = 0
        for (attempt in 0 until HARVEST_TARGET_CONFIRM_ATTEMPTS) {
            val fresh = observeHarvestFrame()
            requirePhaseTime(startedAtNanos, HARVEST_PHASE_TIMEOUT_MS, "收获弹窗处理超时")

            val currentTarget = fresh.target
            val currentUi = fresh.observation?.ui
            if (
                currentTarget != null && currentUi != null &&
                observedFrames.asReversed().any { previous ->
                    previous.target?.let { prior ->
                        coordinatesAreStable(prior, currentTarget, currentUi)
                    } == true
                }
            ) {
                target = currentTarget
                onLog(
                    "收获弹窗点击坐标已通过多帧确认" +
                        "（${currentTarget.centerX}, ${currentTarget.centerY}）",
                )
                break
            }

            confirmationAbsentFrames = if (fresh.isAbsentCandidate) confirmationAbsentFrames + 1 else 0
            if (confirmationAbsentFrames >= HARVEST_ABSENT_QUORUM) {
                onLog("收获弹窗已自行消失，未执行额外点击")
                return HarvestOutcome(detected = true, info = info)
            }

            onLog(
                "收获弹窗点击坐标确认（${attempt + 1}/$HARVEST_TARGET_CONFIRM_ATTEMPTS）：" +
                    when {
                        currentTarget == null -> fresh.reason
                        else -> "坐标尚未与历史有效帧稳定重合"
                    },
            )
            observedFrames += fresh
            if (attempt < HARVEST_TARGET_CONFIRM_ATTEMPTS - 1) {
                wait(HARVEST_TARGET_CONFIRM_RETRY_MS)
            }
        }
        val confirmedTarget = target
            ?: throw AutomationFailure("收获弹窗多帧确认后仍缺少安全文字坐标，禁止盲点")

        operation("关闭收获弹窗（${confirmedTarget.centerX}, ${confirmedTarget.centerY}）")
        runtime.tap(confirmedTarget.centerX, confirmedTarget.centerY)
        wait(POST_CLICK_EXTRA_WAIT_MS)
        awaitActionStability("关闭收获弹窗后")

        var postClickAbsentFrames = 0
        repeat(HARVEST_POSTCHECK_ATTEMPTS) { attempt ->
                val frame = observeHarvestFrame()
                requirePhaseTime(
                    startedAtNanos,
                    HARVEST_PHASE_TIMEOUT_MS,
                    "收获弹窗处理超时",
                )
            when {
                frame.target != null || frame.hasPopupEvidence -> postClickAbsentFrames = 0
                frame.isAbsentCandidate -> {
                    postClickAbsentFrames += 1
                    if (postClickAbsentFrames >= HARVEST_ABSENT_QUORUM) {
                        return HarvestOutcome(detected = true, info = info)
                    }
                }
                else -> postClickAbsentFrames = 0
            }
            if (attempt < HARVEST_POSTCHECK_ATTEMPTS - 1) {
                wait(HARVEST_DETECT_RETRY_MS)
            }
        }
        throw AutomationFailure("收获弹窗点击后未连续两帧确认消失")
    }

    private suspend fun observeHarvestFrame(): HarvestFrame {
        control.awaitRunnable()
        val observation = readUiAfterDismissingRestPopup("确认收获弹窗")
        val ui = observation?.ui
        val target = observation?.let(::locateHarvestTarget)
        val hasEvidence = ui?.let(::hasHarvestPopupEvidence) == true
        val blockingReason = ui?.let(::blockingNonHarvestPopupReason)
        val anchors = if (ui != null && blockingReason == null && !hasEvidence) {
            farmPageAnchors(ui)
        } else {
            emptyList()
        }
        return HarvestFrame(
            observation = observation,
            target = target?.target,
            kind = target?.kind,
            hasPopupEvidence = hasEvidence,
            farmAnchors = anchors,
            blockingReason = blockingReason,
        )
    }

    private fun locateHarvestTarget(observation: HarvestOcrObservation): HarvestTarget? {
        val ui = observation.ui
        if (!isLandscape(ui)) return null
        val continueBox = ui.findTextBox(CLICK_TO_CONTINUE_TEXT) ?: return null
        if (!isSafeBox(continueBox, ui, HARVEST_CONTINUE_SPEC)) {
            onLog(
                "收获“点击继续”坐标不在安全区：" +
                    "(${continueBox.centerX}, ${continueBox.centerY})/" +
                    "${ui.sourceWidth}×${ui.sourceHeight}",
            )
            return null
        }

        val congratulationsBox = ui.findTextBox(CONGRATULATIONS_TEXT)
        val hasCongratulations = congratulationsBox != null &&
            isSafeBox(congratulationsBox, ui, HARVEST_HEADER_SPEC) &&
            congratulationsBox.centerY < continueBox.centerY &&
            abs(congratulationsBox.centerX - continueBox.centerX) <= ui.sourceWidth * 0.30
        val hasRewardContext = hasHarvestRewardContext(observation, continueBox)
        val kind = when {
            hasCongratulations -> HarvestPopupKind.COMPLETE
            hasRewardContext -> HarvestPopupKind.LEGACY_WITH_REWARD_CONTEXT
            else -> {
                onLog("已识别安全的“点击继续”，但缺少收获标题或奖励上下文")
                return null
            }
        }
        return HarvestTarget(
            target = OcrTarget(
                phrase = CLICK_TO_CONTINUE_TEXT,
                centerX = continueBox.centerX,
                centerY = continueBox.centerY,
            ),
            kind = kind,
        )
    }

    private fun hasHarvestRewardContext(
        observation: HarvestOcrObservation,
        continueBox: HarvestScreenTextBox,
    ): Boolean = HARVEST_REWARD_CONTEXTS.any { phrase ->
        observation.ui.findTextBox(phrase)?.let { box ->
            isSafeBox(box, observation.ui, HARVEST_REWARD_SPEC) &&
                box.centerY < continueBox.centerY &&
                abs(box.centerX - continueBox.centerX) <= observation.ui.sourceWidth * 0.32
        } == true
    }

    private fun hasHarvestPopupEvidence(ui: HarvestUiObservation): Boolean =
        ui is HarvestUiObservation.Present ||
            ui is HarvestUiObservation.Partial ||
            ui is HarvestUiObservation.Unknown ||
            ui.findTextBox(CONGRATULATIONS_TEXT) != null ||
            ui.findTextBox(CLICK_TO_CONTINUE_TEXT) != null

    private suspend fun requireFarmlandReading(): FarmlandState {
        val startedAtNanos = monotonicNanos()
        val readings = mutableListOf<FarmlandState>()
        var consensus: FarmlandState? = null
        for (attempt in 0 until FARMLAND_OCR_MAX_ATTEMPTS) {
            control.awaitRunnable()
            val ui = readUiAfterDismissingRestPopup("土地识别前检查")?.ui
                ?: throw AutomationFailure("土地识别前 OCR 不可用，禁止继续")
            blockingPopupReason(ui)?.let { reason ->
                throw AutomationFailure("土地识别前存在未处理弹窗：$reason")
            }
            val reading = runtime.readFarmland()
            requirePhaseTime(startedAtNanos, FARMLAND_PHASE_TIMEOUT_MS, "土地 OCR 超时")
            readings += reading
            onLog(
                "土地识别（${attempt + 1}/$FARMLAND_OCR_MAX_ATTEMPTS）：" +
                    describe(reading),
            )
            if (readings.size >= FARMLAND_OCR_INITIAL_ATTEMPTS) {
                consensus = resolveKnownFarmlandConsensus(readings)
                if (consensus != null) break
                if (attempt < FARMLAND_OCR_MAX_ATTEMPTS - 1) {
                    onLog("土地识别尚未形成两帧有效共识，追加采样")
                }
            }
            if (attempt < FARMLAND_OCR_MAX_ATTEMPTS - 1) wait(FARMLAND_OCR_RETRY_MS)
        }
        val resolved = consensus ?: resolveFarmlandConsensus(readings)
        when (resolved) {
            is FarmlandState.Planted -> onLog(
                "土地成熟时间：%02d:%02d".format(
                    resolved.maturity.hour,
                    resolved.maturity.minute,
                ),
            )
            is FarmlandState.Mature -> onLog("土地作物已成熟")
            is FarmlandState.Empty -> {
                val level = resolved.level?.let { "（${it}级）" }.orEmpty()
                onLog("已到达土地，但土地为空$level")
            }
            is FarmlandState.Unknown -> {
                val raw = resolved.rawText.replace('\n', ' ').trim().take(120)
                    .ifBlank { "<空>" }
                onLog("移动后土地状态未识别：${resolved.reason}；原文：$raw")
            }
        }
        return resolved
    }

    private fun resolveFarmlandConsensus(readings: List<FarmlandState>): FarmlandState {
        check(readings.size in FARMLAND_OCR_INITIAL_ATTEMPTS..FARMLAND_OCR_MAX_ATTEMPTS)
        resolveKnownFarmlandConsensus(readings)?.let { return it }

        val knownReadings = readings.filterNot { it is FarmlandState.Unknown }
        val knownCategoryCounts = knownReadings.groupingBy(::category).eachCount()
        val hasKnownCategoryQuorum = knownCategoryCounts.values.any {
            it >= FARMLAND_OCR_QUORUM
        }
        if (hasKnownCategoryQuorum) {
            val reason = if (
                knownReadings.filterIsInstance<FarmlandState.Planted>().size >=
                FARMLAND_OCR_QUORUM
            ) {
                "五帧成熟时间不一致"
            } else {
                "五帧土地状态不一致"
            }
            return inconsistentFarmland(readings, reason)
        }

        val unknowns = readings.filterIsInstance<FarmlandState.Unknown>()
        if (unknowns.size >= FARMLAND_OCR_QUORUM) {
            return FarmlandState.Unknown(
                rawText = readings.joinToString(" | ") { it.rawText },
                reason = unknowns.joinToString("；") { it.reason }
                    .ifBlank { "土地状态未知" },
            )
        }
        return inconsistentFarmland(readings, "五帧土地状态不一致")
    }

    /**
     * Unknown OCR frames abstain instead of outvoting valid card readings.
     * A concrete result still needs two agreeing frames, and planted cards
     * additionally need the same day/hour/minute on both frames.
     */
    private fun resolveKnownFarmlandConsensus(readings: List<FarmlandState>): FarmlandState? {
        val categoryGroups = readings
            .filterNot { it is FarmlandState.Unknown }
            .groupBy(::category)
            .values
            .filter { it.size >= FARMLAND_OCR_QUORUM }
        val largestCategorySize = categoryGroups.maxOfOrNull { it.size }
            ?: return null
        val categoryWinners = categoryGroups.filter { it.size == largestCategorySize }
        if (categoryWinners.size != 1) return null

        val categoryWinner = categoryWinners.single()
        return when (val first = categoryWinner.first()) {
            is FarmlandState.Planted -> {
                val timeGroups = categoryWinner
                    .filterIsInstance<FarmlandState.Planted>()
                    .groupBy { with(it.maturity) { "$dayOffset-$hour-$minute" } }
                    .values
                    .filter { it.size >= FARMLAND_OCR_QUORUM }
                val largestTimeSize = timeGroups.maxOfOrNull { it.size }
                    ?: return null
                val timeWinners = timeGroups.filter { it.size == largestTimeSize }
                timeWinners.singleOrNull()?.last()
            }
            is FarmlandState.Mature -> first
            is FarmlandState.Empty -> first
            is FarmlandState.Unknown -> null
        }
    }

    private fun inconsistentFarmland(
        readings: List<FarmlandState>,
        reason: String,
    ) = FarmlandState.Unknown(
        rawText = readings.joinToString(" | ") { it.rawText },
        reason = reason,
    )

    private fun category(reading: FarmlandState): FarmlandCategory = when (reading) {
        is FarmlandState.Planted -> FarmlandCategory.PLANTED
        is FarmlandState.Mature -> FarmlandCategory.MATURE
        is FarmlandState.Empty -> FarmlandCategory.EMPTY
        is FarmlandState.Unknown -> FarmlandCategory.UNKNOWN
    }

    private fun describe(reading: FarmlandState): String = when (reading) {
        is FarmlandState.Planted -> "成长中，%02d:%02d成熟".format(
            reading.maturity.hour,
            reading.maturity.minute,
        )
        is FarmlandState.Mature -> "已成熟"
        is FarmlandState.Empty -> "空地${reading.level?.let { "，${it}级" }.orEmpty()}"
        is FarmlandState.Unknown -> {
            val raw = reading.rawText.replace('\n', ' ').trim().take(80).ifBlank { "<空>" }
            "未知（${reading.reason}；$raw）"
        }
    }

    private suspend fun readUiAfterDismissingRestPopup(
        label: String,
    ): HarvestOcrObservation? {
        var dismissed = 0
        while (true) {
            val observation = readUi(label) ?: return null
            when (val prompt = locateRestPrompt(observation.ui)) {
                RestPromptState.Absent -> {
                    val modalContext = locateStrongPopupContext(observation.ui)
                        ?: return observation
                    if (dismissed >= REST_DISMISS_LIMIT) {
                        throw AutomationFailure("连续关闭 $REST_DISMISS_LIMIT 次后弹窗仍然存在")
                    }
                    wait(REST_STABILITY_DELAY_MS)
                    val fresh = readUi("确认通用弹窗")
                        ?: throw AutomationFailure("通用弹窗关闭按钮识别前 OCR 不可用")
                    val confirmed = locateStrongPopupContext(fresh.ui)
                    if (
                        confirmed == null ||
                        confirmed.phrase != modalContext.phrase ||
                        !coordinatesAreStable(modalContext, confirmed, fresh.ui)
                    ) {
                        throw AutomationFailure("通用弹窗文字未通过连续两帧确认，禁止点击关闭按钮")
                    }
                    val closeTarget = runtime.findPopupCloseTarget(
                        PopupCloseSearchProfile.forPopupPhrase(confirmed.phrase),
                    )
                    if (closeTarget == null) {
                        if (confirmed.phrase in BANNER_POPUP_CONTEXTS) {
                            onLog(
                                "识别到普通页面横幅“${confirmed.phrase}”，" +
                                    "不按通用弹窗处理",
                            )
                            return observation
                        }
                        throw AutomationFailure("通用弹窗已确认，但未定位关闭按钮")
                    }
                    wait(COMMON_POPUP_CLICK_DELAY_MS)
                    operation(
                        "识别到弹窗文字“${confirmed.phrase}”，关闭弹窗" +
                            "（${closeTarget.centerX}, ${closeTarget.centerY}）",
                    )
                    runtime.tap(closeTarget.centerX, closeTarget.centerY)
                    wait(POST_CLICK_EXTRA_WAIT_MS)
                    dismissed += 1
                    awaitActionStability("关闭通用弹窗后")
                    wait(COMMON_POPUP_RESUME_DELAY_MS)
                }
                is RestPromptState.Partial -> throw AutomationFailure(
                    "检测到疑似防沉迷提示但证据不完整：${prompt.reason}",
                )
                is RestPromptState.Present -> {
                    if (dismissed >= REST_DISMISS_LIMIT) {
                        throw AutomationFailure("连续关闭 $REST_DISMISS_LIMIT 次后防沉迷提示仍然存在")
                    }
                    wait(REST_STABILITY_DELAY_MS)
                    val fresh = readUi("确认防沉迷休息提示")
                        ?: throw AutomationFailure("防沉迷提示点击前 OCR 不可用")
                    val confirmed = locateRestPrompt(fresh.ui) as? RestPromptState.Present
                        ?: throw AutomationFailure("防沉迷提示未通过连续两帧确认，禁止盲点")
                    if (!coordinatesAreStable(prompt.target, confirmed.target, fresh.ui)) {
                        throw AutomationFailure("防沉迷确定按钮坐标漂移，禁止盲点")
                    }
                    operation(
                        "关闭防沉迷休息提示（${confirmed.target.centerX}, " +
                            "${confirmed.target.centerY}）",
                    )
                    wait(COMMON_POPUP_CLICK_DELAY_MS)
                    runtime.tap(confirmed.target.centerX, confirmed.target.centerY)
                    wait(POST_CLICK_EXTRA_WAIT_MS)
                    dismissed += 1
                    awaitActionStability("关闭防沉迷休息提示后")
                    wait(COMMON_POPUP_RESUME_DELAY_MS)
                }
            }
        }
    }

    private suspend fun readUi(label: String): HarvestOcrObservation? = try {
        control.awaitRunnable()
        runtime.readHarvestUi()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        onLog("$label OCR 失败：${error.message}")
        null
    }

    private suspend fun readUiFromRoot(label: String): HarvestOcrObservation? = try {
        control.awaitRunnable()
        runtime.readHarvestUiFromRoot()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        onLog("$label OCR 失败：${error.message}")
        null
    }

    private suspend fun awaitActionStability(
        label: String,
        timeoutMs: Long = ACTION_STABILITY_TIMEOUT_MS,
        fallbackMs: Long = ACTION_STABILITY_FALLBACK_MS,
    ) {
        val result = runtime.awaitScreenStable(timeoutMs, label)
        if (result.stable) {
            onLog(
                "${label}画面已稳定（${result.samples}次采样，" +
                    "${result.elapsedMs}ms）",
            )
        } else {
            onLog("${label}画面仍有动态，进入 OCR 连续确认（${result.elapsedMs}ms）")
            wait(fallbackMs)
        }
    }

    private fun locateRestPrompt(ui: HarvestUiObservation): RestPromptState {
        val context = REST_CONTEXTS.firstOrNull { phrase ->
            ui.findTextBox(phrase)?.let { box ->
                isSafeBox(box, ui, POPUP_CONTEXT_SPEC)
            } == true
        }
        val confirmBox = ui.findTextBox(CONFIRM_TEXT)
        if (context == null && confirmBox == null) return RestPromptState.Absent
        if (context == null) return RestPromptState.Partial("只识别到确定按钮，缺少防沉迷上下文")
        if (confirmBox == null) return RestPromptState.Partial("识别到$context，但未定位确定按钮")
        if (!isLandscape(ui) || !isSafeBox(confirmBox, ui, REST_CONFIRM_SPEC)) {
            return RestPromptState.Partial("防沉迷确定按钮坐标不在安全区")
        }
        return RestPromptState.Present(
            context = context,
            target = OcrTarget(CONFIRM_TEXT, confirmBox.centerX, confirmBox.centerY),
        )
    }

    private fun locateStrongPopupContext(ui: HarvestUiObservation): OcrTarget? =
        STRONG_POPUP_CONTEXTS.firstNotNullOfOrNull { phrase ->
            val box = ui.findTextBox(phrase) ?: return@firstNotNullOfOrNull null
            if (!isSafeBox(box, ui, POPUP_CONTEXT_SPEC)) {
                null
            } else {
                OcrTarget(phrase, box.centerX, box.centerY)
            }
        }

    private fun blockingPopupReason(ui: HarvestUiObservation): String? =
        blockingNonHarvestPopupReason(ui)
            ?: if (hasHarvestPopupEvidence(ui)) "检测到收获弹窗文字" else null

    private fun blockingNonHarvestPopupReason(ui: HarvestUiObservation): String? =
        when (val rest = locateRestPrompt(ui)) {
            RestPromptState.Absent -> null
            is RestPromptState.Partial -> rest.reason
            is RestPromptState.Present -> "防沉迷休息提示仍然存在"
        }

    private fun farmPageAnchors(ui: HarvestUiObservation): List<String> =
        FarmPageTextEvidence.locate(ui).toList()

    private fun isSafeBox(
        box: HarvestScreenTextBox,
        ui: HarvestUiObservation,
        spec: TextBoxSpec,
    ): Boolean {
        if (!isLandscape(ui)) return false
        val safeX = box.centerX in
            (ui.sourceWidth * spec.left).toInt() until (ui.sourceWidth * spec.right).toInt()
        val safeY = box.centerY in
            (ui.sourceHeight * spec.top).toInt() until (ui.sourceHeight * spec.bottom).toInt()
        val width = box.right - box.left
        val height = box.bottom - box.top
        val safeSize = width in 1..(ui.sourceWidth * spec.maxWidth).toInt() &&
            height in 1..(ui.sourceHeight * spec.maxHeight).toInt()
        return safeX && safeY && safeSize
    }

    private fun coordinatesAreStable(
        first: OcrTarget,
        second: OcrTarget,
        ui: HarvestUiObservation,
    ): Boolean =
        abs(first.centerX - second.centerX) <= (ui.sourceWidth * MAX_TARGET_DRIFT).toInt() &&
            abs(first.centerY - second.centerY) <= (ui.sourceHeight * MAX_TARGET_DRIFT).toInt()

    private fun isLandscape(ui: HarvestUiObservation): Boolean =
        ui.sourceWidth > ui.sourceHeight && ui.sourceHeight > 0

    private suspend fun wait(milliseconds: Long) {
        var remaining = milliseconds
        while (remaining > 0) {
            control.awaitRunnable()
            val slice = remaining.coerceAtMost(250)
            runtime.delayMs(slice)
            remaining -= slice
        }
    }

    private fun elapsedSince(startedAtNanos: Long): Long =
        (monotonicNanos() - startedAtNanos) / 1_000_000L

    private fun requirePhaseTime(
        startedAtNanos: Long,
        timeoutMs: Long,
        message: String,
    ) {
        if (elapsedSince(startedAtNanos) > timeoutMs) throw AutomationFailure(message)
    }

    private fun transition(next: AutomationState, message: String) {
        AutomationTransitionPolicy.requireTransition(state, next)
        state = next
        onState(next, message)
        onLog(message)
    }

    private fun operation(message: String) {
        onState(state, message)
        onLog(message)
    }

    private fun describe(gesture: SwipeGesture): String =
        "摇杆（${gesture.startX},${gesture.startY}）→" +
            "（${gesture.endX},${gesture.endY}），${gesture.durationMs}ms"

    private fun mergeHarvestReadings(readings: List<HarvestInfo>): HarvestInfo? {
        if (readings.isEmpty()) return null
        val crops = linkedMapOf<String, Int>()
        readings.forEach { reading ->
            reading.crops.forEach { (crop, count) ->
                crops[crop] = max(crops[crop] ?: 0, count)
            }
        }
        return HarvestInfo(
            experience = readings.maxOf(HarvestInfo::experience),
            crops = crops,
            rawText = readings.joinToString(" | ") { it.rawText },
        )
    }

    companion object {
        private const val ONE_CLICK_TEXT = "一键务农"
        private const val CONGRATULATIONS_TEXT = "恭喜您获得"
        private const val CLICK_TO_CONTINUE_TEXT = "点击继续"
        private const val CONFIRM_TEXT = "确定"

        private const val FARM_READY_TIMEOUT_MS = 15_000L
        private const val POST_CLICK_EXTRA_WAIT_MS = 2_000L
        private const val FARM_READY_RETRY_MS = 500L
        private const val FARM_READY_MIN_ANCHORS = 2
        private const val ONE_CLICK_TIMEOUT_MS = 20_000L
        private const val ONE_CLICK_RETRY_MS = 1_000L
        private const val ONE_CLICK_STREAM_MISSES_BEFORE_ROOT = 3
        private const val ONE_CLICK_ROOT_CONFIRM_ATTEMPTS = 2
        private const val ONE_CLICK_ROOT_CONFIRM_RETRY_MS = 1_000L
        private const val MAX_TARGET_DRIFT = 0.03
        private const val REST_DISMISS_LIMIT = 3
        private const val REST_STABILITY_DELAY_MS = 1_000L
        private const val COMMON_POPUP_CLICK_DELAY_MS = 1_000L
        private const val COMMON_POPUP_RESUME_DELAY_MS = 1_000L
        private const val ACTION_STABILITY_TIMEOUT_MS = 4_000L
        private const val ACTION_STABILITY_FALLBACK_MS = 1_000L
        private const val ONE_CLICK_STABILITY_TIMEOUT_MS = 10_000L
        private const val HARVEST_DETECT_ATTEMPTS = 5
        private const val HARVEST_ABSENT_QUORUM = 3
        private const val HARVEST_POSTCHECK_ATTEMPTS = 3
        private const val HARVEST_DETECT_RETRY_MS = 1_000L
        private const val HARVEST_OCR_ATTEMPTS = 3
        private const val HARVEST_REWARD_INITIAL_DELAY_MS = 1_000L
        private const val HARVEST_OCR_RETRY_MS = 500L
        private const val HARVEST_TARGET_CONFIRM_ATTEMPTS = 5
        private const val HARVEST_TARGET_CONFIRM_RETRY_MS = 500L
        private const val HARVEST_PHASE_TIMEOUT_MS = 60_000L
        private const val FARMLAND_OCR_INITIAL_ATTEMPTS = 3
        private const val FARMLAND_OCR_MAX_ATTEMPTS = 5
        private const val FARMLAND_OCR_QUORUM = 2
        private const val FARMLAND_OCR_RETRY_MS = 500L
        private const val FARMLAND_PHASE_TIMEOUT_MS = 60_000L

        private val ONE_CLICK_CONTEXTS = listOf("流光加速", "农场升级")
        // Verified on the 2560x1600 v0.3.14 failure trace. These aliases are
        // accepted only inside ONE_CLICK_SPEC and with a valid action context.
        private val ONE_CLICK_OCR_VARIANTS = listOf(
            "一键务衣",
            "一健务衣",
            "二键务衣",
        )
        // Temporary anchor from the historical device sample. Do not use
        // generic disclaimer phrases such as “适度游戏” until a real modal
        // screenshot is available for a more precise rule.
        private val REST_CONTEXTS = listOf("请您休息一下")
        private val HARVEST_REWARD_CONTEXTS = listOf(
            "农场经验",
            "经验",
            "XP",
        )
        private val STRONG_POPUP_CONTEXTS = listOf(
            "今日内不再弹出",
            "活动公告",
            "更新公告",
            "系统公告",
            "登录公告",
            "温馨提示",
        )
        private val BANNER_POPUP_CONTEXTS = setOf(
            "狼来了",
            "小兵限时登场",
            "限时登场",
        )
        private val ONE_CLICK_SPEC = TextBoxSpec(
            left = 0.50,
            top = 0.42,
            right = 0.80,
            bottom = 0.86,
            maxWidth = 0.18,
            maxHeight = 0.12,
        )
        private val ONE_CLICK_CONTEXT_SPEC = TextBoxSpec(
            left = 0.45,
            top = 0.25,
            right = 0.90,
            bottom = 0.98,
            maxWidth = 0.30,
            maxHeight = 0.18,
        )
        private val HARVEST_CONTINUE_SPEC = TextBoxSpec(
            left = 0.20,
            top = 0.40,
            right = 0.80,
            bottom = 0.94,
            maxWidth = 0.25,
            maxHeight = 0.15,
        )
        private val HARVEST_HEADER_SPEC = TextBoxSpec(
            left = 0.20,
            top = 0.12,
            right = 0.80,
            bottom = 0.64,
            maxWidth = 0.35,
            maxHeight = 0.16,
        )
        private val HARVEST_REWARD_SPEC = TextBoxSpec(
            left = 0.18,
            top = 0.18,
            right = 0.82,
            bottom = 0.84,
            maxWidth = 0.30,
            maxHeight = 0.16,
        )
        private val REST_CONFIRM_SPEC = TextBoxSpec(
            left = 0.25,
            top = 0.35,
            right = 0.75,
            bottom = 0.90,
            maxWidth = 0.25,
            maxHeight = 0.15,
        )
        private val POPUP_CONTEXT_SPEC = TextBoxSpec(
            left = 0.10,
            top = 0.08,
            right = 0.90,
            bottom = 0.90,
            maxWidth = 0.45,
            maxHeight = 0.16,
        )
    }

    private data class HarvestOutcome(
        val detected: Boolean,
        val info: HarvestInfo?,
    )

    private data class OneClickEvidence(
        val target: OcrTarget? = null,
        val context: OcrTarget? = null,
        val contextPhrase: String? = null,
    )

    private data class OneClickTextMatch(
        val rawText: String,
        val box: com.lispace.wzryncauto.ocr.HarvestScreenTextBox,
    )

    private data class HarvestFrame(
        val observation: HarvestOcrObservation?,
        val target: OcrTarget?,
        val kind: HarvestPopupKind?,
        val hasPopupEvidence: Boolean,
        val farmAnchors: List<String>,
        val blockingReason: String?,
    ) {
        val isAbsentCandidate: Boolean
            get() = observation != null &&
                target == null &&
                !hasPopupEvidence &&
                blockingReason == null &&
                farmAnchors.size >= FARM_READY_MIN_ANCHORS

        val reason: String
            get() = when {
                observation == null -> "OCR 未返回可验证结果"
                blockingReason != null -> blockingReason
                hasPopupEvidence -> "识别到弹窗文字，但缺少安全点击证据"
                farmAnchors.size < FARM_READY_MIN_ANCHORS ->
                    "未识别到足够的农场页面文字锚点"
                else -> "等待第二帧确认"
            }
    }

    private enum class HarvestPopupKind {
        COMPLETE,
        LEGACY_WITH_REWARD_CONTEXT,
    }

    private data class HarvestTarget(
        val target: OcrTarget,
        val kind: HarvestPopupKind,
    )

    private sealed interface RestPromptState {
        data object Absent : RestPromptState

        data class Present(
            val context: String,
            val target: OcrTarget,
        ) : RestPromptState

        data class Partial(val reason: String) : RestPromptState
    }

    private data class OcrTarget(
        val phrase: String,
        val centerX: Int,
        val centerY: Int,
    )

    private data class TextBoxSpec(
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val maxWidth: Double,
        val maxHeight: Double,
    )

    private enum class FarmlandCategory {
        PLANTED,
        MATURE,
        EMPTY,
        UNKNOWN,
    }
}
