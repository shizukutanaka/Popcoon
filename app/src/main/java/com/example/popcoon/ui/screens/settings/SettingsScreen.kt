package com.example.popcoon.ui.screens.settings

import android.app.Activity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.Spacing

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = androidx.hilt.navigation.compose.hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var deleteDialogVisible by remember { mutableStateOf(false) }
    val activity = LocalContext.current as? Activity

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る")
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
            SectionCard("プライバシー") {
                ToggleRow(
                    title = stringResource(R.string.settings_crash_optin),
                    description = "匿名化したスタックトレースのみ送信",
                    checked = state.crashOptin,
                    onCheckedChange = viewModel::setCrashOptin,
                )
                ToggleRow(
                    title = stringResource(R.string.settings_ai_optin),
                    description = "Claude API に商品情報を送信 (個人情報なし)",
                    checked = state.aiOptin,
                    onCheckedChange = viewModel::setAiOptin,
                )
                ToggleRow(
                    title = stringResource(R.string.settings_affiliate_optin),
                    description = "OFF にすると素の URL を使用 (収益発生せず)",
                    checked = state.affiliateOptin,
                    onCheckedChange = viewModel::setAffiliateOptin,
                )
            }

            // ── データ ─────────────────────────────────
            SectionCard("データ") {
                if (state.isPremium) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_export_csv)) },
                        supportingContent = { Text(stringResource(R.string.settings_export_desc)) },
                        modifier = Modifier.padding(Spacing.ml).clickable { viewModel.exportCsv() },
                    )
                    HorizontalDivider()
                }
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_history)) },
                    modifier = Modifier.padding(Spacing.ml).clickable { viewModel.clearSearchHistory() },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_clear_watchlist)) },
                    modifier = Modifier.padding(Spacing.ml).clickable { viewModel.clearWatchlist() },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = {
                        Text(
                            "全データ削除 (GDPR Article 17)",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.SemiBold,
                        )
                    },
                    supportingContent = { Text(stringResource(R.string.settings_delete_desc)) },
                    modifier = Modifier
                        .padding(Spacing.ml)
                        .clickable { deleteDialogVisible = true },
                )
            }

            // ── サブスク ───────────────────────────────
            SectionCard(if (state.isPremium) "Premium" else "アップグレード") {
                if (state.isPremium) {
                    Text(stringResource(R.string.settings_premium_active), Modifier.padding(Spacing.ml))
                } else {
                    Column(Modifier.padding(Spacing.ml)) {
                        Text(stringResource(R.string.settings_premium_features), fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(Spacing.ml))
                        Text("• " + stringResource(R.string.premium_feature_no_ads), style = MaterialTheme.typography.bodyMedium)
                        Text("• " + stringResource(R.string.premium_feature_export), style = MaterialTheme.typography.bodyMedium)
                        Text("• " + stringResource(R.string.premium_feature_ai_detail), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(Spacing.ml))
                        Button(
                            onClick = { activity?.let { viewModel.launchPurchase(it) } },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("¥480/月 または ¥3,800/年")
                        }
                    }
                }
            }

            // ── 情報 ───────────────────────────────────
            SectionCard("情報") {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_version)) },
                    trailingContent = { Text(state.appVersion) },
                    modifier = Modifier.padding(Spacing.ml),
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_privacy_policy)) },
                    modifier = Modifier.padding(Spacing.ml).clickable { viewModel.openPrivacy() },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings_licenses)) },
                    modifier = Modifier.padding(Spacing.ml).clickable { viewModel.openLicenses() },
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
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 10.dp, bottom = 10.dp),
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
