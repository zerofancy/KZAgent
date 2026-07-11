package com.kzagent.kagent.tools

class ToolQuota(
    val baseCredits: Int = 100,
    val extendAmount: Int = 50,
    val warningThreshold: Int = 25,
) {
    var remaining: Int = baseCredits
        private set
    var extensionsUsed: Int = 0
        private set
    var totalConsumed: Int = 0
        private set

    val isExhausted: Boolean get() = remaining <= 0
    val isLow: Boolean get() = remaining <= warningThreshold

    fun canAfford(cost: Int): Boolean = remaining >= cost

    fun deduct(cost: Int): Boolean {
        if (cost <= 0) return true
        if (remaining < cost) return false
        remaining -= cost
        totalConsumed += cost
        return true
    }

    fun extend(): Boolean {
        remaining += extendAmount
        extensionsUsed++
        return true
    }

    fun statusLine(): String {
        val total = totalConsumed + remaining
        val pct = if (total > 0) (totalConsumed * 100) / total else 0
        val ext = if (extensionsUsed > 0) " (已扩容 ${extensionsUsed}x)" else ""
        return "配额: 已用 ${totalConsumed} 积分, 剩余 ${remaining} 积分, 总消耗率 ${pct}%$ext"
    }
}
