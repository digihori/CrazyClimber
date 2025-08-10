package tk.horiuchi.crazyclimber.core

import kotlin.math.max
import kotlin.random.Random

class World {

    private val rnd = Random(0xCC1122)
    val player = Player()

    // 5xFLOORS の窓状態と次遷移時刻
    private val windows = Array(Config.FLOORS) { Array(Config.COLS) { WindowState.OPEN } }
    private val nextTick = Array(Config.FLOORS) { Array(Config.COLS) { 0L } }

    //private val inputSeq = InputSequencer()
    private val shiftLatch = ShiftLatch(Config.SIMUL_PRESS_WINDOW_MS.toLong())

    var currentTimeMs: Long = 0
    // ポーズベース登攀の状態
    private var lastPoseUnstable: UnstablePattern = UnstablePattern.NONE
    private var poseHalfStep = false
    // 直近の不安定姿勢（＞ or ＜）だけ覚える。未確定は null。
    private var lastUnstable: UnstablePattern? = null


    init {
        // 適当に初期化
        for (f in 0 until Config.FLOORS) {
            for (c in 0 until Config.COLS) {
                windows[f][c] = WindowState.OPEN
                nextTick[f][c] = (500L + rnd.nextLong(800))
            }
        }
    }

    fun getWindow(cell: Cell): WindowState =
        windows[cell.floor.coerceIn(0, Config.FLOORS - 1)][cell.col.coerceIn(0, Config.COLS - 1)]

    fun update(dtMs: Long) {
        currentTimeMs += dtMs
        updateWindows()
        // クリア条件（仮）：最上階に到達したら固定ボーナス
        if (player.pos.floor >= Config.FLOORS - 1) {
            // クリア演出は後で。とりあえず何もしない
        }
    }

    private fun updateWindows() {
        for (f in 0 until Config.FLOORS) {
            for (c in 0 until Config.COLS) {
                windows[f][c] = WindowState.OPEN
                /*
                if (currentTimeMs >= nextTick[f][c]) {
                    windows[f][c] = when (windows[f][c]) {
                        WindowState.OPEN -> WindowState.HALF
                        WindowState.HALF -> WindowState.CLOSED
                        WindowState.CLOSED -> WindowState.OPEN
                    }
                    val base = max(
                        Config.WINDOW_MIN_MS.toLong(),
                        Config.WINDOW_BASE_MS.toLong() - f * 12L
                    )
                    nextTick[f][c] = currentTimeMs + base + rnd.nextLong(400)
                }

                 */
            }
        }
        // 掴んでいる窓が閉まったら即落下
        if (getWindow(player.pos) == WindowState.CLOSED) {
            player.fall()
            // チェックポイント復帰（10階ごと）
            val cpFloor = (player.pos.floor / 10) * 10
            player.pos = Cell(player.pos.col, cpFloor)
            // 安全な窓に矯正（OPEN/HALFを探す）
            if (getWindow(player.pos) == WindowState.CLOSED) {
                windows[player.pos.floor][player.pos.col] = WindowState.OPEN
            }
        }
    }

    private fun isStable(left: LeverDir, right: LeverDir): Boolean =
        (left == LeverDir.UP && right == LeverDir.UP) ||
                (left == LeverDir.DOWN && right == LeverDir.DOWN)

    // Lever入力から行動を決定
    fun handleInput(left: LeverDir, right: LeverDir) {
        // 1) まず見た目ポーズを確定（センターなら維持）
        updatePlayerPose(left, right)

        // 2) ポーズの不安定 A↔B の切替だけで登攀を数える
        // --- 上昇：＞と＜が“切り替わった瞬間”を1段とする（超シンプル） ---
        val cur = unstableOrNull(left, right)
        // CENTER や 左右 や 安定（▽/△）のときは cur == null なので状態維持（何もしない）
        if (cur != null && cur != lastUnstable) {
            // 直前が不安定（＞or＜）なら切替成立 → 1段上昇
            if (lastUnstable != null) {
                val next = Cell(player.pos.col, (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1))
                if (getWindow(next) != WindowState.CLOSED) {
                    player.stepClimbAccepted()
                }
            }
            // 今回の不安定を記録
            lastUnstable = cur
        }

        // 横移動：安定時のみ、同方向同時（200ms）
        if (player.stability == Stability.STABLE) {
            val dir = shiftLatch.feed(left, right, currentTimeMs)
            if (dir == LeverDir.LEFT || dir == LeverDir.RIGHT) {
                val dx = if (dir == LeverDir.LEFT) -1 else 1
                val next = Cell((player.pos.col + dx).coerceIn(0, Config.COLS - 1), player.pos.floor)
                if (getWindow(next) != WindowState.CLOSED) {
                    player.shift(dx)
                }
            }
        } else {
            // 不安定中は横移動受け付けなし
            shiftLatch.reset()
        }

    }

    // プレイヤーの見た目ポーズを更新する処理の例
    fun updatePlayerPose(left: LeverDir, right: LeverDir) {
        // レバーが両方 CENTER の場合はポーズ変更なし
        if (left == LeverDir.CENTER && right == LeverDir.CENTER) {
            return // 何もせず終了 → 前回のポーズを保持
        }

        player.pose = when {
            left == LeverDir.UP && right == LeverDir.UP -> PlayerPose.BOTH_UP
            left == LeverDir.DOWN && right == LeverDir.DOWN -> PlayerPose.BOTH_DOWN
            left == LeverDir.UP && right == LeverDir.DOWN -> PlayerPose.LUP_RDOWN
            left == LeverDir.DOWN && right == LeverDir.UP -> PlayerPose.LDOWN_RUP
            else -> player.pose // 不明パターンは状態維持
        }
    }

    private fun poseToUnstable(pose: PlayerPose): UnstablePattern = when (pose) {
        PlayerPose.LUP_RDOWN -> UnstablePattern.LUP_RDOWN  // ＞
        PlayerPose.LDOWN_RUP -> UnstablePattern.LDOWN_RUP  // ＜
        else -> UnstablePattern.NONE                       // ▽ / △ は安定
    }

    private fun unstableOrNull(left: LeverDir, right: LeverDir): UnstablePattern? = when {
        left == LeverDir.UP   && right == LeverDir.DOWN -> UnstablePattern.LUP_RDOWN // ＞
        left == LeverDir.DOWN && right == LeverDir.UP   -> UnstablePattern.LDOWN_RUP // ＜
        else -> null
    }


}
