package tk.horiuchi.crazyclimber.core

enum class UnstablePattern { NONE, LUP_RDOWN, LDOWN_RUP }

class InputSequencer {
    private var lastPattern = UnstablePattern.NONE
    private var halfStep = false

    private fun classify(left: LeverDir, right: LeverDir): UnstablePattern = when {
        left == LeverDir.UP && right == LeverDir.DOWN -> UnstablePattern.LUP_RDOWN  // ＞
        left == LeverDir.DOWN && right == LeverDir.UP -> UnstablePattern.LDOWN_RUP  // ＜
        else -> UnstablePattern.NONE
    }

    /**
     * 交互入力で true（＝1段上昇）
     * - A(＞) → B(＜) で上昇
     * - B(＜) → A(＞) でも上昇
     * - 同じ不安定パターンの維持はカウントしない
     * - 両UP/両DOWN/CENTERは無視
     */
    fun feedForClimb(left: LeverDir, right: LeverDir): Boolean {
        val cur = classify(left, right)
        if (cur == UnstablePattern.NONE) return false

        // 直前と違う不安定入力になったら半歩 → 1段
        if (cur != lastPattern) {
            if (halfStep) {
                halfStep = false
                lastPattern = cur
                return true // 2回目で1段
            } else {
                halfStep = true
            }
            lastPattern = cur
        }
        return false
    }

    fun reset() {
        lastPattern = UnstablePattern.NONE
        halfStep = false
    }
}
