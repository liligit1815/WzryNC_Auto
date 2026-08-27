package com.lispace.wzryncauto.automation

import com.lispace.wzryncauto.ocr.FarmlandState
import com.lispace.wzryncauto.ocr.HarvestOcrObservation
import com.lispace.wzryncauto.ocr.HarvestScreenTextBox
import com.lispace.wzryncauto.ocr.HarvestUiObservation
import com.lispace.wzryncauto.ocr.MaturityReading
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnterFarmAutomationTest {
    @Test
    fun `runs the complete navigation using OCR without requesting templates`() = runBlocking {
        val runtime = successfulRuntime()
        val states = mutableListOf<AutomationState>()

        val finalState = EnterFarmAutomation(
            runtime = runtime,
            onState = { state, _ -> states += state },
        ).run()

        assertEquals(AutomationState.RESETTING_POSITION, finalState)
        assertEquals(
            listOf(
                AutomationState.PREPARING,
                AutomationState.CHECKING_GAME,
                AutomationState.LAUNCHING_GAME,
                AutomationState.WAITING_LOGIN,
                AutomationState.CLICKING_START_GAME,
                AutomationState.CLOSING_AD_POPUPS,
                AutomationState.WAITING_LOBBY,
                AutomationState.ENTERING_FARM,
                AutomationState.RESETTING_POSITION,
            ),
            states.distinct(),
        )
        assertTrue(runtime.launched)
        assertEquals(listOf(1_190 to 842, 420 to 760), runtime.taps)
        assertEquals(0, runtime.backPresses)
    }

    @Test
    fun `dismisses a recognized promotional modal with the top-right close button`() = runBlocking {
        val runtime = successfulRuntime(
            prefix = List(2) {
                textOcr(
                    box("夺宝限时回馈", 700, 180, 1_500, 300),
                    box("今日内不再弹出", 1_750, 880, 2_250, 960),
                )
            },
            popupCloseTargets = listOf(testCloseTarget()),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(0, runtime.backPresses)
        assertEquals(
            listOf(1_190 to 842, 2_232 to 189, 420 to 760),
            runtime.taps,
        )
    }

    @Test
    fun `dismisses update announcement before waiting for the start button`() = runBlocking {
        val farmEntry = textOcr(
            box("王者农场", 300, 720, 540, 800),
        )
        val farmPage = textOcr(
            box("仓库", 1_800, 300, 1_950, 380),
            box("社交", 1_800, 450, 1_950, 530),
        )
        val runtime = FakeRuntime(
            ocrScreens = ArrayDeque(
                List(2) {
                    textOcr(
                        box("8月13日版本更新公告", 900, 210, 1_650, 300),
                    )
                } +
                    List(2) { startGameOcr() } +
                    List(2) { absentOcr() } +
                    List(5) { absentOcr() } +
                    List(6) { farmEntry } +
                    List(2) { farmPage },
            ),
            popupCloseTargets = listOf(
                testCloseTarget(
                    centerX = 2_185,
                    centerY = 280,
                    width = 2_560,
                    height = 1_600,
                ),
            ),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(2_185 to 280, 1_190 to 842, 420 to 760),
            runtime.taps,
        )
        assertEquals(
            listOf(
                PopupCloseSearchProfile.UPDATE_ANNOUNCEMENT,
                PopupCloseSearchProfile.UPDATE_ANNOUNCEMENT,
            ),
            runtime.popupSearchProfiles,
        )
    }

    @Test
    fun `promotional modal confirmation tolerates one intermittent OCR miss`() = runBlocking {
        val modal = textOcr(
            box("今日内不再弹出", 1_750, 880, 2_250, 960),
        )
        val runtime = successfulRuntime(
            prefix = listOf(modal, absentOcr(), modal),
            popupCloseTargets = listOf(testCloseTarget()),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(1_190 to 842, 2_232 to 189, 420 to 760),
            runtime.taps,
        )
    }

    @Test
    fun `promotional modal can confirm title and footer across OCR frames`() = runBlocking {
        val runtime = successfulRuntime(
            prefix = listOf(
                textOcr(box("今日内不再弹出", 1_750, 880, 2_250, 960)),
                textOcr(box("更新公告", 700, 180, 1_800, 300)),
            ),
            popupCloseTargets = listOf(testCloseTarget()),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(1_190 to 842, 2_232 to 189, 420 to 760),
            runtime.taps,
        )
    }

    @Test
    fun `rechecks and closes the next promotional modal after the first one`() = runBlocking {
        val modal = textOcr(box("今日内不再弹出", 1_750, 880, 2_250, 960))
        val runtime = successfulRuntime(
            prefix = listOf(modal, modal, modal, modal),
            popupCloseTargets = listOf(testCloseTarget(), testCloseTarget()),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(
                1_190 to 842,
                2_232 to 189,
                2_232 to 189,
                420 to 760,
            ),
            runtime.taps,
        )
    }

    @Test
    fun `ignores a close-image hit without modal backdrop context`() = runBlocking {
        val runtime = successfulRuntime(
            popupCloseTargets = listOf(testCloseTarget()),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(listOf(1_190 to 842, 420 to 760), runtime.taps)
    }

    @Test
    fun `shop banner text cannot trigger an advertisement close click`() = runBlocking {
        val runtime = successfulRuntime(
            prefix = List(5) {
                textOcr(box("狼来了小兵限时兑换", 1_500, 180, 2_350, 300))
            },
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(listOf(1_190 to 842, 420 to 760), runtime.taps)
    }

    @Test
    fun `lobby chat rank invitation cannot block a visible farm entry`() = runBlocking {
        val lobby = textOcr(
            box("王者农场", 529, 1_215, 761, 1_269),
            box("HOMESTEAD", 555, 1_277, 739, 1_307),
            box("召集5v5排位赛·多人", 93, 1_412, 823, 1_453),
            box("排位", 1_451, 1_193, 1_627, 1_276),
            box("商城", 2_423, 279, 2_508, 321),
            width = 2_560,
            height = 1_600,
        )
        val runtime = successfulRuntime(prefix = List(2) { lobby })

        EnterFarmAutomation(runtime).run()

        assertEquals(listOf(1_190 to 842, 420 to 760), runtime.taps)
    }

    @Test
    fun `visible farm entry suppresses visual-only close hit on lobby shop artwork`() = runBlocking {
        val lobby = textOcr(
            box("王者农场", 529, 1_215, 761, 1_269),
            box("HOMESTEAD", 555, 1_273, 743, 1_308),
            box("澜全新无双皮肤", 2_116, 313, 2_360, 347),
            box("商城", 2_423, 279, 2_508, 321),
            width = 2_560,
            height = 1_600,
        )
        val falseShopArtworkHit = testCloseTarget(
            centerX = 2_208,
            centerY = 296,
            width = 2_560,
            height = 1_600,
            popupContextScore = 0.20,
        )
        val runtime = successfulRuntime(
            prefix = List(5) { lobby },
            popupCloseTargets = List(8) { falseShopArtworkHit },
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(1_190 to 842, 645 to 1_242),
            runtime.taps,
        )
    }

    @Test
    fun `promotional modal text and lobby shop shortcut are not a shop page`() = runBlocking {
        val promotionalModal = textOcr(
            box("联动时装返场", 888, 707, 2_167, 953),
            box("限时星币兑换", 1_261, 981, 1_777, 1_060),
            box("前往获取", 1_365, 1_136, 1_687, 1_212),
            box("商城", 2_423, 279, 2_508, 321),
            width = 2_560,
            height = 1_600,
        )
        val visualClose = testCloseTarget(
            centerX = 2_378,
            centerY = 279,
            width = 2_560,
            height = 1_600,
            popupContextScore = 0.20,
        )
        val runtime = successfulRuntime(
            prefix = listOf(promotionalModal),
            popupCloseTargets = listOf(visualClose, visualClose),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(1_190 to 842, 2_378 to 279, 420 to 760),
            runtime.taps,
        )
    }

    @Test
    fun `confirmed main-content shop page still stops without a blind tap`() = runBlocking {
        val shopPage = textOcr(
            box("商城", 420, 100, 620, 180),
            box("推荐", 360, 260, 540, 340),
            box("新品", 360, 380, 540, 460),
        )
        val runtime = FakeRuntime(
            ocrScreens = ArrayDeque(
                List(2) { startGameOcr() } +
                    List(2) { absentOcr() } +
                    List(5) { absentOcr() } +
                    List(2) { shopPage },
            ),
        )

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(failure?.message.orEmpty().contains("商城页面"))
        assertEquals(listOf(1_190 to 842), runtime.taps)
    }

    @Test
    fun `taps rest reminder confirmation only when reminder context is present`() = runBlocking {
        val runtime = successfulRuntime(
            prefix = List(2) {
                textOcr(
                    box("请您休息一下", 850, 420, 1_550, 500),
                    box("确定", 1_050, 700, 1_350, 790),
                )
            },
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(0, runtime.backPresses)
        assertEquals(
            listOf(1_190 to 842, 1_200 to 745, 420 to 760),
            runtime.taps,
        )
    }

    @Test
    fun `does not press Back or tap for unknown page text`() = runBlocking {
        val runtime = FakeRuntime(
            ocrScreens = ArrayDeque(
                listOf(
                    textOcr(box("活动", 900, 300, 1_200, 380)),
                    textOcr(box("福利", 900, 300, 1_200, 380)),
                ),
            ),
        )

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals("等待开始游戏文字超时", failure?.message)
        assertTrue(runtime.taps.isEmpty())
        assertEquals(0, runtime.backPresses)
    }

    @Test
    fun `recognized update popup without a close target reports the real blocker`() = runBlocking {
        val updatePopup = textOcr(
            box("8月27日版本更新公告", 860, 120, 1_540, 200),
        )
        val runtime = FakeRuntime(
            ocrScreens = ArrayDeque(List(150) { updatePopup }),
        )

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals(
            "已识别弹窗“更新公告”，但未定位到安全的关闭按钮",
            failure?.message,
        )
        assertTrue(runtime.taps.isEmpty())
    }

    @Test
    fun `chat text cannot impersonate a modal popup`() = runBlocking {
        val runtime = successfulRuntime(
            prefix = listOf(
                textOcr(
                    box("温馨提示", 180, 990, 430, 1_060),
                    box("休息一下", 500, 990, 760, 1_060),
                    box("适度游戏", 900, 990, 1_300, 1_060),
                ),
            ),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(0, runtime.backPresses)
        assertEquals(listOf(1_190 to 842, 420 to 760), runtime.taps)
    }

    @Test
    fun `legal consent screen requires manual choice and is never dismissed`() = runBlocking {
        val runtime = FakeRuntime(
            ocrScreens = ArrayDeque(
                listOf(
                    textOcr(
                        box("游戏许可及服务协议", 700, 220, 1_700, 330),
                        box("同意", 1_100, 850, 1_350, 940),
                    ),
                ),
            ),
        )

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(failure?.message.orEmpty().contains("手动选择"))
        assertTrue(runtime.taps.isEmpty())
        assertEquals(0, runtime.backPresses)
    }

    @Test
    fun `ignores non-interactive startup presentation before the real start button`() =
        runBlocking {
            val farmEntry = textOcr(
                box("王者农场", 360, 1_120, 650, 1_260),
                width = 2_560,
                height = 1_600,
            )
            val farmPage = textOcr(
                box("仓库", 2_300, 280, 2_440, 360),
                box("百科", 1_100, 1_400, 1_300, 1_500),
                width = 2_560,
                height = 1_600,
            )
            val runtime = FakeRuntime(
                ocrScreens = ArrayDeque(
                    List(4) { tabletStartupPresentationOcr() } +
                        List(2) { tabletStartGameOcr() } +
                        List(2) { absentOcr() } +
                        List(2) { absentOcr() } +
                        List(5) { absentOcr() } +
                        List(6) { farmEntry } +
                        List(2) { farmPage },
                ),
            )

            EnterFarmAutomation(runtime).run()

            assertEquals(listOf(1_277 to 1_184, 505 to 1_190), runtime.taps)
        }

    @Test
    fun `accepts verified tablet start button at current lower position`() = runBlocking {
        val farmEntry = textOcr(
            box("王者农场", 360, 1_120, 650, 1_260),
            width = 2_560,
            height = 1_600,
        )
        val farmPage = textOcr(
            box("仓库", 2_300, 280, 2_440, 360),
            box("百科", 1_100, 1_400, 1_300, 1_500),
            width = 2_560,
            height = 1_600,
        )
        val runtime = FakeRuntime(
            ocrScreens = ArrayDeque(
                List(2) { tabletStartGameOcr() } +
                    List(2) { absentOcr() } +
                    List(5) { absentOcr() } +
                    List(6) { farmEntry } +
                    List(2) { farmPage },
            ),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(listOf(1_277 to 1_184, 505 to 1_190), runtime.taps)
    }

    @Test
    fun `exits after one controlled start retry remains visible`() = runBlocking {
        val runtime = FakeRuntime(
            ocrScreens = ArrayDeque(List(9) { tabletStartGameOcr() }),
        )

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals("开始游戏受控重试后页面仍未离开，本轮已终止", failure?.message)
        assertEquals(2, runtime.taps.size)
    }

    @Test
    fun `allows one retry only after the start page remains stable`() =
        runBlocking {
            val farmEntry = textOcr(
                box("王者农场", 360, 1_120, 650, 1_260),
                width = 2_560,
                height = 1_600,
            )
            val farmPage = textOcr(
                box("仓库", 2_300, 280, 2_440, 360),
                box("百科", 1_100, 1_400, 1_300, 1_500),
                width = 2_560,
                height = 1_600,
            )
            val runtime = FakeRuntime(
                ocrScreens = ArrayDeque(
                    List(4) { startGameOcr() } +
                        List(2) { absentOcr() } +
                        List(5) { absentOcr() } +
                        List(6) { farmEntry } +
                        List(2) { farmPage },
                ),
            )

            EnterFarmAutomation(runtime).run()

            assertEquals(
                listOf(1_190 to 842, 1_190 to 842, 505 to 1_190),
                runtime.taps,
            )
        }

    @Test
    fun `confirmed rank page blocks any start retry`() = runBlocking {
        val rankPage = textOcr(
            box("5v5排位赛", 1_050, 1_260, 1_480, 1_360),
            box("赛季", 320, 210, 520, 290),
            width = 2_560,
            height = 1_600,
        )
        val runtime = FakeRuntime(
            ocrScreens = ArrayDeque(
                List(2) { tabletStartGameOcr() } + List(2) { rankPage },
            ),
        )

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(failure?.message.orEmpty().contains("排位赛页面"))
        assertEquals(listOf(1_277 to 1_184), runtime.taps)
    }

    @Test
    fun `requires consecutive farm entry frames before accepting the lobby`() = runBlocking {
        val screens = mutableListOf<HarvestOcrObservation>()
        screens += List(2) { startGameOcr() }
        screens += List(2) { absentOcr() }
        screens += List(5) { absentOcr() }
        screens += farmEntryOcr("王者农场")
        screens += absentOcr()
        screens += farmEntryOcr("HOMESTEAD")
        val runtime = FakeRuntime(ocrScreens = ArrayDeque(screens))

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals("等待王者农场入口文字超时", failure?.message)
        assertEquals(listOf(1_190 to 842), runtime.taps)
    }

    @Test
    fun `accepts different farm labels on consecutive frames when coordinates stay on the same tile`() =
        runBlocking {
            val runtime = successfulRuntime(
                farmLabels = listOf(
                    "王者农场",
                    "HOMESTEAD",
                    "来农场",
                    "王者农场",
                    "HOMESTEAD",
                    "来农场",
                ),
            )

            EnterFarmAutomation(runtime).run()

            assertEquals(420 to 760, runtime.taps.last())
        }

    @Test
    fun `two stable return welfare frames tap the scaled top-left back button once`() = runBlocking {
        val returnWelfare = textOcr(
            box("回归福利", 360, 30, 560, 100),
        )
        val runtime = successfulRuntime(
            beforeFarmEntry = List(2) { returnWelfare },
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(1_190 to 842, 230 to 59, 420 to 760),
            runtime.taps,
        )
    }

    @Test
    fun `return welfare page is also handled during post-login advertisement checks`() = runBlocking {
        val returnWelfare = textOcr(
            box("回归福利", 360, 30, 560, 100),
        )
        val runtime = successfulRuntime(
            prefix = List(2) { returnWelfare },
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(1_190 to 842, 230 to 59, 420 to 760),
            runtime.taps,
        )
    }

    @Test
    fun `one return welfare frame never triggers a back-button tap`() = runBlocking {
        val returnWelfare = textOcr(
            box("回归福利", 360, 30, 560, 100),
        )
        val runtime = successfulRuntime(
            beforeFarmEntry = listOf(returnWelfare, absentOcr()),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(listOf(1_190 to 842, 420 to 760), runtime.taps)
    }

    @Test
    fun `persistent return welfare page stops after one back-button tap`() = runBlocking {
        val returnWelfare = textOcr(
            box("回归福利", 360, 30, 560, 100),
        )
        val runtime = successfulRuntime(
            beforeFarmEntry = List(4) { returnWelfare },
        )

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertTrue(failure?.message.orEmpty().contains("禁止重复点击"))
        assertEquals(listOf(1_190 to 842, 230 to 59), runtime.taps)
    }

    @Test
    fun `return welfare back-button position scales on tablet resolution`() = runBlocking {
        val returnWelfare = textOcr(
            box("回归福利", 380, 40, 620, 130),
            width = 2_560,
            height = 1_600,
        )
        val tabletFarmEntry = textOcr(
            box("王者农场", 360, 1_120, 650, 1_260),
            width = 2_560,
            height = 1_600,
        )
        val tabletFarmPage = textOcr(
            box("仓库", 2_300, 280, 2_440, 360),
            box("百科", 1_100, 1_400, 1_300, 1_500),
            width = 2_560,
            height = 1_600,
        )
        val runtime = FakeRuntime(
            ocrScreens = ArrayDeque(
                List(2) { tabletStartGameOcr() } +
                    List(2) { absentOcr() } +
                    List(2) { absentOcr() } +
                    List(5) { absentOcr() } +
                    List(2) { returnWelfare } +
                    List(6) { tabletFarmEntry } +
                    List(2) { tabletFarmPage },
            ),
        )

        EnterFarmAutomation(runtime).run()

        assertEquals(
            listOf(1_277 to 1_184, 246 to 88, 505 to 1_190),
            runtime.taps,
        )
    }

    @Test
    fun `requires multiple farm page anchors in consecutive frames`() = runBlocking {
        val screens = successfulNavigationScreens().toMutableList()
        screens.removeAt(screens.lastIndex)
        screens.removeAt(screens.lastIndex)
        screens += textOcr(box("种植", 1_900, 850, 2_050, 930))
        screens += textOcr(
            box("仓库", 1_800, 300, 1_950, 380),
            box("社交", 1_800, 450, 1_950, 530),
        )
        screens += absentOcr()
        val runtime = FakeRuntime(ocrScreens = ArrayDeque(screens))

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals("等待农场页面文字锚点超时", failure?.message)
        assertEquals(listOf(1_190 to 842, 420 to 760), runtime.taps)
    }

    @Test
    fun `rejects farm entry text outside its safe region`() = runBlocking {
        val screens = mutableListOf<HarvestOcrObservation>()
        screens += List(2) { startGameOcr() }
        screens += List(2) { absentOcr() }
        screens += List(5) { absentOcr() }
        screens += List(20) {
            textOcr(box("王者农场", 1_800, 720, 2_100, 800))
        }
        val runtime = FakeRuntime(ocrScreens = ArrayDeque(screens))

        val failure = runCatching { EnterFarmAutomation(runtime).run() }.exceptionOrNull()

        assertTrue(failure is AutomationFailure)
        assertEquals("等待王者农场入口文字超时", failure?.message)
        assertEquals(listOf(1_190 to 842), runtime.taps)
    }

    private class FakeRuntime(
        private val ocrScreens: ArrayDeque<HarvestOcrObservation>,
        private val popupCloseTargets: List<PopupCloseTarget?> = emptyList(),
        private val startGameTargets: List<StartGameVisualTarget?> = emptyList(),
    ) : AutomationRuntime {
        var launched = false
        var backPresses = 0
        val taps = mutableListOf<Pair<Int, Int>>()
        private var popupCloseTargetIndex = 0
        private var startGameTargetIndex = 0
        val popupSearchProfiles = mutableListOf<PopupCloseSearchProfile>()

        override suspend fun checkRoot() = true
        override suspend fun isGameForeground() = false
        override suspend fun isGameRunning() = false
        override suspend fun launchGame() {
            launched = true
        }
        override suspend fun stopGame() = Unit
        override suspend fun tap(x: Int, y: Int) {
            taps += x to y
        }
        override suspend fun pressBack() {
            backPresses += 1
        }
        override suspend fun swipe(gesture: SwipeGesture) = Unit
        override suspend fun readFarmland(): FarmlandState = FarmlandState.Planted(
            MaturityReading.Time(12, 0, false, "12:00成熟"),
        )
        override suspend fun readHarvestUi(): HarvestOcrObservation =
            ocrScreens.removeFirstOrNull() ?: absentOcr()
        override suspend fun findPopupCloseTarget(
            profile: PopupCloseSearchProfile,
        ): PopupCloseTarget? {
            popupSearchProfiles += profile
            return popupCloseTargets.getOrNull(popupCloseTargetIndex++)
        }
        override suspend fun findStartGameTarget(): StartGameVisualTarget? =
            startGameTargets.getOrNull(startGameTargetIndex++)
        override suspend fun delayMs(milliseconds: Long) = Unit
    }

    companion object {
        private fun successfulRuntime(
            prefix: List<HarvestOcrObservation> = emptyList(),
            farmLabels: List<String> = List(6) { "王者农场" },
            popupCloseTargets: List<PopupCloseTarget?> = emptyList(),
            beforeFarmEntry: List<HarvestOcrObservation> = emptyList(),
        ): FakeRuntime = FakeRuntime(
            ocrScreens = ArrayDeque(
                successfulNavigationScreens(farmLabels, prefix, beforeFarmEntry),
            ),
            popupCloseTargets = popupCloseTargets,
        )

        private fun testCloseTarget(
            centerX: Int = 2_232,
            centerY: Int = 189,
            width: Int = 2_400,
            height: Int = 1_080,
            popupContextScore: Double = 0.0,
        ) = PopupCloseTarget(
            centerX = centerX,
            centerY = centerY,
            sourceWidth = width,
            sourceHeight = height,
            score = 1.0,
            templateName = "test-close-popup.png",
            popupContextScore = popupContextScore,
        )

        private fun successfulNavigationScreens(
            farmLabels: List<String> = List(6) { "王者农场" },
            adScreens: List<HarvestOcrObservation> = List(5) { absentOcr() },
            beforeFarmEntry: List<HarvestOcrObservation> = emptyList(),
        ): List<HarvestOcrObservation> {
            require(farmLabels.size == 6)
            require(adScreens.size <= 5)
            return buildList {
                addAll(List(2) { startGameOcr() })
                addAll(List(2) { absentOcr() })
                addAll(List(2) { absentOcr() })
                addAll(adScreens)
                addAll(List(5 - adScreens.size) { absentOcr() })
                addAll(beforeFarmEntry)
                addAll(farmLabels.map(::farmEntryOcr))
                add(
                    textOcr(
                        box("仓库", 1_800, 300, 1_950, 380),
                        box("社交", 1_800, 450, 1_950, 530),
                    ),
                )
                add(
                    textOcr(
                        box("社交", 1_800, 450, 1_950, 530),
                        box("种植", 1_900, 850, 2_050, 930),
                    ),
                )
            }
        }

        private fun startGameOcr(): HarvestOcrObservation = textOcr(
            box("开始游戏", 1_090, 812, 1_290, 872),
        )

        private fun tabletStartGameOcr(): HarvestOcrObservation = textOcr(
            box("开始游戏", 1_177, 1_144, 1_377, 1_224),
            width = 2_560,
            height = 1_600,
        )

        private fun tabletStartupPresentationOcr(): HarvestOcrObservation = textOcr(
            box("开始游戏", 1_218, 1_318, 1_418, 1_398),
            width = 2_560,
            height = 1_600,
        )

        private fun farmEntryOcr(text: String): HarvestOcrObservation = textOcr(
            box(text, 300, 720, 540, 800),
        )

        private fun box(
            text: String,
            left: Int,
            top: Int,
            right: Int,
            bottom: Int,
        ) = HarvestScreenTextBox(text, left, top, right, bottom)

        private fun textOcr(
            vararg boxes: HarvestScreenTextBox,
            width: Int = 2_400,
            height: Int = 1_080,
        ): HarvestOcrObservation {
            val rawText = boxes.joinToString(" ") { it.text }
            return HarvestOcrObservation(
                rawText = rawText,
                parsed = null,
                items = emptyList(),
                ui = HarvestUiObservation.Absent(
                    rawText = rawText,
                    textBoxes = boxes.toList(),
                    sourceWidth = width,
                    sourceHeight = height,
                ),
            )
        }

        private fun absentOcr() = textOcr()
    }
}
