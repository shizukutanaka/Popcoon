package com.example.popcoon.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.ui.theme.Spacing

/**
 * 目標価格を設定 / 解除するダイアログ。
 *
 * 同種の価格追跡アプリ（CamelCamelCamel / Keepa 等）が普遍的に備える
 * 「希望価格アラート」を Popcoon に導入するための UI。
 *
 * @param currentTarget 既存の目標価格（円）。null = 未設定。
 * @param onConfirm 入力された目標価格（円）。null = 解除。
 */
@Composable
fun TargetPriceDialog(
    currentTarget: Long?,
    onConfirm: (Long?) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentTarget?.toString() ?: "") }
    // 数字のみ抽出して解釈（全角・記号・カンマを許容）。
    val parsed = text.filter { it.isDigit() }.toLongOrNull()
    val valid = text.isBlank() || (parsed != null && parsed > 0)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.target_price_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.target_price_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(Spacing.ml))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    isError = !valid,
                    label = { Text(stringResource(R.string.target_price_hint)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    // 空入力 = 解除（null）、数字あり = その値で設定。
                    onConfirm(if (text.isBlank()) null else parsed)
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
