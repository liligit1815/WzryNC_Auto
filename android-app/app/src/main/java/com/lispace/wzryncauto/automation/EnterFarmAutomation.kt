package com.lispace.wzryncauto.automation

import com.lispace.wzryncauto.ocr.HarvestUiObservation
import com.lispace.wzryncauto.ocr.findTextBox
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

class AutomationFailure(message: String) : IllegalStateException(message)

/**
 * Navigates from a clean game launch to a text-verified farm page.
 *
 * Every tap is backed by OCR coordinates from the current frame, or by a
 * close-image target found in a freshly captured frame.
 */
class EnterFarmAutomation(
    private val runtime: AutomationRuntime,
    private val control: AutomationControl = AlwaysRunningControl,
    private val onState: (AutomationState, String) -> Unit = { _, _ -> },
    private val onLog: (String) -> Unit = {},
) {
    private var state = AutomationState.IDLE

    suspend fun run(): AutomationState {
        transition(AutomationState.PREPARING, "准备执行")
        check(runtime.checkRoot()) { "未获得 ROOT 权限" }

        transition(AutomationState.CHECKING_GAME, "检查游戏状态")
        if (runtime.isGameRunning()) {
            operation("检测到游戏进程，重新启动以确保初始状态")
            runtime.stopGame()
            wait(5_000)
        }

        transition(AutomationState.LAUNCHING_GAME, "启动王者荣耀")
        runtime.launchGame()

        transition(AutomationState.WAITING_LOGIN, "等待游戏交互层就绪")
        operation("启动后等待${START_GAME_MIN_READY_DELAY_MS / 1_000}秒，随后每${START_GAME_DETECT_RETRY_MS / 1_000}秒检测一次开始页面")
        wait(START_GAME_MIN_READY_DELAY_MS)
        val startTarget = requireStartGameTarget(
            timeoutMs = 60_000,
        )

        transition(AutomationState.CLICKING_START_GAME, "点击开始游戏")
        clickStartGame(startTarget)

        transition(AutomationState.CLOSING_AD_POPUPS, "等待8秒并处理广告弹窗")
        val returnWelfareBackTapped = closeAdvertisementRounds()

        transition(AutomationState.WAITING_LOBBY, "等待王者农场入口")
        val farmEntry = requireFarmEntryTarget(
            timeoutMs = 90_000,
            returnWelfareBackAlreadyTapped = returnWelfareBackTapped,
        )

        transition(AutomationState.ENTERING_FARM, "进入农场")
        clickEnterFarm(farmEntry)
        awaitActionStability(
            label = "进入农场点击后",
            timeoutMs = ENTER_FARM_STABILITY_TIMEOUT_MS,
        )
        requireStableFarmPage(
            timeoutMs = 60_000,
            failure = "等待农场页面文字锚点超时",
        )

        transition(AutomationState.RESETTING_POSITION, "农场已就绪")
        return state
    }

    private suspend fun clickStartGame(initialTarget: NavigationTextTarget) {
        // requireStartGameTarget already confirmed this target in two fresh
        // frames. Tap it immediately: delaying after OCR creates a time-of-check
        // to time-of-use race with the transitioning Unity page.
        tapStartTarget(initialTarget, "开始游戏（OCR）")
        wait((START_POST_CLICK_NO_INPUT_MS - POST_CLICK_EXTRA_WAIT_MS).coerceAtLeast(0L))

        val retryTarget = awaitStableStartTargetOrExit("开始游戏首次点击后")
            ?: return onLog("开始游戏页面已连续确认离开")

        // A swallowed first input is possible, but a retry is allowed only
        // after the no-input window and two stable, freshly captured frames.
        // The newest coordinate is tapped immediately and at most once.
        tapStartTarget(retryTarget, "开始游戏受控重试（1/1）")
        wait((START_POST_CLICK_NO_INPUT_MS - POST_CLICK_EXTRA_WAIT_MS).coerceAtLeast(0L))
        val survived = awaitStableStartTargetOrExit("开始游戏受控重试后")
        if (survived != null) {
            throw AutomationFailure("开始游戏受控重试后页面仍未离开，本轮已终止")
        }
        onLog("开始游戏页面已连续确认离开")
    }

    private suspend fun tapStartTarget(target: NavigationTextTarget, label: String) {
        operation("$label（${target.centerX}, ${target.centerY}）")
        runtime.tap(target.centerX, target.centerY)
        wait(POST_CLICK_EXTRA_WAIT_MS)
    }

    private suspend fun awaitStableStartTargetOrExit(label: String): NavigationTextTarget? {
        var previousTarget: NavigationTextTarget? = null
        var stableStartFrames = 0
        var absentFrames = 0
        var rankFrames = 0

        repeat(START_TRANSITION_OBSERVATIONS) { index ->
            val ui = readNavigationUi("$label 第${index + 1}次检查")
            if (ui == null) {
                previousTarget = null
                stableStartFrames = 0
                absentFrames = 0
                rankFrames = 0
                onLog("$label OCR 暂不可用，不据此点击或判定页面离开")
            } else {
                locateLegalConsentContext(ui)?.let { context ->
                    throw AutomationFailure("检测到“$context”，请用户在手机上阅读并手动选择后重试")
                }
                val rankEvidence = rankPageEvidence(ui)
                rankFrames = if (isRankPage(rankEvidence)) rankFrames + 1 else 0
                if (rankFrames >= RANK_PAGE_CONFIRM_FRAMES) {
                    throw AutomationFailure(
                        "检测到排位赛页面（${rankEvidence.joinToString("、")}），禁止继续点击",
                    )
                }

                val current = locateTextTarget(ui, START_GAME_SPEC)
                if (current == null) {
                    previousTarget = null
                    stableStartFrames = 0
                    absentFrames += 1
                    if (absentFrames >= START_PAGE_EXIT_QUORUM) return null
                } else {
                    absentFrames = 0
                    stableStartFrames = if (
                        previousTarget != null && targetsAreStable(previousTarget, current)
                    ) {
                        stableStartFrames + 1
                    } else {
                        1
                    }
                    previousTarget = current
                    if (stableStartFrames >= START_PAGE_RETRY_QUORUM) {
                        onLog(
                            "$label 连续$stableStartFrames 帧仍为稳定开始页，" +
                                "允许一次受控重试",
                        )
                        return current
                    }
                }
            }
            if (index < START_TRANSITION_OBSERVATIONS - 1) {
                wait(START_TRANSITION_OBSERVATION_DELAY_MS)
            }
        }
        throw AutomationFailure("$label 状态无法连续确认，禁止重试或继续")
    }

    /**
     * The post-login advertisement phase is deliberately bounded. Each round
     * reads a fresh OCR frame and permits either a text-confirmed close click,
     * or a close-image click confirmed by two fresh frames plus a dimmed modal
     * backdrop. A stable farm entry ends this phase immediately.
     */
    private suspend fun closeAdvertisementRounds(): Boolean {
        operation("开始游戏点击后等待${AD_INITIAL_WAIT_MS / 1_000}秒，再检查广告弹窗")
        wait(AD_INITIAL_WAIT_MS)
        var rankFrames = 0
        var previousFarmTarget: NavigationTextTarget? = null
        var previousReturnWelfare: NavigationTextTarget? = null
        var returnWelfareBackTapped = false
        repeat(AD_CHECK_ROUNDS) { index ->
            control.awaitRunnable()
            val round = index + 1
            val ui = readNavigationUi("广告弹窗第${round}轮")
            val returnWelfare = ui?.let(::locateReturnWelfarePage)
            if (returnWelfare != null) {
                previousFarmTarget = null
                rankFrames = 0
                if (
                    previousReturnWelfare != null &&
                    targetsAreStable(previousReturnWelfare, returnWelfare)
                ) {
                    if (returnWelfareBackTapped) {
                        throw AutomationFailure(
                            "回归福利页面点击返回后仍连续两帧存在，" +
                                "本轮已终止，禁止重复点击",
                        )
                    }
                    clickReturnWelfareBack(returnWelfare, "广告处理阶段")
                    returnWelfareBackTapped = true
                    previousReturnWelfare = null
                } else {
                    previousReturnWelfare = returnWelfare
                    if (index < AD_CHECK_ROUNDS - 1) {
                        wait(RETURN_WELFARE_CONFIRM_DELAY_MS)
                    }
                }
                return@repeat
            }
            previousReturnWelfare = null
            val currentFarmTarget = ui?.let(::locateEnterFarmText)
            val popupResult = processPopup(
                ui = ui,
                label = "广告弹窗第${round}轮",
                // A visible farm entry proves that the lobby is already
                // actionable. Do not let a visual-only X match on a static
                // lobby/shop banner take precedence over that OCR evidence.
                // Text-confirmed popups are still handled by processPopup.
                allowVisualWithoutText = currentFarmTarget == null,
            )
            when (popupResult) {
                PopupProcessResult.HANDLED -> {
                    previousFarmTarget = null
                    rankFrames = 0
                    return@repeat
                }
                PopupProcessResult.UNRESOLVED -> {
                    previousFarmTarget = null
                    rankFrames = 0
                    onLog("广告弹窗第${round}/$AD_CHECK_ROUNDS 轮证据尚未稳定，继续等待")
                    if (index < AD_CHECK_ROUNDS - 1) wait(AD_CHECK_INTERVAL_MS)
                    return@repeat
                }
                PopupProcessResult.ABSENT -> Unit
            }

            if (
                currentFarmTarget != null &&
                previousFarmTarget != null &&
                targetsAreStable(previousFarmTarget, currentFarmTarget)
            ) {
                onLog("广告处理阶段已连续确认王者农场入口，进入大厅导航")
                return returnWelfareBackTapped
            }
            previousFarmTarget = currentFarmTarget

            val rankEvidence = if (currentFarmTarget == null) {
                ui?.let(::rankPageEvidence).orEmpty()
            } else {
                emptySet()
            }
            rankFrames = if (isRankPage(rankEvidence)) rankFrames + 1 else 0
            if (rankFrames >= RANK_PAGE_CONFIRM_FRAMES) {
                throw AutomationFailure(
                    "广告处理阶段检测到排位赛页面（${rankEvidence.joinToString("、")}），" +
                        "禁止继续点击",
                )
            }
            onLog("广告弹窗第${round}/$AD_CHECK_ROUNDS 轮未识别到弹窗")
            if (index < AD_CHECK_ROUNDS - 1) {
                wait(AD_CHECK_INTERVAL_MS)
            }
        }
        return returnWelfareBackTapped
    }

    private suspend fun clickEnterFarm(target: NavigationTextTarget) {
        operation(
            "进入农场（OCR ${target.phrase}，${target.centerX}, ${target.centerY}）",
        )
        wait(FARM_ENTRY_CLICK_DELAY_MS)
        runtime.tap(target.centerX, target.centerY)
        wait(POST_CLICK_EXTRA_WAIT_MS)
        wait(FARM_ENTRY_AFTER_CLICK_DELAY_MS)
    }

    private suspend fun requireStartGameTarget(timeoutMs: Long): NavigationTextTarget {
        var elapsed = 0L
        val startedAtNanos = System.nanoTime()
        var popupDismissals = 0
        var attempts = 0
        var previousTarget: NavigationTextTarget? = null
        var unresolvedPopupPhrase: String? = null
        val maxAttempts = timeoutMs / START_GAME_DETECT_RETRY_MS + 2
        while (elapsed <= timeoutMs && attempts++ < maxAttempts) {
            control.awaitRunnable()
            val ui = readNavigationUi("开始游戏")
            elapsed = maxOf(elapsed, elapsedSince(startedAtNanos))
            if (elapsed > timeoutMs) break
            if (ui != null) {
                locateLegalConsentContext(ui)?.let { context ->
                    throw AutomationFailure(
                        "检测到“$context”，请用户在手机上阅读并手动选择后重试",
                    )
                }
            }
            if (ui != null) {
                val detectedPopup = locateKnownPopupContext(ui)
                when (processPopup(
                    ui = ui,
                    label = "开始游戏",
                    allowVisualWithoutText = false,
                )) {
                    PopupProcessResult.HANDLED -> {
                        popupDismissals += 1
                        previousTarget = null
                        unresolvedPopupPhrase = null
                        if (popupDismissals > MAX_POPUP_DISMISSALS) {
                            throw AutomationFailure("开始游戏连续处理弹窗次数过多")
                        }
                        continue
                    }
                    PopupProcessResult.UNRESOLVED -> {
                        previousTarget = null
                        unresolvedPopupPhrase = detectedPopup?.phrase
                        val delay = START_GAME_DETECT_RETRY_MS.coerceAtMost(timeoutMs - elapsed)
                        if (delay > 0) wait(delay)
                        elapsed += delay
                        continue
                    }
                    PopupProcessResult.ABSENT -> unresolvedPopupPhrase = null
                }
            }
            val current = ui?.let { locateTextTarget(it, START_GAME_SPEC) }
            if (current != null && previousTarget != null && targetsAreStable(previousTarget, current)) {
                operation("开始游戏 OCR 连续确认（${current.centerX}, ${current.centerY}）")
                return current
            }
            previousTarget = current
            val delay = (if (current == null) {
                START_GAME_DETECT_RETRY_MS
            } else {
                START_GAME_CONFIRM_DELAY_MS
            }).coerceAtMost(timeoutMs - elapsed)
            if (delay > 0) wait(delay)
            elapsed += delay
        }
        unresolvedPopupPhrase?.let { phrase ->
            throw AutomationFailure("已识别弹窗“$phrase”，但未定位到安全的关闭按钮")
        }
        throw AutomationFailure("等待开始游戏文字超时")
    }

    private suspend fun requireFarmEntryTarget(
        timeoutMs: Long,
        returnWelfareBackAlreadyTapped: Boolean,
    ): NavigationTextTarget {
        var elapsed = 0L
        val startedAtNanos = System.nanoTime()
        var previous: NavigationTextTarget? = null
        var previousReturnWelfare: NavigationTextTarget? = null
        var returnWelfareBackTapped = returnWelfareBackAlreadyTapped
        var popupDismissals = 0
        var rankFrames = 0
        var shopFrames = 0
        var attempts = 0
        val maxAttempts = timeoutMs / FARM_ENTRY_CONFIRM_DELAY_MS + 2
        while (elapsed <= timeoutMs && attempts++ < maxAttempts) {
            control.awaitRunnable()
            val ui = readNavigationUi("王者农场入口")
            elapsed = maxOf(elapsed, elapsedSince(startedAtNanos))
            if (elapsed > timeoutMs) break
            if (ui != null) {
                locateLegalConsentContext(ui)?.let { context ->
                    throw AutomationFailure("检测到“$context”，请用户在手机上阅读并手动选择后重试")
                }
            }

            val returnWelfare = ui?.let(::locateReturnWelfarePage)
            if (returnWelfare != null) {
                previous = null
                rankFrames = 0
                shopFrames = 0
                if (
                    previousReturnWelfare != null &&
                    targetsAreStable(previousReturnWelfare, returnWelfare)
                ) {
                    if (returnWelfareBackTapped) {
                        throw AutomationFailure(
                            "回归福利页面点击返回后仍连续两帧存在，" +
                                "本轮已终止，禁止重复点击",
                        )
                    }
                    clickReturnWelfareBack(returnWelfare, "等待王者农场入口阶段")
                    returnWelfareBackTapped = true
                    previousReturnWelfare = null
                    continue
                }
                previousReturnWelfare = returnWelfare
                val delay = RETURN_WELFARE_CONFIRM_DELAY_MS.coerceAtMost(timeoutMs - elapsed)
                if (delay > 0) wait(delay)
                elapsed += delay
                continue
            }
            previousReturnWelfare = null

            val current = ui?.let(::locateEnterFarmText)
            if (ui != null) {
                when (processPopup(
                    ui = ui,
                    label = "王者农场入口",
                    // The farm entry is the safer target. Visual-only close
                    // matching is intentionally disabled whenever this frame
                    // already exposes the navigation target; known popup text
                    // can still prove and close a real modal.
                    allowVisualWithoutText = current == null,
                )) {
                    PopupProcessResult.HANDLED -> {
                        popupDismissals += 1
                        if (popupDismissals > MAX_POPUP_DISMISSALS) {
                            throw AutomationFailure("王者农场入口连续处理弹窗次数过多")
                        }
                        previous = null
                        rankFrames = 0
                        shopFrames = 0
                        continue
                    }
                    PopupProcessResult.UNRESOLVED -> {
                        previous = null
                        rankFrames = 0
                        shopFrames = 0
                        val delay = FARM_ENTRY_DETECT_RETRY_MS.coerceAtMost(timeoutMs - elapsed)
                        if (delay > 0) wait(delay)
                        elapsed += delay
                        continue
                    }
                    PopupProcessResult.ABSENT -> Unit
                }
            }

            // 入口文字是这一阶段的导航目标。连续两帧确认后直接返回，
            // 且入口证据优先于页面保护文字，避免聊天栏“召集5v5排位赛”
            // 或大厅固定“商城”入口覆盖真实的农场目标。
            if (current != null && previous != null && targetsAreStable(previous, current)) {
                operation(
                    "王者农场入口 OCR 连续确认（${current.centerX}, ${current.centerY}）",
                )
                return current
            }

            if (current == null) {
                shopFrames = if (ui != null && isShopPage(ui)) shopFrames + 1 else 0
                if (shopFrames >= SHOP_PAGE_CONFIRM_FRAMES) {
                    throw AutomationFailure("检测到商城页面，禁止继续点击；本轮已停止保护设备")
                }
                val rankEvidence = ui?.let(::rankPageEvidence).orEmpty()
                rankFrames = if (isRankPage(rankEvidence)) rankFrames + 1 else 0
                if (rankFrames >= RANK_PAGE_CONFIRM_FRAMES) {
                    throw AutomationFailure(
                        "检测到排位赛页面（${rankEvidence.joinToString("、")}），" +
                            "等待农场入口已提前终止",
                    )
                }
            } else {
                rankFrames = 0
                shopFrames = 0
            }
            previous = current
            val delay = if (current == null) {
                FARM_ENTRY_DETECT_RETRY_MS
            } else {
                FARM_ENTRY_CONFIRM_DELAY_MS
            }.coerceAtMost(timeoutMs - elapsed)
            if (delay > 0) wait(delay)
            elapsed += delay
        }
        throw AutomationFailure("等待王者农场入口文字超时")
    }

    private suspend fun clickReturnWelfareBack(
        target: NavigationTextTarget,
        phase: String,
    ) {
        val backX = (target.sourceWidth * RETURN_WELFARE_BACK_X_RATIO).roundToInt()
        val backY = (target.sourceHeight * RETURN_WELFARE_BACK_Y_RATIO).roundToInt()
        operation(
            "${phase}连续两帧识别到“回归福利”，" +
                "点击左上角返回按钮（$backX, $backY）",
        )
        runtime.tap(backX, backY)
        wait(POST_CLICK_EXTRA_WAIT_MS)
        awaitActionStability("退出回归福利后")
        wait(RETURN_WELFARE_RESUME_DELAY_MS)
    }

    /**
     * A farm frame is accepted only when it contains at least two independent
     * page labels. A second frame must repeat at least one of those labels.
     */
    private suspend fun requireStableFarmPage(
        timeoutMs: Long,
        failure: String,
    ) {
        var elapsed = 0L
        val startedAtNanos = System.nanoTime()
        var previousAnchors: Set<String>? = null
        var popupDismissals = 0
        var attempts = 0
        val maxAttempts = timeoutMs / STABILITY_FRAME_DELAY_MS + 2

        while (elapsed <= timeoutMs && attempts++ < maxAttempts) {
            control.awaitRunnable()
            val ui = readNavigationUi("农场页面")
            elapsed = maxOf(elapsed, elapsedSince(startedAtNanos))
            if (elapsed > timeoutMs) break
            val anchors = ui?.let(::locateFarmPageAnchors).orEmpty()
            when (processPopup(
                ui = ui,
                label = "农场页面",
                allowVisualWithoutText = false,
            )) {
                PopupProcessResult.HANDLED -> {
                    popupDismissals += 1
                    if (popupDismissals > MAX_POPUP_DISMISSALS) {
                        throw AutomationFailure("农场页面连续处理弹窗次数过多")
                    }
                    previousAnchors = null
                    continue
                }
                PopupProcessResult.UNRESOLVED -> {
                    previousAnchors = null
                    val delay = NAVIGATION_RETRY_MS.coerceAtMost(timeoutMs - elapsed)
                    if (delay > 0) wait(delay)
                    elapsed += delay
                    continue
                }
                PopupProcessResult.ABSENT -> Unit
            }

            if (
                anchors.size >= MIN_FARM_PAGE_ANCHORS &&
                previousAnchors != null &&
                anchors.intersect(previousAnchors.orEmpty()).isNotEmpty()
            ) {
                onLog("农场页面文字已连续确认：${anchors.joinToString("、")}")
                return
            }
            previousAnchors = anchors.takeIf { it.size >= MIN_FARM_PAGE_ANCHORS }

            if (elapsed == timeoutMs) break
            val delay = (if (anchors.size < MIN_FARM_PAGE_ANCHORS) NAVIGATION_RETRY_MS else STABILITY_FRAME_DELAY_MS)
                .coerceAtMost(timeoutMs - elapsed)
            if (delay > 0) wait(delay)
            elapsed += delay
        }
        throw AutomationFailure(failure)
    }

    private suspend fun processPopup(
        ui: HarvestUiObservation?,
        label: String,
        allowVisualWithoutText: Boolean,
    ): PopupProcessResult {
        val evidence = inspectPopup(ui, allowVisualWithoutText)
            ?: return PopupProcessResult.ABSENT
        return when (evidence) {
            is PopupEvidence.Rest -> {
                wait(POPUP_CONFIRM_DELAY_MS)
                val freshUi = readNavigationUi("$label 弹窗二次确认")
                    ?: throw AutomationFailure("休息提示点击前 OCR 不可用")
                val confirmed = locateRestReminderConfirm(freshUi)
                    ?: throw AutomationFailure("休息提示未通过连续两帧确认，禁止点击")
                if (!targetsAreStable(evidence.target, confirmed)) {
                    throw AutomationFailure("休息提示确认按钮坐标漂移，禁止点击")
                }
                operation(
                    "关闭休息提示（OCR ${confirmed.phrase}，" +
                        "${confirmed.centerX}, ${confirmed.centerY}）",
                )
                runtime.tap(confirmed.centerX, confirmed.centerY)
                wait(POST_CLICK_EXTRA_WAIT_MS)
                awaitActionStability("关闭休息提示后")
                wait(POPUP_RESUME_DELAY_MS)
                PopupProcessResult.HANDLED
            }
            is PopupEvidence.Known -> if (closeKnownPopup(evidence, label)) {
                PopupProcessResult.HANDLED
            } else {
                PopupProcessResult.UNRESOLVED
            }
            is PopupEvidence.Visual -> {
                val confirmed = confirmVisualPopup(evidence.target, label)
                    ?: return PopupProcessResult.UNRESOLVED
                closePopupByImage(
                    label = label,
                    closeTarget = confirmed,
                    reason = "关闭图与弹窗背景连续确认",
                )
                PopupProcessResult.HANDLED
            }
        }
    }

    private suspend fun inspectPopup(
        ui: HarvestUiObservation?,
        allowVisualWithoutText: Boolean,
    ): PopupEvidence? {
        if (ui == null) return null
        locateLegalConsentContext(ui)?.let { context ->
            throw AutomationFailure("检测到“$context”，请用户在手机上阅读并手动选择后重试")
        }
        val restContext = locateRestReminderContext(ui)
        val restTarget = locateRestReminderConfirm(ui)
        if (restContext != null) {
            if (restTarget == null) {
                throw AutomationFailure("识别到休息提示“$restContext”，但未定位安全的确认按钮")
            }
            return PopupEvidence.Rest(restTarget)
        }

        val popupContext = locateKnownPopupContext(ui)
        if (popupContext != null) {
            // Promotional dialogs often animate their artwork for several
            // frames. OCR can therefore miss the same context, or recognize
            // the title in one frame and the footer in another. Keep the
            // safety rule of two modal-specific context hits, but allow those
            // two forms of evidence to corroborate each other.
            // The first context was already found in the frame that caused
            // this branch; one compatible fresh frame completes the quorum.
            return PopupEvidence.Known(popupContext)
        }
        // Do not spend a visual close attempt on a completely empty OCR frame.
        // Real promotional modals still expose artwork/footer text even when
        // the exact known context phrase is misread.
        if (allowVisualWithoutText && ui.rawText.isNotBlank()) {
            val closeTarget = runtime.findPopupCloseTarget() ?: return null
            if (closeTarget.popupContextScore < MIN_POPUP_CONTEXT_SCORE) {
                onLog("页面存在但未确认弹窗背景，忽略关闭图片命中")
                return null
            }
            return PopupEvidence.Visual(closeTarget)
        }
        return null
    }

    private suspend fun closeKnownPopup(
        evidence: PopupEvidence.Known,
        label: String,
    ): Boolean {
        var confirmedHits = 1
        for (attempt in 1..POPUP_CONTEXT_CONFIRM_ATTEMPTS) {
            wait(popupConfirmDelayFor(label))
            val freshUi = readNavigationUi("$label 弹窗确认 $attempt")
            val confirmed = freshUi?.let(::locateKnownPopupContext)
            if (freshUi == null || confirmed == null ||
                !popupContextsAreCompatible(evidence.context, confirmed)
            ) {
                onLog(
                    "$label 弹窗确认帧未命中兼容上下文" +
                        "（$attempt/$POPUP_CONTEXT_CONFIRM_ATTEMPTS），继续取帧",
                )
                continue
            }
            confirmedHits += 1
            if (confirmedHits >= POPUP_CONTEXT_REQUIRED_HITS) {
                val searchProfile = PopupCloseSearchProfile.forPopupPhrase(confirmed.phrase)
                val closeTarget = runtime.findPopupCloseTarget(
                    searchProfile,
                )
                if (closeTarget == null) {
                    if (confirmed.phrase in BANNER_POPUP_CONTEXTS) {
                        onLog("识别到普通页面横幅“${confirmed.phrase}”，不执行关闭")
                    } else {
                        onLog("$label 已确认弹窗文字，但当前帧未匹配到关闭图，继续等待下一帧")
                    }
                    return false
                }
                return closePopupByImage(
                    label = label,
                    closeTarget = closeTarget,
                    reason = "识别到弹窗文字“${confirmed.phrase}”",
                    clickDelayMs = popupClickDelayFor(label),
                    searchProfile = searchProfile,
                    verifyDismissal = searchProfile ==
                        PopupCloseSearchProfile.UPDATE_ANNOUNCEMENT,
                )
            }
            onLog(
                "$label 弹窗上下文已确认 $confirmedHits/$POPUP_CONTEXT_REQUIRED_HITS" +
                    "（${confirmed.phrase}）",
            )
        }
        throw AutomationFailure("通用弹窗未通过多帧确认，禁止点击关闭按钮")
    }

    private suspend fun confirmVisualPopup(
        first: PopupCloseTarget,
        label: String,
    ): PopupCloseTarget? {
        wait(popupConfirmDelayFor(label))
        val second = runtime.findPopupCloseTarget()
        if (second == null || !closeTargetsAreStable(first, second)) {
            onLog("$label 关闭图未通过连续两帧位置确认，忽略点击")
            return null
        }
        return second
    }

    private suspend fun closePopupByImage(
        label: String,
        closeTarget: PopupCloseTarget,
        reason: String,
        clickDelayMs: Long = POPUP_CLICK_DELAY_MS,
        searchProfile: PopupCloseSearchProfile = PopupCloseSearchProfile.DEFAULT,
        verifyDismissal: Boolean = false,
    ): Boolean {
        operation(
            "$label 匹配到广告关闭图片，点击关闭按钮（$reason）" +
                "（${closeTarget.centerX}, ${closeTarget.centerY}，" +
                "匹配度${"%.2f".format(closeTarget.score)}）",
        )
        wait(clickDelayMs)
        runtime.tap(closeTarget.centerX, closeTarget.centerY)
        wait(POST_CLICK_EXTRA_WAIT_MS)
        awaitActionStability("关闭通用弹窗后")
        wait(POPUP_RESUME_DELAY_MS)
        if (verifyDismissal) {
            val remainingTarget = runtime.findPopupCloseTarget(searchProfile)
            if (remainingTarget != null && closeTargetsAreStable(closeTarget, remainingTarget)) {
                onLog(
                    "$label 点击后更新公告关闭按钮仍存在" +
                        "（${remainingTarget.centerX}, ${remainingTarget.centerY}），" +
                        "本次关闭未生效",
                )
                return false
            }
            onLog("$label 更新公告关闭按钮已消失")
        }
        onLog("$label 关闭后进入下一轮重新截图检查")
        return true
    }

    private fun popupClickDelayFor(label: String): Long =
        if (label == "开始游戏" || label.startsWith("广告弹窗")) {
            POPUP_CLICK_DELAY_FAST_MS
        } else {
            POPUP_CLICK_DELAY_MS
        }

    private fun popupConfirmDelayFor(label: String): Long =
        if (label == "开始游戏" || label.startsWith("广告弹窗")) {
            POPUP_CONFIRM_DELAY_FAST_MS
        } else {
            POPUP_CONFIRM_DELAY_MS
        }

    private fun closeTargetsAreStable(
        first: PopupCloseTarget,
        second: PopupCloseTarget,
    ): Boolean =
        first.sourceWidth == second.sourceWidth &&
            first.sourceHeight == second.sourceHeight &&
            abs(first.centerX - second.centerX) <=
                (second.sourceWidth * CLOSE_TARGET_STABILITY_X).toInt() &&
            abs(first.centerY - second.centerY) <=
                (second.sourceHeight * CLOSE_TARGET_STABILITY_Y).toInt()

    private sealed interface PopupEvidence {
        data class Rest(val target: NavigationTextTarget) : PopupEvidence
        data class Known(val context: NavigationTextTarget) : PopupEvidence
        data class Visual(val target: PopupCloseTarget) : PopupEvidence
    }

    private enum class PopupProcessResult {
        ABSENT,
        HANDLED,
        UNRESOLVED,
    }

    private fun locateRestReminderConfirm(ui: HarvestUiObservation): NavigationTextTarget? {
        if (locateRestReminderContext(ui) == null) return null
        return REST_CONFIRM_SPECS.firstNotNullOfOrNull { locateTextTarget(ui, it) }
    }

    private fun locateRestReminderContext(ui: HarvestUiObservation): String? =
        REST_REMINDER_CONTEXTS.firstOrNull { phrase ->
            locateTextTarget(ui, POPUP_CONTEXT_SPEC.copy(phrase = phrase)) != null
        }

    private fun locateLegalConsentContext(ui: HarvestUiObservation): String? =
        LEGAL_CONSENT_CONTEXTS.firstOrNull { phrase ->
            locateTextTarget(ui, LEGAL_CONTEXT_SPEC.copy(phrase = phrase)) != null
        }

    private fun locateKnownPopupContext(ui: HarvestUiObservation): NavigationTextTarget? =
        STRONG_POPUP_CONTEXTS.firstNotNullOfOrNull { phrase ->
            locateTextTarget(ui, POPUP_CONTEXT_SPEC.copy(phrase = phrase))
        }

    private fun targetsAreStable(
        first: NavigationTextTarget,
        second: NavigationTextTarget,
    ): Boolean =
        first.sourceWidth == second.sourceWidth &&
            first.sourceHeight == second.sourceHeight &&
            abs(first.centerX - second.centerX) <=
            (second.sourceWidth * TARGET_STABILITY_X).toInt() &&
            abs(first.centerY - second.centerY) <=
            (second.sourceHeight * TARGET_STABILITY_Y).toInt()

    private fun popupTargetsAreStable(
        first: NavigationTextTarget,
        second: NavigationTextTarget,
    ): Boolean =
        first.sourceWidth == second.sourceWidth &&
            first.sourceHeight == second.sourceHeight &&
            abs(first.centerX - second.centerX) <=
            (second.sourceWidth * POPUP_CONTEXT_STABILITY_X).toInt() &&
            abs(first.centerY - second.centerY) <=
            (second.sourceHeight * POPUP_CONTEXT_STABILITY_Y).toInt()

    private fun popupContextsAreCompatible(
        first: NavigationTextTarget,
        second: NavigationTextTarget,
    ): Boolean {
        if (first.sourceWidth != second.sourceWidth || first.sourceHeight != second.sourceHeight) {
            return false
        }
        if (first.phrase == second.phrase) {
            return popupTargetsAreStable(first, second)
        }
        return first.phrase in CROSS_FRAME_POPUP_CONTEXTS &&
            second.phrase in CROSS_FRAME_POPUP_CONTEXTS
    }

    private fun locateFarmPageAnchors(ui: HarvestUiObservation): Set<String> {
        return FarmPageTextEvidence.locate(ui)
    }

    private suspend fun readNavigationUi(label: String): HarvestUiObservation? {
        return try {
            runtime.readHarvestUi()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            onLog("$label OCR 失败：${error.message}")
            null
        }?.ui?.takeIf { ui ->
            if (!isLandscape(ui)) {
                onLog("等待王者荣耀切换横屏（${ui.sourceWidth}×${ui.sourceHeight}）")
                false
            } else {
                true
            }
        }
    }

    private suspend fun awaitActionStability(
        label: String,
        timeoutMs: Long = ACTION_STABILITY_TIMEOUT_MS,
    ) {
        val result = runtime.awaitScreenStable(timeoutMs, label)
        if (result.stable) {
            onLog(
                "${label}画面已稳定（${result.samples}次采样，" +
                    "${result.elapsedMs}ms）",
            )
        } else {
            // A live game can keep a small animation running forever. Let the
            // existing OCR quorum make the final page-specific decision.
            onLog("${label}画面仍有动态，进入 OCR 连续确认（${result.elapsedMs}ms）")
            wait(ACTION_STABILITY_FALLBACK_MS)
        }
    }

    private fun locateTextTarget(
        ui: HarvestUiObservation,
        spec: TextTargetSpec,
        logUnsafe: Boolean = true,
    ): NavigationTextTarget? {
        val box = ui.findTextBox(spec.phrase) ?: return null
        if (!isLandscape(ui)) return null

        val safeX = box.centerX in
            (ui.sourceWidth * spec.left).toInt() until (ui.sourceWidth * spec.right).toInt()
        val safeY = box.centerY in
            (ui.sourceHeight * spec.top).toInt() until (ui.sourceHeight * spec.bottom).toInt()
        val boxWidth = box.right - box.left
        val boxHeight = box.bottom - box.top
        val safeSize = boxWidth in 1..(ui.sourceWidth * spec.maxWidth).toInt() &&
            boxHeight in 1..(ui.sourceHeight * spec.maxHeight).toInt()
        if (!safeX || !safeY || !safeSize) {
            if (logUnsafe) {
                onLog(
                    "OCR ${spec.phrase}坐标不在安全区：" +
                        "(${box.centerX}, ${box.centerY})/${ui.sourceWidth}×${ui.sourceHeight}",
                )
            }
            return null
        }
        return NavigationTextTarget(
            phrase = spec.phrase,
            centerX = box.centerX,
            centerY = box.centerY,
            sourceWidth = ui.sourceWidth,
            sourceHeight = ui.sourceHeight,
        )
    }

    private fun locateEnterFarmText(ui: HarvestUiObservation): NavigationTextTarget? =
        ENTER_FARM_SPECS.firstNotNullOfOrNull { locateTextTarget(ui, it) }

    private fun locateReturnWelfarePage(ui: HarvestUiObservation): NavigationTextTarget? =
        locateTextTarget(ui, RETURN_WELFARE_SPEC, logUnsafe = false)

    private fun isShopPage(ui: HarvestUiObservation): Boolean {
        val title = locateTextTarget(ui, SHOP_PAGE_TITLE_SPEC, logUnsafe = false)
            ?: return false
        val evidence = buildList {
            add("商城" to title)
            SHOP_PAGE_SECONDARY_CONTEXTS.forEach { phrase ->
                locateTextTarget(
                    ui,
                    SHOP_PAGE_SECONDARY_SPEC.copy(phrase = phrase),
                    logUnsafe = false,
                )?.let { add(phrase to it) }
            }
        }.distinctBy { (_, target) ->
            listOf(target.centerX, target.centerY, target.sourceWidth, target.sourceHeight)
        }
        return evidence.map { it.first }.toSet().let { labels ->
            "商城" in labels && labels.any { it in SHOP_PAGE_SECONDARY_CONTEXTS }
        }
    }

    private fun rankPageEvidence(ui: HarvestUiObservation): Set<String> = buildList {
        RANK_PAGE_STRONG_CONTEXTS.forEach { phrase ->
            locateTextTarget(
                ui,
                RANK_PAGE_STRONG_SPEC.copy(phrase = phrase),
                logUnsafe = false,
            )?.let { add(phrase to it) }
        }
        RANK_PAGE_SECONDARY_CONTEXTS.forEach { phrase ->
            locateTextTarget(
                ui,
                RANK_PAGE_SECONDARY_SPEC.copy(phrase = phrase),
                logUnsafe = false,
            )?.let { add(phrase to it) }
        }
    }.distinctBy { (_, target) ->
        listOf(target.centerX, target.centerY, target.sourceWidth, target.sourceHeight)
    }.mapTo(linkedSetOf()) { (phrase, _) -> phrase }

    private fun isRankPage(evidence: Set<String>): Boolean =
        evidence.any { it in RANK_PAGE_STRONG_CONTEXTS } &&
            evidence.count { it in RANK_PAGE_SECONDARY_CONTEXTS } >= RANK_PAGE_MIN_SECONDARY_CONTEXTS

    private fun isLandscape(ui: HarvestUiObservation): Boolean =
        ui.sourceWidth > ui.sourceHeight && ui.sourceWidth > 0 && ui.sourceHeight > 0

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
        (System.nanoTime() - startedAtNanos) / 1_000_000L

    private fun transition(next: AutomationState, message: String) {
        AutomationTransitionPolicy.requireTransition(state, next)
        state = next
        onState(next, message)
        onLog(message)
    }

    private fun operation(message: String) {
        onLog(message)
        onState(state, message)
    }

    companion object {
        private const val AD_INITIAL_WAIT_MS = 8_000L
        private const val POST_CLICK_EXTRA_WAIT_MS = 2_000L
        private const val AD_CHECK_ROUNDS = 5
        private const val AD_CHECK_INTERVAL_MS = 1_000L
        private const val START_GAME_MIN_READY_DELAY_MS = 10_000L
        private const val START_GAME_DETECT_RETRY_MS = 1_000L
        private const val START_GAME_CONFIRM_DELAY_MS = 500L
        private const val START_POST_CLICK_NO_INPUT_MS = 8_000L
        private const val START_TRANSITION_OBSERVATIONS = 8
        private const val START_TRANSITION_OBSERVATION_DELAY_MS = 500L
        private const val START_PAGE_EXIT_QUORUM = 2
        private const val START_PAGE_RETRY_QUORUM = 2
        private const val FARM_ENTRY_DETECT_RETRY_MS = 1_000L
        private const val FARM_ENTRY_CONFIRM_DELAY_MS = 500L
        private const val FARM_ENTRY_CLICK_DELAY_MS = 500L
        private const val FARM_ENTRY_AFTER_CLICK_DELAY_MS = 5_000L
        private const val RETURN_WELFARE_CONFIRM_DELAY_MS = 500L
        private const val RETURN_WELFARE_RESUME_DELAY_MS = 1_000L
        private const val RETURN_WELFARE_BACK_X_RATIO = 0.096
        private const val RETURN_WELFARE_BACK_Y_RATIO = 0.055
        private const val ENTER_FARM_STABILITY_TIMEOUT_MS = 15_000L
        private const val POPUP_CONFIRM_DELAY_MS = 1_000L
        private const val POPUP_CONFIRM_DELAY_FAST_MS = 500L
        private const val POPUP_CLICK_DELAY_MS = 1_000L
        private const val POPUP_CLICK_DELAY_FAST_MS = 500L
        private const val POPUP_RESUME_DELAY_MS = 1_000L
        private const val POPUP_CONTEXT_CONFIRM_ATTEMPTS = 8
        private const val POPUP_CONTEXT_REQUIRED_HITS = 2
        private const val ACTION_STABILITY_TIMEOUT_MS = 4_000L
        private const val ACTION_STABILITY_FALLBACK_MS = 1_000L
        private const val NAVIGATION_RETRY_MS = 1_000L
        private const val STABILITY_FRAME_DELAY_MS = 300L
        private const val MAX_POPUP_DISMISSALS = 10
        private const val MIN_POPUP_CONTEXT_SCORE = 0.08
        private const val CLOSE_TARGET_STABILITY_X = 0.04
        private const val CLOSE_TARGET_STABILITY_Y = 0.04
        private const val MIN_FARM_PAGE_ANCHORS = 2
        private const val TARGET_STABILITY_X = 0.12
        private const val TARGET_STABILITY_Y = 0.12
        private const val POPUP_CONTEXT_STABILITY_X = 0.20
        private const val POPUP_CONTEXT_STABILITY_Y = 0.20

        private val START_GAME_SPEC = TextTargetSpec(
            phrase = "开始游戏",
            left = 0.35,
            top = 0.60,
            right = 0.65,
            // The Android/Unity startup presentation also contains the same
            // text around 85% screen height, but it is not interactive. The
            // real button observed across successful runs is around 74-78%.
            bottom = 0.82,
        )
        private val ENTER_FARM_SPECS = listOf(
            "王者农场",
            "HOMESTEAD",
            "来农场",
        ).map { phrase ->
            TextTargetSpec(
                phrase = phrase,
                left = 0.15,
                top = 0.55,
                right = 0.50,
                bottom = 0.86,
            )
        }
        private val RETURN_WELFARE_SPEC = TextTargetSpec(
            phrase = "回归福利",
            left = 0.08,
            top = 0.01,
            right = 0.40,
            bottom = 0.18,
            maxWidth = 0.35,
            maxHeight = 0.15,
        )
        private val REST_CONFIRM_SPECS = listOf("确定", "我知道了").map { phrase ->
            TextTargetSpec(
                phrase = phrase,
                left = 0.30,
                top = 0.45,
                right = 0.70,
                bottom = 0.90,
            )
        }
        private val POPUP_CONTEXT_SPEC = TextTargetSpec(
            phrase = "",
            left = 0.10,
            top = 0.08,
            right = 0.90,
            bottom = 0.90,
            maxWidth = 0.70,
        )
        private val LEGAL_CONTEXT_SPEC = TextTargetSpec(
            phrase = "",
            left = 0.08,
            top = 0.05,
            right = 0.92,
            bottom = 0.90,
            maxWidth = 0.70,
            maxHeight = 0.18,
        )
        // Use the full sentence from the historical device sample as the
        // temporary rest-popup anchor. Short phrases such as “适度游戏” also
        // appear in the game's loading-page legal disclaimer and are not
        // reliable popup evidence on their own.
        private val REST_REMINDER_CONTEXTS = listOf("请您休息一下")
        private val LEGAL_CONSENT_CONTEXTS = listOf(
            "游戏许可及服务协议",
            "王者荣耀隐私保护指引",
            "儿童隐私保护指引",
        )

        // These phrases prove a modal context; generic words such as “活动”
        // and “福利” are intentionally excluded because they also occur in the lobby.
        private val STRONG_POPUP_CONTEXTS = listOf(
            "今日内不再弹出",
            "每日充值送好礼",
            "充值送好礼",
            "活动公告",
            "更新公告",
            "系统公告",
            "登录公告",
            "温馨提示",
        )
        private val CROSS_FRAME_POPUP_CONTEXTS = setOf(
            "今日内不再弹出",
            "每日充值送好礼",
            "充值送好礼",
            "活动公告",
            "更新公告",
            "系统公告",
            "登录公告",
        )
        private val BANNER_POPUP_CONTEXTS = setOf(
            "狼来了",
            "小兵限时登场",
            "限时登场",
        )
        private val SHOP_PAGE_SECONDARY_CONTEXTS = setOf(
            "推荐",
            "新品",
            "促销",
            "商品",
            "夺宝",
        )
        private const val SHOP_PAGE_CONFIRM_FRAMES = 2
        private const val RANK_PAGE_CONFIRM_FRAMES = 2
        private const val RANK_PAGE_MIN_SECONDARY_CONTEXTS = 1
        private val RANK_PAGE_STRONG_CONTEXTS = setOf(
            "5v5排位赛",
            "多人排位",
            "单/多人排位",
        )
        private val RANK_PAGE_SECONDARY_CONTEXTS = setOf(
            "房间号组队",
            "巅峰赛",
            "分路段位",
            "英雄战力",
            "赛季",
            "段位",
            "勇者积分",
            "排位保护卡",
        )
        private val SHOP_PAGE_TITLE_SPEC = TextTargetSpec(
            phrase = "商城",
            left = 0.03,
            top = 0.04,
            right = 0.90,
            bottom = 0.92,
            maxWidth = 0.30,
            maxHeight = 0.18,
        )
        private val SHOP_PAGE_SECONDARY_SPEC = TextTargetSpec(
            phrase = "",
            left = 0.03,
            top = 0.06,
            right = 0.92,
            bottom = 0.94,
            maxWidth = 0.45,
            maxHeight = 0.18,
        )
        private val RANK_PAGE_STRONG_SPEC = TextTargetSpec(
            phrase = "",
            left = 0.02,
            top = 0.05,
            right = 0.98,
            // Bottom chat messages sit around 89% screen height on the
            // target tablet. Real rank-page labels/buttons are above 86%.
            bottom = 0.86,
            maxWidth = 0.55,
            maxHeight = 0.20,
        )
        private val RANK_PAGE_SECONDARY_SPEC = TextTargetSpec(
            phrase = "",
            left = 0.02,
            top = 0.02,
            right = 0.98,
            bottom = 0.98,
            maxWidth = 0.55,
            maxHeight = 0.20,
        )
    }

    private data class TextTargetSpec(
        val phrase: String,
        val left: Double,
        val top: Double,
        val right: Double,
        val bottom: Double,
        val maxWidth: Double = 0.35,
        val maxHeight: Double = 0.15,
    )

    private data class NavigationTextTarget(
        val phrase: String,
        val centerX: Int,
        val centerY: Int,
        val sourceWidth: Int,
        val sourceHeight: Int,
    )

}
