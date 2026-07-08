package io.github.shizukutanaka.popcoon.ui.screens.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import io.github.shizukutanaka.popcoon.ui.theme.AppIcons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import io.github.shizukutanaka.popcoon.ui.a11y.a11yHeading
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.ui.theme.CornerRadius
import io.github.shizukutanaka.popcoon.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onCustoms: () -> Unit = {},
    onLicenses: () -> Unit = {},
    viewModel: SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var deleteDialogVisible by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { viewModel.restoreWatchlist(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, stringResource(R.string.nav_back))
                    }
                },
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(Spacing.ml).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
        ) {
            // ── プライバシー設定 ─────────────────────────
            SectionCard(stringResource(R.string.settings_privacy)) {
                ToggleRow(
                    title = stringResource(R.string.settings_crash_optin),
                    description = stringResource(R.string.settings_crash_desc),
                    checked = state.crashOptin,
                    onCheckedChange = viewModel::setCrashOptin,
                )
                ToggleRow(
                    title = stringResource(R.string.settings_ai_optin),
                    description = stringResource(R.string.settings_ai_desc),
                    checked = state.aiOptin,
                    onCheckedChange = viewModel::setAiOptin,
                )
                ToggleRow(
                    title = stringResource(R.string.settings_affiliate_optin),
                    description = stringResource(R.string.settings_affiliate_desc),
                    checked = state.affiliateOptin,
                    onCheckedChange = viewModel::setAffiliateOptin,
                )
            }

            // ── EC 会員設定 (ポイント還元ランキング個人化) ──────
            SectionCard(stringResource(R.string.settings_ec_membership)) {
                // 楽天 SPU: Slider 1–15
                Column(Modifier.padding(horizontal = Spacing.ml, vertical = Spacing.sm)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            stringResource(R.string.settings_rakuten_spu),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            "${state.rakutenSpu}x",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        stringResource(R.string.settings_rakuten_spu_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Slider(
                        value = state.rakutenSpu.toFloat(),
                        onValueChange = { viewModel.setRakutenSpu(it.toInt()) },
                        valueRange = 1f..15f,
                        steps = 13,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                HorizontalDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_yahoo_premium),
                    description = stringResource(R.string.settings_yahoo_premium_desc),
                    checked = state.yahooPremium,
                    onCheckedChange = viewModel::setYahooPremium,
                )
                HorizontalDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_paypay_softbank),
                    description = stringResource(R.string.settings_paypay_softbank_desc),
                    checked = state.paypaySoftbank,
                    onCheckedChange = viewModel::setPaypaySoftbank,
                )
                HorizontalDivider()
                ToggleRow(
                    title = stringResource(R.string.settings_amazon_prime),
                    description = stringResource(R.string.settings_amazon_prime_desc),
                    checked = state.amazonPrime,
                    onCheckedChange = viewModel::setAmazonPrime,
                )
            }

            // ── 通知 ───────────────────────────────────
            SectionCard(stringResource(R.string.settings_notifications)) {
                Column(Modifier.padding(Spacing.ml)) {
                    Text(
                        stringResource(R.string.settings_notif_drop_pct),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        stringResource(R.string.settings_notif_drop_pct_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        listOf(1, 3, 5, 10).forEach { pct ->
                            FilterChip(
                                selected = state.notifDropPercent == pct,
                                onClick = { viewModel.setNotifDropPercent(pct) },
                                label = { Text("$pct%") },
                            )
                        }
                    }
                }
            }

            // ── データ ─────────────────────────────────
            SectionCard(stringResource(R.string.settings_data)) {
                if (state.isPremium) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_export_csv)) },
                        supportingContent = { Text(stringResource(R.string.settings_export_desc)) },
                        modifier = Modifier.padding(Spacing.ml).clickable(role = Role.Button) { viewModel.exportCsv() },
                    )
                    HorizontalDivider()
                }
                // ウォッチリスト バックアップ/復元 — 全ユーザー無料 (機種変更・再インストール対策)。
                // 上記 CSV エクスポートは価格履歴の分析用データ抽出 (Premium 限定) で別機能。
                ListItem(
                    headlineContent = { Text(stringResource(R.string.watchlist_backup)) },
                    supportingContent = { Text(stringResource(R.string.watchlist_backup_desc)) },
                    modifier = Modifier.padding(Spacing.ml).clickable(role = Role.Button) { viewModel.backupWatchlist() },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.watchlist_restore)) },
                    supportingContent = { Text(stringResource(R.string.watchlist_restore_desc)) },
                    modifier = Modifier.padding(Spacing.ml).clickable(role = Role.Button) {
                        restoreLauncher.launch(arrayOf("application/json"))
                    },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_history)) },
                    modifier = Modifier.padding(Spacing.ml).clickable(role = Role.Button) { viewModel.clearSearchHistory() },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_watchlist)) },
                    modifier = Modifier.padding(Spacing.ml).clickable(role = Role.Button) { viewModel.clearWatchlist() },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text(
                            stringResource(R.string.settings_delete_all),
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    supportingContent = { Text(stringResource(R.string.settings_delete_desc)) },
                    modifier = Modifier
                        .padding(Spacing.ml)
                        .clickable(role = Role.Button) { deleteDialogVisible = true },
                )
            }

            // ── ツール ─────────────────────────────────
            SectionCard(stringResource(R.string.settings_tools)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.customs_simulate)) },
                    supportingContent = { Text(stringResource(R.string.customs_entry_desc)) },
                    modifier = Modifier.padding(Spacing.ml).clickable(role = Role.Button) { onCustoms() },
                )
            }

            // ── サブスク ───────────────────────────────
            SectionCard(stringResource(if (state.isPremium) R.string.settings_premium else R.string.settings_upgrade)) {
                if (state.isPremium) {
                    Text(stringResource(R.string.settings_premium_active), Modifier.padding(Spacing.ml))
                } else {
                    Column(Modifier.padding(Spacing.ml)) {
                        Text(stringResource(R.string.settings_premium_features), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(Spacing.ml))
                        // 「アフィリエイトリンク非表示」「AIアドバイス詳細表示」は過去ここに特典として
                        // 記載されていたが、前者は無料設定 (settings_affiliate_optin) と機能的に同一、
                        // 後者は isPremium によるゲートが実装のどこにも存在せず無料ユーザーと同一体験
                        // だった (機能過不足監査で発見)。実際に isPremium でゲートされている CSV
                        // エクスポートのみを特典として訴求する。
                        Text("• " + stringResource(R.string.premium_feature_export), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(Spacing.ml))
                        Button(
                            onClick = { activity?.let { viewModel.launchPurchase(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(stringResource(R.string.settings_premium_price))
                        }
                    }
                }
            }

            // ── 情報 ───────────────────────────────────
            SectionCard(stringResource(R.string.settings_info)) {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    trailingContent = { Text(state.appVersion) },
                    modifier = Modifier.padding(Spacing.ml),
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_privacy_policy)) },
                    modifier = Modifier.padding(Spacing.ml).clickable(role = Role.Button) { viewModel.openPrivacy() },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                    modifier = Modifier.padding(Spacing.ml).clickable(role = Role.Button) { onLicenses() },
                )
            }
        }
    }

    if (deleteDialogVisible) {
        AlertDialog(
            onDismissRequest = { deleteDialogVisible = false },
            title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
            text = {
                Text(stringResource(R.string.settings_delete_confirm_body))
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteAllData()
                    deleteDialogVisible = false
                }) {
                    Text(stringResource(R.string.settings_delete_action), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteDialogVisible = false }) {
                    Text(stringResource(R.string.settings_cancel))
                }
            },
        )
    }

    state.restoreResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = viewModel::clearRestoreResult,
            title = { Text(stringResource(R.string.watchlist_restore)) },
            text = { Text(message.asString()) },
            confirmButton = {
                TextButton(onClick = viewModel::clearRestoreResult) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            // TalkBack の見出しナビ: 設定は 7 セクションある縦長画面なので、
            // 各セクション見出しを heading 化すると「見出し単位ジャンプ」で素早く移動できる。
            modifier = Modifier.padding(start = 10.dp, bottom = 10.dp).a11yHeading(),
            color = MaterialTheme.colorScheme.primary,
        )
        Card(
            shape = RoundedCornerShape(CornerRadius.card),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(content = content)
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(description, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        },
        modifier = Modifier.padding(Spacing.ml),
    )
}

// modifier helper
