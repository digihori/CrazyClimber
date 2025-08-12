package tk.horiuchi.crazyclimber.core

import android.util.Log
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
    private var lastUnstable: UnstablePattern? = null

    val ojisans = mutableListOf<Ojisan>()
    val pots    = mutableListOf<Pot>()

    // スポーン制御
    private var nextOjisanCheckMs = 0L
    private var safeUntilMs: Long = 3000L // 開始直後の無敵時間（しらけどり出現時もここで抑止予定）

    // クールダウン
    private var ojisanCooldownMs = 0L

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
        updateOjisanAndPots(dtMs)
        // クリア条件（仮）：最上階に到達したら固定ボーナス
        if (player.pos.floor >= Config.FLOORS - 1) {
            // クリア演出は後で。とりあえず何もしない
        }
    }

    private fun updateOjisanAndPots(dtMs: Long) {
        //Log.d("OjisanDebug", "updateOjisanAndPots() called")

        // ===== スポーンスケジューラ（追いかけっこ防止） =====
        if (currentTimeMs >= nextOjisanCheckMs) {
            if (currentTimeMs > safeUntilMs && ojisanCooldownMs <= 0L) {
                trySpawnOjisan() // ← ここでだけ呼ばれる
            }
            // 次の判定は固定間隔で予約（350～500msあたりで調整）
            nextOjisanCheckMs = currentTimeMs + 400L
        }
        if (ojisanCooldownMs > 0L) ojisanCooldownMs -= dtMs

        // 2) おじさんの進行と投擲
        val itO = ojisans.iterator()
        while (itO.hasNext()) {
            val o = itO.next()
            o.elapsedMs += dtMs
            val durIn  = 700f
            val durOut = 700f
            when (o.state) {
                Ojisan.State.SLIDE_IN -> {
                    o.t = (o.elapsedMs / durIn).coerceAtMost(1f)
                    if (o.t >= 1f) {
                        o.state = Ojisan.State.THROW
                        o.elapsedMs = 0L
                    }
                }
                Ojisan.State.THROW -> {
                    // その場で鉢生成（1回だけ）
                    pots += Pot(col = o.col, yFloor = o.floor + 0.8f)
                    o.state = Ojisan.State.SLIDE_OUT
                    o.elapsedMs = 0L
                    ojisanCooldownMs = 900L // 連続出現を少し抑える
                }
                Ojisan.State.SLIDE_OUT -> {
                    o.t = (o.elapsedMs / durOut).coerceAtMost(1f)
                    if (o.t >= 1f) {
                        o.state = Ojisan.State.DONE
                    }
                }
                Ojisan.State.DONE -> { itO.remove() }
            }
        }

        // 3) 植木鉢の落下と当たり
        val itP = pots.iterator()
        while (itP.hasNext()) {
            val p = itP.next()
            p.yFloor -= p.speedFloorsPerSec * (dtMs / 1000f)

            // 画面下（地上）で消滅
            if (p.yFloor < -1f) { itP.remove(); continue }

            // プレイヤーに当たった？（列一致＆同じ階帯）
            val hit = (p.col == player.pos.col) &&
                    (p.yFloor <= player.pos.floor + 0.4f && p.yFloor >= player.pos.floor - 0.4f)

            if (hit) {
                // 先にプレイヤー側の処理
                onPlayerHitByPot(p)

                // ★ 跳ね返り：衝突点を「1階上」にしてから横へ逃がす
                if (!p.bounced) {
                    val BOUNCE_OFFSET_F = 1.0f      // ← “1段上”の量。好みに応じて 0.8〜1.2 に調整可
                    p.yFloor = player.pos.floor + BOUNCE_OFFSET_F

                    // 横に1列ずらす（左右ランダム）
                    val dx = if (rnd.nextBoolean()) 1 else -1
                    p.col = (p.col + dx).coerceIn(0, Config.COLS - 1)
                    p.bounced = true

                    // ★ このフレームでの再判定を避ける
                    continue
                }
                // 既にバウンド済みならそのまま落下継続
            }
        }

    }

    /** プレイヤーが鉢に当たった時の処理 */
    private fun onPlayerHitByPot(p: Pot) {
        player.hit(true)
        when (player.stability) {
            Stability.STABLE -> {
                val imposed = if (rnd.nextBoolean()) {
                    player.hands = HandPair.L_UP_R_DOWN
                    player.pose  = PlayerPose.LUP_RDOWN
                    UnstablePattern.LUP_RDOWN
                } else {
                    player.hands = HandPair.L_DOWN_R_UP
                    player.pose  = PlayerPose.LDOWN_RUP
                    UnstablePattern.LDOWN_RUP
                }
                // ★被弾直後を“半歩目”として記録：次に反対を入れれば+1階
                lastUnstable = imposed
            }
            Stability.UNSTABLE -> {
                player.fall()
                val cp = (player.pos.floor / 10) * 10
                player.pos = Cell(player.pos.col, cp)
                // シーケンスはリセット
                lastUnstable = null
            }
        }
    }

    /** 上方の OPEN 窓から1体スポーン */
    private fun trySpawnOjisan() {
        val f0 = player.pos.floor + 2
        val f1 = (player.pos.floor + 4).coerceAtMost(Config.FLOORS - 1)
        if (f0 > f1) return

        // 上に行くほど出やすい（簡易）：floor/50 をベースに
        val prob = (player.pos.floor + 5).coerceAtMost(50) / 50f
        if (rnd.nextFloat() > prob) return

        // ランダムに列・階を選び、OPENなら出す
        repeat(6) {
            val f = rnd.nextInt(f0, f1 + 1)
            val c = rnd.nextInt(0, Config.COLS)
            if (getWindow(Cell(c, f)) == WindowState.OPEN) {
                ojisans += Ojisan(col = c, floor = f)
                return
            }
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
        if (mustFallByWindow()) {
            player.fall()
            val cpFloor = (player.pos.floor / 10) * 10
            player.pos = Cell(player.pos.col, cpFloor)
            // 安全化
            windows[player.pos.floor][player.pos.col] = WindowState.OPEN
            lastUnstable = null
        }

    }

    private fun mustFallByWindow(): Boolean {
        return when (player.hands) {
            HandPair.BOTH_UP,
            HandPair.L_UP_R_DOWN,
            HandPair.L_DOWN_R_UP -> {
                // 上に手がかかっている系：一段上の窓が閉なら落下
                val up = Cell(player.pos.col, (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1))
                getWindow(up) == WindowState.CLOSED
            }
            HandPair.BOTH_DOWN -> {
                // 両手下：現在の窓が閉なら落下
                getWindow(player.pos) == WindowState.CLOSED
            }
        }
    }


    private fun isStable(left: LeverDir, right: LeverDir): Boolean =
        (left == LeverDir.UP && right == LeverDir.UP) ||
                (left == LeverDir.DOWN && right == LeverDir.DOWN)

    // Lever入力から行動を決定
    private fun climb(toPose: PlayerPose) {
        val next = Cell(player.pos.col, (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1))
        if (getWindow(next) == WindowState.CLOSED) return
        player.stepClimbAccepted()
        player.pose = toPose
        player.hands = when (toPose) {
            PlayerPose.BOTH_UP   -> HandPair.BOTH_UP
            PlayerPose.BOTH_DOWN -> HandPair.BOTH_DOWN
            PlayerPose.LUP_RDOWN -> HandPair.L_UP_R_DOWN
            PlayerPose.LDOWN_RUP -> HandPair.L_DOWN_R_UP
        }
    }

    private fun applyPose(p: PlayerPose) {
        player.pose = p
        player.hands = when (p) {
            PlayerPose.BOTH_UP   -> HandPair.BOTH_UP
            PlayerPose.BOTH_DOWN -> HandPair.BOTH_DOWN
            PlayerPose.LUP_RDOWN -> HandPair.L_UP_R_DOWN
            PlayerPose.LDOWN_RUP -> HandPair.L_DOWN_R_UP
        }
    }

    private fun diagonalOf(left: LeverDir, right: LeverDir): UnstablePattern? = when {
        left == LeverDir.UP   && right == LeverDir.DOWN -> UnstablePattern.LUP_RDOWN
        left == LeverDir.DOWN && right == LeverDir.UP   -> UnstablePattern.LDOWN_RUP
        else -> null
    }

    fun handleInput(left: LeverDir, right: LeverDir) {
        val inputBothDown = (left == LeverDir.DOWN && right == LeverDir.DOWN)
        val inputBothUp   = (left == LeverDir.UP   && right == LeverDir.UP)
        val curDiag       = diagonalOf(left, right)

        val wasStable   = (player.hands == HandPair.BOTH_UP || player.hands == HandPair.BOTH_DOWN)
        val wasUnstable = !wasStable
        val nowMs       = currentTimeMs

        // --- 新仕様：BOTH_DOWN 中に片手だけ上げても登らない（ポーズのみ変更） ---
        if (player.hands == HandPair.BOTH_DOWN && curDiag != null) {
            // 見た目と内部状態だけ不安定へ。lastUnstableもセットしておくと次の逆斜めで登れる
            if (curDiag == UnstablePattern.LUP_RDOWN) {
                player.pose = PlayerPose.LUP_RDOWN
                player.hands = HandPair.L_UP_R_DOWN
            } else {
                player.pose = PlayerPose.LDOWN_RUP
                player.hands = HandPair.L_DOWN_R_UP
            }
            lastUnstable = curDiag
            // 横移動は不安定なので不可、処理終了
            return
        }
        // === 先に「仕様の2項」を強制適用 ===
        // (A) UNSTABLE → 両手下（BOTH_DOWN）で +1階（着地＝BOTH_DOWN）
        if (wasUnstable && inputBothDown) {
            val next = Cell(player.pos.col, (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1))
            if (getWindow(next) != WindowState.CLOSED) {
                player.stepClimbAccepted()
                applyPose(PlayerPose.BOTH_DOWN)
                lastUnstable = null   // シーケンス完了
            }
            // 横移動は安定に戻ってから判定させたいので、このフレームはここで終了
            return
        }

        // (A2) STABLE 両手上（BOTH_UP） → 両手下（BOTH_DOWN）で +1階
        if (wasStable && player.hands == HandPair.BOTH_UP && inputBothDown) {
            val next = Cell(player.pos.col, (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1))
            if (getWindow(next) != WindowState.CLOSED) {
                player.stepClimbAccepted()
                applyPose(PlayerPose.BOTH_DOWN)
            }
            return
        }

        // (B) BOTH_DOWN から BOTH_UP への直接遷移を禁止（見た目もロジックも維持）
        if (player.hands == HandPair.BOTH_DOWN && inputBothUp) {
            // ここではポーズを変えず、横移動だけ判定可能にする
            val dir = shiftLatch.feed(left, right, nowMs)
            if (dir == LeverDir.LEFT || dir == LeverDir.RIGHT) {
                val dx = if (dir == LeverDir.LEFT) -1 else 1
                val next = Cell((player.pos.col + dx).coerceIn(0, Config.COLS - 1), player.pos.floor)
                if (getWindow(next) != WindowState.CLOSED) player.shift(dx)
            }
            return
        }

        // === 通常の上昇ロジック ===

        // 1) STABLE → 斜め（＞/＜）で必ず +1階、着地＝その斜めのUNSTABLE
        if (wasStable && curDiag != null) {
            val poseAfter = if (curDiag == UnstablePattern.LUP_RDOWN)
                PlayerPose.LUP_RDOWN else PlayerPose.LDOWN_RUP
            val next = Cell(player.pos.col, (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1))
            if (getWindow(next) != WindowState.CLOSED) {
                player.stepClimbAccepted()
                applyPose(poseAfter)
                lastUnstable = curDiag
            }
            return
        }

        // 2) UNSTABLE → 反対斜めに切替で +1階、着地＝その斜めのUNSTABLE
        if (wasUnstable && curDiag != null && curDiag != lastUnstable) {
            val poseAfter = if (curDiag == UnstablePattern.LUP_RDOWN)
                PlayerPose.LUP_RDOWN else PlayerPose.LDOWN_RUP
            val next = Cell(player.pos.col, (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1))
            if (getWindow(next) != WindowState.CLOSED) {
                player.stepClimbAccepted()
                applyPose(poseAfter)
                lastUnstable = curDiag
            }
            return
        }

        // === 見た目ポーズの更新（CENTERや無関係入力では維持） ===
        // ここでは禁止事項（BOTH_DOWN→BOTH_UP）を再度尊重
        when {
            inputBothDown -> applyPose(PlayerPose.BOTH_DOWN)
            inputBothUp   -> {
                if (player.hands != HandPair.BOTH_DOWN) applyPose(PlayerPose.BOTH_UP)
                // 両下→両上は通さない
            }
            curDiag == UnstablePattern.LUP_RDOWN -> applyPose(PlayerPose.LUP_RDOWN)
            curDiag == UnstablePattern.LDOWN_RUP -> applyPose(PlayerPose.LDOWN_RUP)
            else -> { /* CENTERや左右はポーズ維持 */ }
        }

        // === 横移動（安定のみ） ===
        if (player.hands == HandPair.BOTH_UP || player.hands == HandPair.BOTH_DOWN) {
            val dir = shiftLatch.feed(left, right, nowMs)
            if (dir == LeverDir.LEFT || dir == LeverDir.RIGHT) {
                val dx = if (dir == LeverDir.LEFT) -1 else 1
                val next = Cell((player.pos.col + dx).coerceIn(0, Config.COLS - 1), player.pos.floor)
                if (getWindow(next) != WindowState.CLOSED) player.shift(dx)
            }
        } else {
            shiftLatch.reset()
        }
    }


    // プレイヤーの見た目ポーズを更新する処理の例
    fun updatePlayerPose(left: LeverDir, right: LeverDir) {
        // レバーが両方 CENTER の場合はポーズ変更なし
        if (left == LeverDir.CENTER && right == LeverDir.CENTER) {
            return // 何もせず終了 → 前回のポーズを保持
        }

        val newPose = when {
            left == LeverDir.UP   && right == LeverDir.UP   -> PlayerPose.BOTH_UP
            left == LeverDir.DOWN && right == LeverDir.DOWN -> PlayerPose.BOTH_DOWN
            left == LeverDir.UP   && right == LeverDir.DOWN -> PlayerPose.LUP_RDOWN
            left == LeverDir.DOWN && right == LeverDir.UP   -> PlayerPose.LDOWN_RUP
            else -> player.pose
        }
        player.pose = newPose
        // ★hands を pose に合わせて更新（ここが重要）
        player.hands = when (newPose) {
            PlayerPose.BOTH_UP   -> HandPair.BOTH_UP
            PlayerPose.BOTH_DOWN -> HandPair.BOTH_DOWN
            PlayerPose.LUP_RDOWN -> HandPair.L_UP_R_DOWN
            PlayerPose.LDOWN_RUP -> HandPair.L_DOWN_R_UP
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
