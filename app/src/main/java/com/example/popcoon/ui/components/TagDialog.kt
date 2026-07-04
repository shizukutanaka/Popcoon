package com.example.popcoon.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.popcoon.R
import com.example.popcoon.ui.theme.Spacing
import com.example.popcoon.ui.util.HapticFeedback

/**
 * ウォッチリストアイテムのタグ (フォルダ分類) を設定 / 解除するダイアログ。
 *
 * 自由記述の単一タグ。既存タグがあれば選択チップで再利用を促し、
 * タグの表記ゆれ (「ガジェット」「gadget」等の重複) を抑える。
 *
 * @param currentTag 既存のタグ。null = 未分類。
 * @param existingTags 他アイテムで既に使われているタグ一覧 (クイック選択用)。
 * @param onConfirm 入力されたタグ。null または空文字で解除。
 */
@Composable
fun TagDialog(
    currentTag: String?,
    existingTags: List<String>,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(currentTag ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.watchlist_tag_dialog_title)) },
        text = {
            Column {
                if (existingTags.isNotEmpty()) {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        existingTags.forEach { tag ->
                            AssistChip(
                                onClick = { text = tag },
                                label = { Text(tag) },
                            )
                        }
                    }
                    Spacer(Modifier.height(Spacing.ml))
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.watchlist_tag_hint)) },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    HapticFeedback.success(context)
                    onConfirm(text.trim().ifBlank { null })
                },
            ) {
                Text(
                    stringResource(
                        if (text.isBlank()) R.string.target_price_clear else R.string.action_save,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_cancel))
            }
        },
    )
}
