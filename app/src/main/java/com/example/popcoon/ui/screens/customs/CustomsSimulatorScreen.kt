package com.example.popcoon.ui.screens.customs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenu
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.popcoon.R
import com.example.popcoon.core.CurrencyFormatter
import com.example.popcoon.feature.crossborder.CustomsSimulator
import com.example.popcoon.ui.theme.AppIcons
import com.example.popcoon.ui.theme.CornerRadius
import com.example.popcoon.ui.theme.PopcoonTheme
import com.example.popcoon.ui.theme.Spacing

/**
 * 越境EC着払い価格シミュレータ画面。
 *
 * テスト済み純ロジック [CustomsSimulator] (Python `simulate_customs` と完全一致) は
 * 実装済みだが UI 導線が無く死蔵していた。海外通販で買う前に「関税・消費税・手数料込みの
 * 着払い総額」と「国内最安値と比べてお得か」を試算できるようにする。
 *
 * `SaleCalendarScreen` と同様、ViewModel 不要 — 入力を `remember` で純計算するだけ。
 * 設定画面の「ツール」セクションから遷移する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomsSimulatorScreen(onBack: () -> Unit) {
    var foreign by remember { mutableStateOf("") }
    var shipping by remember { mutableStateOf("") }
    var japan by remember { mutableStateOf("") }
    var categoryIndex by remember { mutableIntStateOf(DEFAULT_CATEGORY_INDEX) }
    var categoryExpanded by remember { mutableStateOf(false) }

    val categoryKey = CUSTOMS_CATEGORIES[categoryIndex].first
    val japanPrice = japan.toLongOrNull()
    val result = remember(foreign, shipping, japan, categoryKey) {
        val f = foreign.toLongOrNull() ?: return@remember null
        CustomsSimulator.simulate(
            foreignPriceJpy = f,
            shippingJpy = shipping.toLongOrNull() ?: 0L,
            category = categoryKey,
            japanBestPrice = japanPrice,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.customs_simulate)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.Back, stringResource(R.string.nav_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(Spacing.ml)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Spacing.ml),
        ) {
            NumberField(
                value = foreign,
                onValueChange = { foreign = it.filter(Char::isDigit) },
                label = stringResource(R.string.customs_foreign_price),
            )
            NumberField(
                value = shipping,
                onValueChange = { shipping = it.filter(Char::isDigit) },
                label = stringResource(R.string.customs_shipping),
            )

            ExposedDropdownMenuBox(
                expanded = categoryExpanded,
                onExpandedChange = { categoryExpanded = it },
            ) {
                OutlinedTextField(
                    value = stringResource(CUSTOMS_CATEGORIES[categoryIndex].second),
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.customs_category)) },
                    trailingIcon = {
                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                    },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = categoryExpanded,
                    onDismissRequest = { categoryExpanded = false },
                ) {
                    CUSTOMS_CATEGORIES.forEachIndexed { i, (_, labelRes) ->
                        DropdownMenuItem(
                            text = { Text(stringResource(labelRes)) },
                            onClick = {
                                categoryIndex = i
                                categoryExpanded = false
                            },
                        )
                    }
                }
            }

            NumberField(
                value = japan,
                onValueChange = { japan = it.filter(Char::isDigit) },
                label = stringResource(R.string.customs_japan_price),
            )

            result?.let { r -> ResultCard(result = r, hasComparison = japanPrice != null) }
        }
    }
}

@Composable
private fun NumberField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ResultCard(result: CustomsSimulator.Result, hasComparison: Boolean) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(CornerRadius.card),
    ) {
        Column(Modifier.padding(Spacing.lg)) {
            Text(
                stringResource(R.string.customs_result),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )

            if (hasComparison) {
                Surface(
                    color = verdictColor(result.verdict).copy(alpha = 0.15f),
                    shape = RoundedCornerShape(CornerRadius.pill),
                    modifier = Modifier.padding(top = Spacing.md),
                ) {
                    Text(
                        text = stringResource(verdictLabel(result.verdict)),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = verdictColor(result.verdict),
                        modifier = Modifier.padding(horizontal = Spacing.ml, vertical = Spacing.sm),
                    )
                }
            }

            Column(Modifier.padding(top = Spacing.ml)) {
                AmountRow(stringResource(R.string.customs_dutiable), result.dutiableValue)
                AmountRow(stringResource(R.string.customs_duty), result.customsDuty)
                AmountRow(stringResource(R.string.customs_consumption_tax), result.consumptionTax)
                AmountRow(stringResource(R.string.customs_fee), result.handlingFee)
                if (result.isTaxExempt) {
                    Text(
                        stringResource(R.string.customs_exempt),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = Spacing.xs),
                    )
                }
                HorizontalDivider(Modifier.padding(vertical = Spacing.md))
                AmountRow(
                    label = stringResource(R.string.customs_total),
                    amount = result.totalLandedCost,
                    emphasize = true,
                )
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: Long, emphasize: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            CurrencyFormatter.yen(amount),
            style = if (emphasize) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
            fontWeight = if (emphasize) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

@Composable
private fun verdictColor(verdict: CustomsSimulator.Verdict): Color = when (verdict) {
    CustomsSimulator.Verdict.CHEAPER -> MaterialTheme.colorScheme.primary
    CustomsSimulator.Verdict.COMPARABLE -> Color(0xFFB8860B)
    CustomsSimulator.Verdict.MORE_EXPENSIVE -> MaterialTheme.colorScheme.onSurfaceVariant
    CustomsSimulator.Verdict.NOT_RECOMMENDED -> MaterialTheme.colorScheme.error
}

private fun verdictLabel(verdict: CustomsSimulator.Verdict): Int = when (verdict) {
    CustomsSimulator.Verdict.CHEAPER -> R.string.customs_verdict_cheaper
    CustomsSimulator.Verdict.COMPARABLE -> R.string.customs_verdict_comparable
    CustomsSimulator.Verdict.MORE_EXPENSIVE -> R.string.customs_verdict_more_expensive
    CustomsSimulator.Verdict.NOT_RECOMMENDED -> R.string.customs_verdict_not_recommended
}

// CustomsSimulator.DUTY_RATES のキー (日本語) と表示用文字列リソースの対応。
// simulate() には日本語キーをそのまま渡す必要があるため一覧で保持する。
private val CUSTOMS_CATEGORIES: List<Pair<String, Int>> = listOf(
    "衣類" to R.string.customs_cat_clothing,
    "靴" to R.string.customs_cat_shoes,
    "バッグ" to R.string.customs_cat_bag,
    "電子機器" to R.string.customs_cat_electronics,
    "カメラ" to R.string.customs_cat_camera,
    "おもちゃ" to R.string.customs_cat_toy,
    "スポーツ用品" to R.string.customs_cat_sports,
    "化粧品" to R.string.customs_cat_cosmetics,
    "食品" to R.string.customs_cat_food,
    "その他" to R.string.customs_cat_other,
)

// 既定は「電子機器」(ITA 無税、越境で最も比較ニーズが高い)。
private const val DEFAULT_CATEGORY_INDEX = 3

@Preview(name = "CustomsSimulator", showBackground = true)
@Composable
private fun CustomsSimulatorScreenPreview() {
    PopcoonTheme {
        CustomsSimulatorScreen(onBack = {})
    }
}
