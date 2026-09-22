package com.lispace.wzryncauto.automation

import com.lispace.wzryncauto.ocr.FarmlandState
import com.lispace.wzryncauto.ocr.HarvestInfo
import com.lispace.wzryncauto.ocr.HarvestOcrObservation
import com.lispace.wzryncauto.ocr.HarvestScreenTextBox
import com.lispace.wzryncauto.ocr.HarvestUiObservation
import com.lispace.wzryncauto.ocr.MaturityReading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class FarmActionAutomationTest {
    @Test
    fun `complete OCR-only flow clicks the first safe one-click coordinate`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                    farmUi(),
                    farmUi(),
                ),
            ),
        )
        val states = mutableListOf<AutomationState>()
        val clickedAt = LocalDateTime.of(2026, 8, 10, 12, 34, 56)
        val guard = RecordingGuard()

        val result = FarmActionAutomation(
            runtime = runtime,
            onState = { state, _ -> states += state },
            now = { clickedAt },
            oneClickGuard = guard,
        ).run()

        assertFalse(result.harvested)
        assertEquals(clickedAt, result.firstWaterAt)
        assertEquals(listOf(1520 to 617), runtime.taps)
        assertEquals(listOf(1520 to 617), guard.beforeTargets)
        assertEquals(listOf(clickedAt), guard.acceptedAt)
        assertEquals(0, guard.harvestObservedCount)
        assertEquals(
            listOf(
                SwipeGesture(430, 755, 305, 538, 1500),
                SwipeGesture(430, 755, 430, 555, 1200),
            ),
            runtime.swipes,
        )
        assertEquals(
            listOf(
                AutomationState.RESETTING_POSITION,
                AutomationState.MOVING_TO_STATUE,
                AutomationState.VERIFYING_ONE_CLICK_FARM,
                AutomationState.ONE_CLICK_FARMING,
                AutomationState.HANDLING_HARVEST,
                AutomationState.MOVING_TO_FARMLAND,
                AutomationState.VERIFYING_FARMLAND,
                AutomationState.READING_MATURITY,
            ),
            states.distinct(),
        )
    }

    @Test
    fun `one-click detection does not require a second OCR frame`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    farmUi(),
                    oneClickUi(1522, 619),
                    farmUi(),
                    farmUi(),
                ),
            ),
        )

        FarmActionAutomation(runtime).run()

        assertEquals(listOf(1520 to 617), runtime.taps)
    }

    @Test
    fun `one-click detection does not require action context`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619, includeContext = false),
                    farmUi(),
                    farmUi(),
                ),
            ),
        )

        FarmActionAutomation(runtime).run()

        assertEquals(listOf(1520 to 617), runtime.taps)
    }

    @Test
    fun `verified one-click OCR variants are accepted with action context`() = runBlocking {
        listOf("一键务衣", "一健务衣", "二键务衣").forEach { variant ->
            val runtime = FakeRuntime(
                uiReadings = ArrayDeque(
                    listOf(
                        farmUi(),
                        farmUi(),
                        oneClickUi(1520, 617, buttonText = variant),
                        farmUi(),
                        farmUi(),
                    ),
                ),
            )

            FarmActionAutomation(runtime).run()

            assertEquals("variant=$variant", listOf(1520 to 617), runtime.taps)
        }
    }

    @Test
    fun `one-click OCR variant without action context is never tapped`() = runBlocking {
        val unsafeVariant = oneClickUi(
            x = 1520,
            y = 617,
            includeContext = false,
            buttonText = "一键务衣",
        )
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(listOf(farmUi(), farmUi())),
            defaultUi = unsafeVariant,
        )

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(runtime.taps.isEmpty())
        assertEquals(1, runtime.swipes.size)
    }

    @Test
    fun `one-click detection clicks before later target OCR loss`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    contextOnlyUi(),
                    contextOnlyUi(),
                    farmUi(),
                    farmUi(),
                ),
            ),
        )

        FarmActionAutomation(runtime).run()

        assertEquals(listOf(1520 to 617), runtime.taps)
    }

    @Test
    fun `three stable context-only stream misses switch to root OCR`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    contextOnlyUi(),
                    contextOnlyUi(),
                    contextOnlyUi(),
                    farmUi(),
                    farmUi(),
                    farmUi(),
                ),
            ),
            rootUiReadings = ArrayDeque(listOf(oneClickUi(1524, 621))),
        )

        FarmActionAutomation(runtime).run()

        assertEquals(1, runtime.rootUiReadCount)
        assertEquals(listOf(1524 to 621), runtime.taps)
        assertEquals(2, runtime.swipes.size)
    }

    @Test
    fun `second forced root confirmation can recover the one-click target`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    contextOnlyUi(),
                    contextOnlyUi(),
                    contextOnlyUi(),
                    farmUi(),
                    farmUi(),
                    farmUi(),
                ),
            ),
            rootUiReadings = ArrayDeque(
                listOf(
                    contextOnlyUi(),
                    oneClickUi(1524, 621),
                ),
            ),
        )

        FarmActionAutomation(runtime).run()

        assertEquals(2, runtime.rootUiReadCount)
        assertEquals(listOf(1524 to 621), runtime.taps)
        assertEquals(2, runtime.swipes.size)
    }

    @Test
    fun `completed OCR result is evaluated before the primary timeout stops new captures`() =
        runBlocking {
            var monotonicNanos = 0L
            var uiReadCount = 0
            val runtime = FakeRuntime(
                uiReadings = ArrayDeque(
                    listOf(
                        farmUi(),
                        farmUi(),
                        oneClickUi(1520, 617),
                        farmUi(),
                        farmUi(),
                        farmUi(),
                    ),
                ),
                onHarvestUiRead = {
                    uiReadCount += 1
                    if (uiReadCount == 3) {
                        monotonicNanos += 21_000_000_000L
                    }
                },
            )

            FarmActionAutomation(
                runtime = runtime,
                monotonicNanos = { monotonicNanos },
            ).run()

            assertEquals(listOf(1520 to 617), runtime.taps)
            assertEquals(0, runtime.rootUiReadCount)
        }

    @Test
    fun `root fallback never clicks context-only evidence`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(farmUi(), farmUi()) + List(6) { contextOnlyUi() },
            ),
            rootUiReadings = ArrayDeque(List(2) { contextOnlyUi() }),
            defaultUi = contextOnlyUi(),
        )

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals(2, runtime.rootUiReadCount)
        assertTrue(runtime.taps.isEmpty())
        assertEquals(1, runtime.swipes.size)
    }

    @Test
    fun `farm page needs two anchors on two consecutive frames before movement`() = runBlocking {
        val oneAnchor = uiObservation(listOf(box("仓库", 2200, 220)))
        val runtime = FakeRuntime(defaultUi = oneAnchor)

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(runtime.swipes.isEmpty())
        assertTrue(runtime.taps.isEmpty())
    }

    @Test
    fun `one OCR box containing several farm words is only one anchor`() = runBlocking {
        val combined = uiObservation(
            listOf(box("仓库 社交 百科", 2_100, 260, width = 260)),
        )
        val runtime = FakeRuntime(defaultUi = combined)

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(runtime.swipes.isEmpty())
        assertTrue(runtime.taps.isEmpty())
    }

    @Test
    fun `one-click text outside safe ROI cannot be tapped`() = runBlocking {
        val unsafe = oneClickUi(320, 620)
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(listOf(farmUi(), farmUi())),
            defaultUi = unsafe,
        )

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals(1, runtime.swipes.size)
        assertTrue(runtime.taps.isEmpty())
    }

    @Test
    fun `one-click text without action context can be tapped`() = runBlocking {
        val noContext = oneClickUi(1522, 619, includeContext = false)
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    noContext,
                    farmUi(),
                    farmUi(),
                    farmUi(),
                ),
            ),
        )

        FarmActionAutomation(runtime).run()

        assertEquals(2, runtime.swipes.size)
        assertEquals(listOf(1522 to 619), runtime.taps)
    }

    @Test
    fun `persistent guard boundary blocks duplicate one-click tap`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(farmUi(), farmUi(), oneClickUi(1520, 617), oneClickUi(1522, 619)),
            ),
        )
        val guard = RecordingGuard(allow = false)

        val failure = runCatching {
            FarmActionAutomation(runtime = runtime, oneClickGuard = guard).run()
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals(listOf(1520 to 617), guard.beforeTargets)
        assertTrue(runtime.taps.isEmpty())
        assertEquals(1, runtime.swipes.size)
    }

    @Test
    fun `rest reminder requires context and two frames then taps fresh confirm`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    restUi(1270, 798),
                    restUi(1274, 800),
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                    farmUi(),
                    farmUi(),
                ),
            ),
        )

        FarmActionAutomation(runtime).run()

        assertEquals(listOf(1274 to 800, 1520 to 617), runtime.taps)
        assertEquals(2, runtime.swipes.size)
    }

    @Test
    fun `standalone confirm without rest context blocks all movement`() = runBlocking {
        val unknownDialog = uiObservation(listOf(box("确定", 1274, 800)))
        val runtime = FakeRuntime(defaultUi = unknownDialog)

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(runtime.taps.isEmpty())
        assertTrue(runtime.swipes.isEmpty())
    }

    @Test
    fun `closes complete harvest popup using refreshed OCR coordinate`() = runBlocking {
        val guard = RecordingGuard()
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                    completeHarvestUi(1200, 900),
                    completeHarvestUi(1200, 900),
                    completeHarvestUi(1200, 900),
                    completeHarvestUi(1204, 904),
                    farmUi(),
                    farmUi(),
                ),
            ),
            onTap = { x, _ ->
                if (x == 1204) assertEquals(1, guard.harvestObservedCount)
            },
        )

        val result = FarmActionAutomation(runtime, oneClickGuard = guard).run()

        assertTrue(result.harvested)
        assertEquals(1, guard.harvestObservedCount)
        assertEquals(listOf(1520 to 617, 1204 to 904), runtime.taps)
        assertEquals(2, runtime.swipes.size)
    }

    @Test
    fun `legacy click-continue is accepted only with reward context`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                    legacyHarvestUi(1198, 900),
                    legacyHarvestUi(1198, 900),
                    legacyHarvestUi(1198, 900),
                    legacyHarvestUi(1202, 904),
                    farmUi(),
                    farmUi(),
                ),
            ),
        )

        val result = FarmActionAutomation(runtime).run()

        assertTrue(result.harvested)
        assertEquals(66, result.harvestInfo?.experience)
        assertEquals(listOf(1520 to 617, 1202 to 904), runtime.taps)
    }

    @Test
    fun `transient final continue miss recovers on a later stable frame`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                    legacyHarvestUi(1198, 900),
                    legacyHarvestUi(1198, 900),
                    legacyHarvestUi(1199, 901),
                    congratulationsOnlyUi(),
                    legacyHarvestUi(1202, 904),
                    farmUi(),
                    farmUi(),
                ),
            ),
        )

        val result = FarmActionAutomation(runtime).run()

        assertTrue(result.harvested)
        assertEquals(listOf(1520 to 617, 1202 to 904), runtime.taps)
    }

    @Test
    fun `persistent continue misses after initial detection still forbid a blind tap`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                    legacyHarvestUi(1198, 900),
                    legacyHarvestUi(1198, 900),
                    legacyHarvestUi(1199, 901),
                ) + List(5) { congratulationsOnlyUi() },
            ),
        )

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(failure?.message.orEmpty().contains("多帧确认"))
        assertEquals(listOf(1520 to 617), runtime.taps)
    }

    @Test
    fun `click-continue without congratulations or reward context blocks movement`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                ),
            ),
            defaultUi = continueOnlyUi(),
        )

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals(listOf(1520 to 617), runtime.taps)
        assertEquals(1, runtime.swipes.size)
    }

    @Test
    fun `farm page reward label cannot turn unrelated continue into harvest`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                ),
            ),
            defaultUi = continueWithMatchRewardUi(),
        )

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals(listOf(1520 to 617), runtime.taps)
        assertEquals(1, runtime.swipes.size)
    }

    @Test
    fun `partial congratulations blocks move to farmland`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                ),
            ),
            defaultUi = congratulationsOnlyUi(),
        )

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals(listOf(1520 to 617), runtime.taps)
        assertEquals(1, runtime.swipes.size)
    }

    @Test
    fun `blank OCR frames cannot prove popup absence or permit farmland movement`() = runBlocking {
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                ),
            ),
            defaultUi = uiObservation(emptyList()),
        )

        val failure = runCatching { FarmActionAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals(listOf(1520 to 617), runtime.taps)
        assertEquals(1, runtime.swipes.size)
    }

    @Test
    fun `accepts two of three matching planted readings`() = runBlocking {
        val runtime = standardRuntime(
            farmlandReadings = ArrayDeque(
                listOf(
                    FarmlandState.Unknown("", "瞬时空帧"),
                    planted(11, 6),
                    planted(11, 6),
                ),
            ),
        )

        val result = FarmActionAutomation(runtime).run()

        assertTrue(result.farmlandState is FarmlandState.Planted)
        assertEquals(11, (result.maturity as MaturityReading.Time).hour)
        assertEquals(3, runtime.farmlandReadCount)
    }

    @Test
    fun `additional frames recover one valid plus two unknown initial readings`() = runBlocking {
        val runtime = standardRuntime(
            farmlandReadings = ArrayDeque(
                listOf(
                    planted(14, 21),
                    FarmlandState.Unknown("葛笋 14:215", "未找到成熟时间"),
                    FarmlandState.Unknown("葛笋 14:215 O3 ?", "未找到成熟时间"),
                    planted(14, 21),
                ),
            ),
        )

        val result = FarmActionAutomation(runtime).run()

        assertTrue(result.farmlandState is FarmlandState.Planted)
        val maturity = result.maturity as MaturityReading.Time
        assertEquals(14, maturity.hour)
        assertEquals(21, maturity.minute)
        assertEquals(4, runtime.farmlandReadCount)
    }

    @Test
    fun `a single valid planted frame among five readings remains unknown`() = runBlocking {
        val runtime = standardRuntime(
            farmlandReadings = ArrayDeque(
                listOf(
                    planted(14, 21),
                    FarmlandState.Unknown("葛笋 14:215", "未找到成熟时间"),
                    FarmlandState.Unknown("葛笋 14:215 O3 ?", "未找到成熟时间"),
                    FarmlandState.Unknown("", "瞬时空帧"),
                    FarmlandState.Unknown("葛笋", "未找到成熟时间"),
                ),
            ),
        )

        val result = FarmActionAutomation(runtime).run()

        assertTrue(result.farmlandState is FarmlandState.Unknown)
        assertEquals(null, result.maturity)
        assertEquals(5, runtime.farmlandReadCount)
    }

    @Test
    fun `five planted frames without a matching time remain unknown`() = runBlocking {
        val runtime = standardRuntime(
            farmlandReadings = ArrayDeque(
                listOf(
                    planted(11, 6),
                    planted(11, 7),
                    FarmlandState.Unknown("", "瞬时空帧"),
                    planted(11, 8),
                    FarmlandState.Unknown("", "瞬时空帧"),
                ),
            ),
        )

        val result = FarmActionAutomation(runtime).run()

        assertTrue(result.farmlandState is FarmlandState.Unknown)
        assertEquals(null, result.maturity)
        assertEquals(2, runtime.swipes.size)
        assertEquals(5, runtime.farmlandReadCount)
    }

    @Test
    fun `empty farmland does not enter maturity state`() = runBlocking {
        val runtime = standardRuntime(
            farmlandReadings = ArrayDeque(List(3) { FarmlandState.Empty(2, "农田 2级") }),
        )
        val states = mutableListOf<AutomationState>()

        val result = FarmActionAutomation(
            runtime = runtime,
            onState = { state, _ -> states += state },
        ).run()

        assertTrue(result.farmlandState is FarmlandState.Empty)
        assertEquals(null, result.maturity)
        assertFalse(AutomationState.READING_MATURITY in states)
        assertEquals(2, runtime.swipes.size)
    }

    @Test
    fun `early arrival waits then reads a fresh button without inflating readiness time`() = runBlocking {
        val start = LocalDateTime.of(2026, 9, 22, 10, 0)
        val target = start.plusSeconds(8)
        var clock = start
        val tapTimes = mutableListOf<LocalDateTime>()
        val waits = mutableListOf<Long>()
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(listOf(
                farmUi(), farmUi(), oneClickUi(1520, 617), oneClickUi(1530, 625),
            )),
            onDelay = { milliseconds ->
                waits += milliseconds
                clock = clock.plusNanos(milliseconds * 1_000_000)
            },
            onTap = { _, _ -> tapTimes += clock },
        )

        val result = FarmActionAutomation(runtime, now = { clock }, notBefore = target).run()

        assertEquals(listOf(1530 to 625), runtime.taps)
        assertTrue(tapTimes.single() >= target)
        assertTrue(result.readyAt < target)
        assertEquals(tapTimes.single(), result.firstWaterAt)
        assertTrue(waits.all { it in 1L..250L })
    }

    @Test
    fun `cancelling while awaiting target does not send a watering action`() = runBlocking {
        val start = LocalDateTime.of(2026, 9, 22, 10, 0)
        var waiting = false
        val runtime = FakeRuntime(
            uiReadings = ArrayDeque(listOf(farmUi(), farmUi(), oneClickUi(1520, 617))),
            onDelay = { if (waiting) throw CancellationException("用户停止") },
        )
        val guard = RecordingGuard()

        val failure = runCatching {
            FarmActionAutomation(
                runtime,
                now = { start },
                notBefore = start.plusMinutes(1),
                oneClickGuard = guard,
                onLog = { if ("等待计划浇水" in it) waiting = true },
            ).run()
        }.exceptionOrNull()

        assertTrue(failure is CancellationException)
        assertTrue(runtime.taps.isEmpty())
        assertTrue(guard.beforeTargets.isEmpty())
    }

    @Test
    fun `recovery reads farmland without repeating an already sent action`() = runBlocking {
        val sentAt = LocalDateTime.of(2026, 9, 22, 10, 0)
        val runtime = FakeRuntime()
        val guard = RecordingGuard(allow = false)

        val result = FarmActionAutomation(
            runtime,
            now = { sentAt.plusMinutes(1) },
            resumeAfterActionAt = sentAt,
            oneClickGuard = guard,
        ).run()

        assertTrue(result.recoveredAction)
        assertEquals(sentAt, result.firstWaterAt)
        assertTrue(runtime.taps.isEmpty())
        assertTrue(guard.beforeTargets.isEmpty())
        assertEquals(2, runtime.swipes.size)
        assertEquals(3, runtime.farmlandReadCount)
    }

    @Test
    fun `watering that matures the crop performs one separately guarded harvest`() = runBlocking {
        var clock = LocalDateTime.of(2026, 9, 22, 10, 0)
        lateinit var runtime: FakeRuntime
        runtime = FakeRuntime(
            farmlandReadings = ArrayDeque(List(3) { mature() } + List(3) { planted(11, 0) }),
            uiProvider = {
                when {
                    runtime.taps.count { it.first == 1520 } == 2 &&
                        runtime.taps.none { it.first == 1200 } -> completeHarvestUi(1200, 900)
                    runtime.swipes.size in setOf(1, 3) -> oneClickUi(1520, 617)
                    else -> farmUi()
                }
            },
            onTap = { _, _ -> clock = clock.plusSeconds(10) },
        )
        val guard = RecordingGuard()

        val result = FarmActionAutomation(runtime, now = { clock }, oneClickGuard = guard).run()

        assertTrue(result.harvested)
        assertTrue(result.farmlandState is FarmlandState.Planted)
        assertEquals(listOf(1520 to 617), guard.beforeTargets)
        assertEquals(listOf(1520 to 617), guard.beforeMaturityTargets)
        assertEquals(guard.maturityAcceptedAt.single(), result.firstWaterAt)
        assertTrue(result.readyAt < result.firstWaterAt)
        assertEquals(4, runtime.swipes.size)
        assertEquals(SwipeGesture(430, 755, 430, 955, 1200), runtime.swipes[2])
        assertEquals(6, runtime.farmlandReadCount)
    }

    @Test
    fun `crop maturing between OCR frames is harvested in the current visit`() = runBlocking {
        lateinit var runtime: FakeRuntime
        runtime = FakeRuntime(
            farmlandReadings = ArrayDeque(
                listOf(planted(10, 1), planted(10, 1), mature(), mature()) +
                    List(3) { planted(11, 0) },
            ),
            uiProvider = {
                if (runtime.swipes.size in setOf(1, 3)) oneClickUi(1520, 617) else farmUi()
            },
        )
        val guard = RecordingGuard()

        val result = FarmActionAutomation(runtime, oneClickGuard = guard).run()

        assertTrue(result.farmlandState is FarmlandState.Planted)
        assertEquals(1, guard.beforeMaturityTargets.size)
        assertEquals(7, runtime.farmlandReadCount)
        assertEquals(2, runtime.taps.size)
        // A successful input command without a visible harvest modal must
        // not be reported as a visually confirmed harvest.
        assertFalse(result.harvested)
    }

    @Test
    fun `recovery preserves a pending reward popup without sending another farm action`() = runBlocking {
        lateinit var runtime: FakeRuntime
        runtime = FakeRuntime(
            uiProvider = {
                if (runtime.taps.isEmpty()) completeHarvestUi(1200, 900) else farmUi()
            },
        )
        val sentAt = LocalDateTime.of(2026, 9, 22, 10, 0)
        val guard = RecordingGuard(allow = false)

        val result = FarmActionAutomation(
            runtime,
            resumeAfterActionAt = sentAt,
            oneClickGuard = guard,
        ).run()

        assertTrue(result.harvested)
        assertTrue(result.recoveredAction)
        assertEquals(sentAt, result.firstWaterAt)
        assertEquals(listOf(1200 to 900), runtime.taps)
        assertTrue(guard.beforeTargets.isEmpty())
        assertTrue(guard.beforeMaturityTargets.isEmpty())
    }

    @Test
    fun `a second mature result stops without an unbounded click loop`() = runBlocking {
        lateinit var runtime: FakeRuntime
        runtime = FakeRuntime(
            farmlandReadings = ArrayDeque(List(6) { mature() }),
            uiProvider = {
                if (runtime.swipes.size in setOf(1, 3)) oneClickUi(1520, 617) else farmUi()
            },
        )
        val guard = RecordingGuard()

        val failure = runCatching {
            FarmActionAutomation(runtime, oneClickGuard = guard).run()
        }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(failure?.message.orEmpty().contains("同场收获后仍显示已成熟"))
        assertEquals(2, runtime.taps.size)
        assertEquals(1, guard.beforeMaturityTargets.size)
        assertEquals(4, runtime.swipes.size)
    }

    @Test
    fun `an already sent maturity harvest is never repeated on recovery`() = runBlocking {
        lateinit var runtime: FakeRuntime
        runtime = FakeRuntime(
            farmlandReadings = ArrayDeque(List(3) { mature() }),
            uiProvider = {
                if (runtime.swipes.size == 3) oneClickUi(1520, 617) else farmUi()
            },
        )
        val guard = RecordingGuard(allowMaturityHarvest = false)

        val failure = runCatching {
            FarmActionAutomation(
                runtime,
                resumeAfterActionAt = LocalDateTime.of(2026, 9, 22, 10, 0),
                oneClickGuard = guard,
            ).run()
        }.exceptionOrNull()

        assertTrue(failure?.message.orEmpty().contains("同场收获已越过发送边界"))
        assertTrue(runtime.taps.isEmpty())
        assertTrue(guard.beforeTargets.isEmpty())
        assertEquals(1, guard.beforeMaturityTargets.size)
    }

    @Test
    fun `old mature frames do not authorize a harvest when the latest frame is unknown`() = runBlocking {
        val runtime = standardRuntime(
            farmlandReadings = ArrayDeque(
                listOf(mature(), mature()) + List(3) { FarmlandState.Unknown("", "未识别") },
            ),
        )
        val guard = RecordingGuard()

        val result = FarmActionAutomation(runtime, oneClickGuard = guard).run()

        assertTrue(result.farmlandState is FarmlandState.Unknown)
        assertEquals(1, runtime.taps.size)
        assertTrue(guard.beforeMaturityTargets.isEmpty())
    }

    private class FakeRuntime(
        private val uiReadings: ArrayDeque<HarvestOcrObservation> = ArrayDeque(),
        private val rootUiReadings: ArrayDeque<HarvestOcrObservation> = ArrayDeque(),
        private val farmlandReadings: ArrayDeque<FarmlandState> = ArrayDeque(),
        private val defaultUi: HarvestOcrObservation = farmUi(),
        private val onHarvestUiRead: () -> Unit = {},
        private val uiProvider: (() -> HarvestOcrObservation)? = null,
        private val onDelay: (Long) -> Unit = {},
        private val onTap: (Int, Int) -> Unit = { _, _ -> },
    ) : AutomationRuntime {
        val taps = mutableListOf<Pair<Int, Int>>()
        val swipes = mutableListOf<SwipeGesture>()
        var rootUiReadCount = 0
            private set
        var farmlandReadCount = 0
            private set

        override suspend fun checkRoot() = true
        override suspend fun isGameForeground() = true
        override suspend fun isGameRunning() = true
        override suspend fun launchGame() = Unit
        override suspend fun stopGame() = Unit
        override suspend fun tap(x: Int, y: Int) {
            taps += x to y
            onTap(x, y)
        }
        override suspend fun swipe(gesture: SwipeGesture) {
            swipes += gesture
        }
        override suspend fun pressBack() = Unit
        override suspend fun readFarmland(): FarmlandState {
            farmlandReadCount += 1
            return if (farmlandReadings.isEmpty()) planted(11, 6) else farmlandReadings.removeFirst()
        }
        override suspend fun readHarvestUi(): HarvestOcrObservation {
            onHarvestUiRead()
            return if (uiReadings.isEmpty()) uiProvider?.invoke() ?: defaultUi else uiReadings.removeFirst()
        }
        override suspend fun readHarvestUiFromRoot(): HarvestOcrObservation? {
            rootUiReadCount += 1
            return if (rootUiReadings.isEmpty()) null else rootUiReadings.removeFirst()
        }
        override suspend fun delayMs(milliseconds: Long) = onDelay(milliseconds)
    }

    private class RecordingGuard(
        private val allow: Boolean = true,
        private val allowMaturityHarvest: Boolean = true,
    ) : OneClickActionGuard {
        val beforeTargets = mutableListOf<Pair<Int, Int>>()
        val acceptedAt = mutableListOf<LocalDateTime>()
        val beforeMaturityTargets = mutableListOf<Pair<Int, Int>>()
        val maturityAcceptedAt = mutableListOf<LocalDateTime>()
        var harvestObservedCount = 0
            private set

        override suspend fun beforeTap(target: VerifiedActionTarget): Boolean {
            beforeTargets += target.centerX to target.centerY
            return allow
        }

        override suspend fun afterTapAccepted(acceptedAt: LocalDateTime) {
            this.acceptedAt += acceptedAt
        }

        override suspend fun onHarvestObserved() {
            harvestObservedCount += 1
        }

        override suspend fun beforeMaturityHarvest(target: VerifiedActionTarget): Boolean {
            beforeMaturityTargets += target.centerX to target.centerY
            return allowMaturityHarvest
        }

        override suspend fun afterMaturityHarvestAccepted(acceptedAt: LocalDateTime) {
            maturityAcceptedAt += acceptedAt
        }
    }

    companion object {
        private fun mature() = FarmlandState.Mature(MaturityReading.Mature("已成熟"))

        private val baseFarmBoxes = listOf(
            box("仓库", 2200, 220),
            box("社交", 2200, 350),
            box("百科", 1200, 1010),
        )

        private fun standardRuntime(
            farmlandReadings: ArrayDeque<FarmlandState> = ArrayDeque(),
        ) = FakeRuntime(
            uiReadings = ArrayDeque(
                listOf(
                    farmUi(),
                    farmUi(),
                    oneClickUi(1520, 617),
                    oneClickUi(1522, 619),
                    farmUi(),
                    farmUi(),
                ),
            ),
            farmlandReadings = farmlandReadings,
        )

        private fun planted(hour: Int, minute: Int) = FarmlandState.Planted(
            MaturityReading.Time(hour, minute, false, "%02d:%02d成熟".format(hour, minute)),
        )

        private fun farmUi() = uiObservation(baseFarmBoxes)

        private fun oneClickUi(
            x: Int,
            y: Int,
            includeContext: Boolean = true,
            buttonText: String = "一键务农",
        ): HarvestOcrObservation {
            val boxes = buildList {
                addAll(baseFarmBoxes)
                if (includeContext) add(box("流光加速", 1500, 500))
                add(box(buttonText, x, y))
            }
            return uiObservation(boxes)
        }

        private fun contextOnlyUi(): HarvestOcrObservation = uiObservation(
            listOf(box("流光加速", 1500, 500)),
        )

        private fun restUi(x: Int, y: Int) = uiObservation(
            listOf(
                box("请您休息一下", 1200, 520, width = 400),
                box("确定", x, y),
            ),
        )

        private fun completeHarvestUi(x: Int, y: Int): HarvestOcrObservation {
            val continueBox = box("点击继续", x, y)
            val boxes = listOf(
                box("恭喜您获得", 1200, 420, width = 240),
                box("农场经验", 1100, 600, width = 180),
                continueBox,
            )
            return HarvestOcrObservation(
                rawText = boxes.joinToString(" ") { it.text },
                parsed = HarvestInfo(66, mapOf("胡萝卜" to 10), "66 农场经验 胡萝卜 10"),
                items = emptyList(),
                ui = HarvestUiObservation.Present(
                    rawText = boxes.joinToString(" ") { it.text },
                    textBoxes = boxes,
                    sourceWidth = 2400,
                    sourceHeight = 1080,
                    continueBox = continueBox,
                ),
            )
        }

        private fun legacyHarvestUi(x: Int, y: Int): HarvestOcrObservation {
            val boxes = listOf(
                box("农场经验", 1100, 600, width = 180),
                box("点击继续", x, y),
            )
            return HarvestOcrObservation(
                rawText = "66 农场经验 胡萝卜 10 点击继续",
                parsed = HarvestInfo(66, mapOf("胡萝卜" to 10), "66 农场经验 胡萝卜 10"),
                items = emptyList(),
                ui = HarvestUiObservation.Partial(
                    rawText = "66 农场经验 胡萝卜 10 点击继续",
                    textBoxes = boxes,
                    sourceWidth = 2400,
                    sourceHeight = 1080,
                    hasCongratulations = false,
                    hasClickToContinue = true,
                    reason = "旧版仅显示点击继续",
                ),
            )
        }

        private fun continueOnlyUi(): HarvestOcrObservation {
            val boxes = listOf(box("点击继续", 1200, 900))
            return HarvestOcrObservation(
                rawText = "点击继续",
                parsed = null,
                items = emptyList(),
                ui = HarvestUiObservation.Partial(
                    rawText = "点击继续",
                    textBoxes = boxes,
                    sourceWidth = 2400,
                    sourceHeight = 1080,
                    hasCongratulations = false,
                    hasClickToContinue = true,
                    reason = "缺少奖励上下文",
                ),
            )
        }

        private fun continueWithMatchRewardUi(): HarvestOcrObservation {
            val boxes = listOf(
                box("对局奖励", 1_780, 90, width = 180),
                box("点击继续", 1_200, 900),
            )
            return HarvestOcrObservation(
                rawText = "对局奖励 点击继续",
                parsed = null,
                items = emptyList(),
                ui = HarvestUiObservation.Partial(
                    rawText = "对局奖励 点击继续",
                    textBoxes = boxes,
                    sourceWidth = 2_400,
                    sourceHeight = 1_080,
                    hasCongratulations = false,
                    hasClickToContinue = true,
                    reason = "非收获页面的继续提示",
                ),
            )
        }

        private fun congratulationsOnlyUi(): HarvestOcrObservation {
            val boxes = listOf(box("恭喜您获得", 1200, 420, width = 240))
            return HarvestOcrObservation(
                rawText = "恭喜您获得",
                parsed = null,
                items = emptyList(),
                ui = HarvestUiObservation.Partial(
                    rawText = "恭喜您获得",
                    textBoxes = boxes,
                    sourceWidth = 2400,
                    sourceHeight = 1080,
                    hasCongratulations = true,
                    hasClickToContinue = false,
                    reason = "缺少点击继续",
                ),
            )
        }

        private fun uiObservation(
            boxes: List<HarvestScreenTextBox>,
        ): HarvestOcrObservation {
            val raw = boxes.joinToString(" ") { it.text }
            return HarvestOcrObservation(
                rawText = raw,
                parsed = null,
                items = emptyList(),
                ui = HarvestUiObservation.Absent(
                    rawText = raw,
                    textBoxes = boxes,
                    sourceWidth = 2400,
                    sourceHeight = 1080,
                ),
            )
        }

        private fun box(
            text: String,
            centerX: Int,
            centerY: Int,
            width: Int = 140,
            height: Int = 48,
        ) = HarvestScreenTextBox(
            text = text,
            left = centerX - width / 2,
            top = centerY - height / 2,
            right = centerX + width / 2,
            bottom = centerY + height / 2,
        )

    }
}
