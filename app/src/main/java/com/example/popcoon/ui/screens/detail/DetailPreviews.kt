package com.example.popcoon.ui.screens.detail

import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.example.popcoon.ui.components.ProductDetailSkeleton
import com.example.popcoon.ui.theme.PopcoonTheme

/**
 * ProductDetailScreen 関連コンポーネントの @Preview。
 */

@Preview(name = "ProductDetailSkeleton – Light", showBackground = true)
@Composable
private fun DetailSkeletonLightPreview() {
    PopcoonTheme { Surface { ProductDetailSkeleton() } }
}

@Preview(name = "ProductDetailSkeleton – Dark", showBackground = true,
    uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun DetailSkeletonDarkPreview() {
    PopcoonTheme(darkTheme = true) { Surface { ProductDetailSkeleton() } }
}
