package io.github.shizukutanaka.popcoon.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** ViewModel から UI への文字列受け渡し。@StringRes でロケール対応、DynamicString で動的テキスト対応。 */
sealed class UiText {
    /** 文字列リソース (ロケール対応)。ViewModel はリソース ID のみ保持し、Context を不要にする。 */
    data class StringResource(@StringRes val resId: Int, vararg val args: Any) : UiText()

    /** 動的文字列 (API エラーメッセージ等ロケール非依存の生文字列)。 */
    data class DynamicString(val text: String) : UiText()

    @Composable
    fun asString(): String = when (this) {
        is StringResource -> if (args.isEmpty()) stringResource(resId)
                             else stringResource(resId, *args)
        is DynamicString  -> text
    }
}
