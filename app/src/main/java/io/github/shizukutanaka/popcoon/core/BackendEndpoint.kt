package io.github.shizukutanaka.popcoon.core

internal fun backendEndpoint(baseUrl: String, path: String): String? =
    baseUrl.trim().trimEnd('/').takeIf { it.isNotEmpty() }?.let { "$it$path" }
