package tk.horiuchi.crazyclimber.core

class Player {
    var pos = Cell(col = 2, floor = 0)   // 中央列スタート
    var hands = HandPair.BOTH_UP
    var anim = PlayerAnim.IDLE
    var lives = 3
    var score = 0

    var pose: PlayerPose = PlayerPose.BOTH_UP

    val stability: Stability
        get() = when (hands) {
            HandPair.BOTH_UP, HandPair.BOTH_DOWN -> Stability.STABLE
            else -> Stability.UNSTABLE
        }

    fun stepClimbAccepted() {
        anim = PlayerAnim.CLIMBING
        pos = pos.copy(floor = pos.floor + 1)
        score += 100
    }
    fun shift(dx: Int) {
        anim = PlayerAnim.SHIFTING
        pos = pos.copy(col = (pos.col + dx).coerceIn(0, Config.COLS - 1))
    }
    fun hit(penalize: Boolean) { if (penalize) score -= 100 }
    fun fall() { anim = PlayerAnim.FALLING; lives-- }
}
