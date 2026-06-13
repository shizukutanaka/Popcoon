package com.example.popcoon.feature.notification

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class StockAlertEvaluatorTest : StringSpec({

    "在庫復活 (false→true) はアラート有効なら BACK_IN_STOCK" {
        StockAlertEvaluator.evaluate(
            previouslyInStock = false,
            currentlyInStock = true,
            stockAlertEnabled = true,
        ) shouldBe StockAlertEvaluator.Kind.BACK_IN_STOCK
    }

    "在庫切れ (true→false) はアラート有効なら OUT_OF_STOCK" {
        StockAlertEvaluator.evaluate(
            previouslyInStock = true,
            currentlyInStock = false,
            stockAlertEnabled = true,
        ) shouldBe StockAlertEvaluator.Kind.OUT_OF_STOCK
    }

    "変化なし (true→true) は NONE" {
        StockAlertEvaluator.evaluate(
            previouslyInStock = true,
            currentlyInStock = true,
            stockAlertEnabled = true,
        ) shouldBe StockAlertEvaluator.Kind.NONE
    }

    "変化なし (false→false) は NONE" {
        StockAlertEvaluator.evaluate(
            previouslyInStock = false,
            currentlyInStock = false,
            stockAlertEnabled = true,
        ) shouldBe StockAlertEvaluator.Kind.NONE
    }

    "初回同期 (previouslyInStock=null) は NONE — 基準なし" {
        StockAlertEvaluator.evaluate(
            previouslyInStock = null,
            currentlyInStock = true,
            stockAlertEnabled = true,
        ) shouldBe StockAlertEvaluator.Kind.NONE
    }

    "アラート無効なら在庫復活でも NONE" {
        StockAlertEvaluator.evaluate(
            previouslyInStock = false,
            currentlyInStock = true,
            stockAlertEnabled = false,
        ) shouldBe StockAlertEvaluator.Kind.NONE
    }

    "アラート無効なら在庫切れでも NONE" {
        StockAlertEvaluator.evaluate(
            previouslyInStock = true,
            currentlyInStock = false,
            stockAlertEnabled = false,
        ) shouldBe StockAlertEvaluator.Kind.NONE
    }
})
