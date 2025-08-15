package tk.horiuchi.crazyclimber.core

object Config {
    const val COLS = 5
    const val FLOORS = 60
    const val TILE = 128
    const val FPS = 30
    const val DT_MS = 1000 / FPS
    const val SIMUL_PRESS_WINDOW_MS = 200

    const val WINDOW_BASE_MS = 2800L
    const val WINDOW_MIN_MS  = 1200L
    const val WINDOW_FLOOR_COEF = 4L     // 階が上がるごとの加速（小さめ）
    const val WINDOW_JITTER_MS  = 900L   // ランダム揺らぎ
}
