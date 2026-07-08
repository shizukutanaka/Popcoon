package io.github.shizukutanaka.popcoon.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.shizukutanaka.popcoon.feature.scorer.BuyTimingScorer
import io.github.shizukutanaka.popcoon.ui.components.ScoreCard
import io.github.shizukutanaka.popcoon.ui.theme.PopcoonTheme
import org.junit.Rule
import org.junit.Test

/**
 * ScoreCard の段階的開示挙動を検証。
 *
 * Apple HIG: タップで詳細を展開する Progressive Disclosure パターン。
 */
class ScoreCardUiTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun scoreCard_collapsedByDefault_showsOnlyVerdict() {
        composeTestRule.setContent {
            PopcoonTheme {
                ScoreCard(
                    score = 75,
                    verdict = BuyTimingScorer.Verdict.BUY_NOW,
                    confidence = "高",
                    signals = listOf(
                        BuyTimingScorer.Signal("過去最安近辺", 30),
                        BuyTimingScorer.Signal("下降トレンド", 20),
                    ),
                )
            }
        }

        composeTestRule.onNodeWithText("今が買い時").assertIsDisplayed()
        composeTestRule.onNodeWithText("75").assertIsDisplayed()
    }

    @Test
    fun scoreCard_tapped_expandsBreakdown() {
        composeTestRule.setContent {
            PopcoonTheme {
                ScoreCard(
                    score = 75,
                    verdict = BuyTimingScorer.Verdict.BUY_NOW,
                    confidence = "高",
                    signals = listOf(
                        BuyTimingScorer.Signal("過去最安近辺", 30),
                    ),
                )
            }
        }

        // タップで展開
        composeTestRule.onNodeWithText("今が買い時").performClick()

        // 展開後はシグナル名が表示される
        composeTestRule.onNodeWithText("スコア内訳").assertIsDisplayed()
    }

    @Test
    fun scoreCard_waitVerdict_showsWaitMessage() {
        composeTestRule.setContent {
            PopcoonTheme {
                ScoreCard(
                    score = 25,
                    verdict = BuyTimingScorer.Verdict.WAIT,
                    confidence = "中",
                    signals = emptyList(),
                )
            }
        }

        composeTestRule.onNodeWithText("もう少し待つとよい").assertIsDisplayed()
    }
}
