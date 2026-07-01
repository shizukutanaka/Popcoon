package com.example.popcoon.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import com.example.popcoon.R
import com.example.popcoon.ui.theme.AppIcons
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.PopcoonTheme
import com.example.popcoon.ui.theme.Spacing

/**
 * EC 会員設定 (楽天SPU/Yahooプレミアム/PayPay/Amazonプライム) の一度きりの案内バナー。
 *
 * 背景: 実質価格ランキング (PointSimulator) はこれら 4 設定に依存するが、
 * 全てデフォルト OFF かつ設定画面のみに存在する。オンボーディングでは一切触れないため、
 * 大半のユーザーが存在に気づかず「実質価格」が常に最低倍率で計算され続け、
 * アプリの核心的な差別化機能 (個人化されたポイント込み実質価格の透明表示) が
 * 事実上死蔵していた (ソクラテス式レビューで発見)。
 *
 * 1 回だけ表示 → タップで設定画面へ / × で閉じる、いずれも二度と表示しない。
 */
@Composable
fun EcMembershipBanner(
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onOpenSettings),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(CornerRadius.card),
    ) {
        Row(
            modifier = Modifier.padding(start = Spacing.ml, end = Spacing.xs, top = Spacing.sm, bottom = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = stringResource(R.string.ec_membership_prompt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    AppIcons.Close,
                    contentDescription = stringResource(R.string.action_dismiss),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Preview(name = "EcMembershipBanner", showBackground = true)
@Composable
private fun EcMembershipBannerPreview() {
    PopcoonTheme {
        EcMembershipBanner(onOpenSettings = {}, onDismiss = {})
    }
}
