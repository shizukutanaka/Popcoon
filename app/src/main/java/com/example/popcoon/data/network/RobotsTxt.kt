package com.example.popcoon.data.network

/**
 * 最小限の robots.txt パーサ / 判定器。
 *
 * FallbackScraper が個別商品ページを取得する前に、対象サイトの robots.txt を
 * 尊重するために使う。純関数 (ネットワーク非依存) なので単体テスト可能。
 *
 * 仕様 (REP / Google 準拠の簡易版):
 *  - `User-agent` レコードを行単位でグルーピング
 *  - 対象 UA に一致するグループ、無ければ `*` グループの規則を適用
 *  - `Allow` / `Disallow` は「最長一致」を採用、同長なら Allow を優先
 *  - 空の `Disallow:` は「全許可」、規則なしも許可
 *  - パターン末尾以外の `*` ワイルドカードと `$` 終端アンカーに対応
 */
object RobotsTxt {

    private data class Rule(val allow: Boolean, val pattern: String)

    /**
     * [robotsTxt] の内容に基づき、[path] が [userAgent] に許可されるか判定する。
     * パースに失敗・該当規則なしの場合は許可 (true)。
     */
    fun isAllowed(robotsTxt: String, path: String, userAgent: String = "*"): Boolean {
        val normalizedPath = path.ifEmpty { "/" }
        val groups = parseGroups(robotsTxt)
        // UA 一致 (部分一致・大小無視) → 無ければ "*" グループ
        val ua = userAgent.lowercase()
        val rules = groups.entries
            .firstOrNull { (agent, _) -> agent != "*" && ua.contains(agent) }
            ?.value
            ?: groups["*"]
            ?: return true

        var best: Rule? = null
        for (rule in rules) {
            if (rule.pattern.isEmpty()) continue // 空 Disallow = 全許可、マッチ対象外
            if (matches(rule.pattern, normalizedPath)) {
                if (best == null ||
                    rule.pattern.length > best.pattern.length ||
                    (rule.pattern.length == best.pattern.length && rule.allow)
                ) {
                    best = rule
                }
            }
        }
        return best?.allow ?: true
    }

    private fun parseGroups(robotsTxt: String): Map<String, List<Rule>> {
        val groups = LinkedHashMap<String, MutableList<Rule>>()
        var currentAgents = mutableListOf<String>()
        var sawDirectiveSinceAgent = false

        for (rawLine in robotsTxt.lineSequence()) {
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) continue
            val colon = line.indexOf(':')
            if (colon < 0) continue
            val field = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim()

            when (field) {
                "user-agent" -> {
                    // 直前に規則が来ていたら新しいグループの開始
                    if (sawDirectiveSinceAgent) {
                        currentAgents = mutableListOf()
                        sawDirectiveSinceAgent = false
                    }
                    currentAgents.add(value.lowercase())
                    groups.getOrPut(value.lowercase()) { mutableListOf() }
                }
                "allow", "disallow" -> {
                    sawDirectiveSinceAgent = true
                    val agents = currentAgents.ifEmpty { listOf("*") }
                    for (agent in agents) {
                        groups.getOrPut(agent) { mutableListOf() }
                            .add(Rule(allow = field == "allow", pattern = value))
                    }
                }
            }
        }
        return groups
    }

    /** robots.txt パターン (`*` ワイルドカード、`$` 終端) と path のマッチ。 */
    private fun matches(pattern: String, path: String): Boolean {
        val anchored = pattern.endsWith("$")
        val core = if (anchored) pattern.dropLast(1) else pattern
        val parts = core.split("*")
        var index = 0
        for ((i, part) in parts.withIndex()) {
            if (part.isEmpty()) continue
            val found = path.indexOf(part, index)
            if (i == 0) {
                if (!path.startsWith(part)) return false
                index = part.length
            } else {
                if (found < 0) return false
                index = found + part.length
            }
        }
        if (anchored) {
            // 最終 part が path の末尾で終わる必要がある
            val last = parts.lastOrNull { it.isNotEmpty() } ?: return path.length == index || core == "*"
            return path.endsWith(last)
        }
        return true
    }
}
