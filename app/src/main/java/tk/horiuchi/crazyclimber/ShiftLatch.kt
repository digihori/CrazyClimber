package tk.horiuchi.crazyclimber.core

/**
 * 左右のレバーを同方向に短時間で倒した場合だけ、その方向を返す。
 * windowMs は許容する同時押しの時間幅（ミリ秒）
 */
class ShiftLatch(private val windowMs: Long) {
    private var lastDir: LeverDir = LeverDir.CENTER
    private var lastTime: Long = 0L

    fun feed(left: LeverDir, right: LeverDir, nowMs: Long): LeverDir {
        // 左右の入力方向が同じ（LEFT または RIGHT）の場合だけ見る
        if (left == right && (left == LeverDir.LEFT || left == LeverDir.RIGHT)) {
            if (lastDir == left && (nowMs - lastTime) <= windowMs) {
                // 同じ方向で時間内ならヒット
                lastDir = LeverDir.CENTER
                return left
            }
            lastDir = left
            lastTime = nowMs
        } else {
            // 中立や異方向の場合はラッチ解除
            lastDir = LeverDir.CENTER
        }
        return LeverDir.CENTER
    }

    fun reset() {
        lastDir = LeverDir.CENTER
        lastTime = 0L
    }
}
