package io.github.shizukutanaka.popcoon.ui.screens.licenses

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.shizukutanaka.popcoon.R
import io.github.shizukutanaka.popcoon.ui.theme.AppIcons
import io.github.shizukutanaka.popcoon.ui.theme.Spacing

/**
 * 同梱 OSS ライブラリのライセンス表記画面。
 *
 * 以前は SettingsViewModel.openLicenses() が自プロジェクトの LICENSE (MIT) を
 * GitHub で開くだけの暫定実装で、実際に同梱している OSS 依存 (Compose/Ktor/
 * Room/Hilt 等) の表記が一切なかった (商用リリース監査で発見)。
 * [OssLicenses] の静的リストをここで一覧表示し、タップで該当ライセンス全文を表示する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    var selectedLicense by remember { mutableStateOf<LicenseType?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_licenses)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.padding(padding)) {
            item {
                Text(
                    stringResource(R.string.licenses_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.ml),
                )
            }
            items(OssLicenses.entries, key = { it.name }) { entry ->
                ListItem(
                    headlineContent = { Text(entry.name) },
                    supportingContent = { Text("${entry.version} — ${entry.license.displayName}") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { selectedLicense = entry.license },
                )
                HorizontalDivider()
            }
        }
    }

    selectedLicense?.let { license ->
        AlertDialog(
            onDismissRequest = { selectedLicense = null },
            title = { Text(license.displayName) },
            text = {
                Text(
                    license.fullText ?: stringResource(R.string.license_android_sdk_text),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    modifier = Modifier
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { selectedLicense = null }) {
                    Text(stringResource(R.string.action_dismiss))
                }
            },
        )
    }
}
