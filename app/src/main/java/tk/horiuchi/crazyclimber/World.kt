package tk.horiuchi.crazyclimber.core

import android.util.Log
import kotlin.math.max
import kotlin.random.Random

class World {
    // ===== Debug flags =====
    @Volatile var debugNoOjisan: Boolean = false
    @Volatile var debugWindowsNeverClose: Boolean = false
    @Volatile var debugNoShirake: Boolean = false
    @Volatile var debugShirakeLoop: Boolean = false
    @Volatile var debugNoKong: Boolean = false
    @Volatile var debugKongLoop: Boolean = false

    @Volatile var stageCleared: Boolean = false

    private val rnd = Random(System.nanoTime())
    val player = Player()

    // 5xFLOORS の窓状態と次遷移時刻
    private val windows = Array(Config.FLOORS) { Array(Config.COLS) { WindowState.OPEN } }
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

    // 横移動のクールタイム（repeat抑制）
    private val SHIFT_COOLDOWN_MS = 220L       // ← GameViewのshiftMsと同じに
    private var nextShiftOkMs = 0L             // 次に横移動を許可する時刻（ms）

    // ===== 窓アニメ（3段＋CLOSED/OPENING） =====
    enum class WindowAnim { OPEN, CLOSING1, CLOSING2, CLOSING3, CLOSED, OPENING3, OPENING2, OPENING1 }

    private val winAnim  = Array(Config.FLOORS) { Array(Config.COLS) { WindowAnim.OPEN } }
    private val nextTick = Array(Config.FLOORS) { Array(Config.COLS) { 0L } }

    // 可視範囲（GameViewから毎フレーム渡す）
    private var visTopFloor = 0
    private var visRows = 0

    // アニメ速度（閉/開とも ≒5秒）
    companion object {
        private const val WINDOW_STEPS = 3
        private const val CLOSE_TOTAL_MS = 5000L
        private const val OPEN_TOTAL_MS  = 5000L
        private const val CLOSED_HOLD_MS = 400L
        private const val SELECT_INTERVAL_MS = 1800L
        private const val FORCE_CLOSE_IDLE_MS = 10_000L
        private const val OPEN_MIN_HOLD_MS   = 1500L  // OPENを最低この時間は維持
        private const val CLOSED_MIN_HOLD_MS = 2000L  // CLOSEDを最低この時間は維持（★閉じは長め）
    }
    private val CLOSE_STEP_MS = CLOSE_TOTAL_MS / WINDOW_STEPS
    private val OPEN_STEP_MS  = OPEN_TOTAL_MS  / WINDOW_STEPS

    // 仕様：初期クローズ比率＆トグル比率
    private var initialClosedRatio = 0.10f    // 初期：可視20%をCLOSEDに
    //private var toggleRatio        = 0.20f    // 周期：可視20%をトグル（OPEN→閉始/ CLOSED→開始）

    // 同時にアニメ中でよい窓の上限（見えている総数に対する割合）
    private var maxActiveRatio = 0.06f      // ← 6% くらいから様子見（多いなら 0.04f など）
    // 1回の抽選で新規に動かす割合（小さめ）
    //private var toggleBatchRatio = 0.02f    // ← 2%/回。多いなら 0.01f
    // 新規に動かすうち「閉じ方向」に回す比率
    //private var desiredCloseShare = 0.30f   // ← 30% を閉じ、70% を開け
    // --- 窓の同時アクティブ上限＆抽選強度 ---
    private var closeStartBatchRatio  = 0.03f   // 1回の抽選で「OPEN→閉じ」を始める比率
    private var openStartBatchRatio   = 0.03f  // 1回の抽選で「CLOSED→開け」を始める比率（低め）

    // トグル抽選間隔
    private var nextSelectMs = 0L

    // “初めて見えた階”に初期20%クローズを配る
    private val seededFloor = BooleanArray(Config.FLOORS)

    // 10秒停止で強制クローズ
    private var lastPosChangeMs = 0L
    private var prevCellForIdle = player.pos.copy()

    // ===== Boss =====
    //private enum class BossState { IDLE, WINDUP, PUNCH, RECOVER, MOVE }
    private data class Boss(
        var active: Boolean = false,         // 出現中？
        var side: Int = -1,                  // -1: 左 / +1: 右
        var areaBottom: Int = 0,             // ボス領域の最下段 floor（含む）
        var width: Int = 3,                  // 3列（中央に届くため）
        var height: Int = 3,                 // 3階ぶん
        var startedAtMs: Long = 0L,          // 出現開始時刻
        var timeoutAtMs: Long = 0L,          // 強制落下タイムアウト
        var punching: Boolean = false,       // パンチ中フラグ
        var punchT: Float = 0f               // 0..1（簡易進行）
    )
    private var boss = Boss()

    //private fun bossLeftColFor(side: Int): Int =
    //    if (side < 0) CENTER_COL - (BOSS_W - 1) else CENTER_COL
    private var bossDone = false

    private val BOSS_TRIGGER_FLOOR = 40
    private val BOSS_FORCE_FALL_MS = 10_000L
    private val BOSS_BOTTOM_FROM_TRIGGER = 7     // 30F + 7 = 37F がボトム
    private val BOSS_H = 3                       // 3x4 の“4”
    private val BOSS_W = 3                       // 3x4 の“3”
    private val CENTER_COL = Config.COLS / 2           // 5列なら 2

    data class BossDraw(
        val active: Boolean,
        val side: Int,            // -1 左, +1 右
        val pose: Int,            // 0:通常, 1:パンチ
        val areaBottom: Int,      // 下端floor
        val areaHeight: Int       // 4
    )
    private var bossDraw: BossDraw? = null
    fun getBossDraw(): BossDraw? = bossDraw


    init {
        // 適当に初期化
        for (f in 0 until Config.FLOORS) {
            for (c in 0 until Config.COLS) {
                windows[f][c] = WindowState.OPEN
                nextTick[f][c] = (500L + rnd.nextLong(800))
            }
        }
    }

    //fun getWindow(cell: Cell): WindowState =
    //    windows[cell.floor.coerceIn(0, Config.FLOORS - 1)][cell.col.coerceIn(0, Config.COLS - 1)]

    private fun atTop(): Boolean = (player.pos.floor >= Config.FLOORS)
    //private fun atTop(): Boolean = false

    fun update(dtMs: Long) {
        currentTimeMs += dtMs

        //triggerBossIfNeeded()
        if (!debugNoKong) {
            if (debugKongLoop) {
                if (!boss.active && player.pos.floor >= 10) {
                    startBoss(player.pos.floor)
                }
            } else {
                if (!bossDone && !boss.active && player.pos.floor >= BOSS_TRIGGER_FLOOR) {
                    startBoss(player.pos.floor)
                }
            }
        }

        if (debugWindowsNeverClose) {
            forceOpenAllWindows()
        } else {
            // 窓まわり
            seedInitialClosedIfNeeded()  // 初めて見えた階に20%閉を配る
            handleForceCloseByIdle()
            stepWindowAnimAndTrigger()
            toggleSomeVisibleWindows()   // 可視20%をトグル抽選
        }

        updateShirake(dtMs)
        updateBoss(dtMs)
        updateOjisanAndPots(dtMs)
        // クリア条件（仮）：最上階に到達したら固定ボーナス
        if (!stageCleared && player.pos.floor >= Config.FLOORS - 1) {
            // クリア演出は後で。とりあえず何もしない
            stageCleared = true
        }
    }

    private fun forceOpenAllWindows() {
        for (f in 0 until Config.FLOORS)
            for (c in 0 until Config.COLS) {
                winAnim[f][c]  = WindowAnim.OPEN
                nextTick[f][c] = 0L
                // クールダウンを使っているなら
                //if (::nextEligible.isInitialized) nextEligible[f][c] = 0L
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

                if (p.kind == Pot.Kind.BIRD_DROP) {
                    itP.remove()
                    continue
                }
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
                if (player.hands == HandPair.BOTH_DOWN) {
                    // ★ 1段下げる（下限0でクランプ）
                    val newFloor = (player.pos.floor - 1).coerceAtLeast(0)
                    player.pos = player.pos.copy(floor = newFloor)

                    // ★ 強制UNSTABLE（ランダムで ＞ / ＜ を付与）
                    val imposed = if (rnd.nextBoolean()) {
                        player.hands = HandPair.L_UP_R_DOWN
                        player.pose  = PlayerPose.LUP_RDOWN
                        UnstablePattern.LUP_RDOWN
                    } else {
                        player.hands = HandPair.L_DOWN_R_UP
                        player.pose  = PlayerPose.LDOWN_RUP
                        UnstablePattern.LDOWN_RUP
                    }
                    lastUnstable = imposed
                } else {
                    // （BOTH_UP など）従来通り：高さは据え置きでUNSTABLE化
                    val imposed = if (rnd.nextBoolean()) {
                        player.hands = HandPair.L_UP_R_DOWN
                        player.pose  = PlayerPose.LUP_RDOWN
                        UnstablePattern.LUP_RDOWN
                    } else {
                        player.hands = HandPair.L_DOWN_R_UP
                        player.pose  = PlayerPose.LDOWN_RUP
                        UnstablePattern.LDOWN_RUP
                    }
                    lastUnstable = imposed
                }
            }
            Stability.UNSTABLE -> {
                notifyHit()
                lastUnstable = null
            }
        }
    }

    /** 上方の OPEN 窓から1体スポーン */
    private fun trySpawnOjisan() {
        if (debugNoOjisan) return
        if (shirake.active) return
        if (boss.active) return

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
            val cell = Cell(c, f)
            if (!isTransition(cell) && !isClosed(cell)) {
                ojisans += Ojisan(col = c, floor = f)
                return
            }
        }
    }

    fun setVisibleRange(topFloor: Int, rows: Int) {
        visTopFloor = topFloor.coerceIn(0, Config.FLOORS - 1)
        visRows     = rows.coerceIn(0, Config.FLOORS)

        if (!visReady && visRows > 0) {
            visReady = true
        }
    }

    // steps=3 のとき、OPEN=0、CLOSING1=0.25、CLOSING2=0.5、CLOSING3=0.75、CLOSED=1.0
    fun getWindowCloseProgress(cell: Cell): Float {
        val f = cell.floor.coerceIn(0, Config.FLOORS - 1)
        val c = cell.col.coerceIn(0, Config.COLS - 1)
        val k = WINDOW_STEPS + 1f
        return when (winAnim[f][c]) {
            WindowAnim.OPEN       -> 0f
            WindowAnim.CLOSING1   -> 1f / k
            WindowAnim.CLOSING2   -> 2f / k
            WindowAnim.CLOSING3   -> 3f / k
            WindowAnim.CLOSED     -> 1f
            WindowAnim.OPENING3   -> 3f / k
            WindowAnim.OPENING2   -> 2f / k
            WindowAnim.OPENING1   -> 1f / k
        }
    }

    private fun isTransition(cell: Cell): Boolean {
        val f = cell.floor.coerceIn(0, Config.FLOORS - 1)
        val c = cell.col.coerceIn(0, Config.COLS - 1)
        return when (winAnim[f][c]) {
            WindowAnim.CLOSING1, WindowAnim.CLOSING2, WindowAnim.CLOSING3,
            WindowAnim.OPENING1, WindowAnim.OPENING2, WindowAnim.OPENING3 -> true
            else -> false
        }
    }

    private fun seedInitialClosedIfNeeded() {
        if (visRows <= 0) return
        val start = visTopFloor
        val end   = (visTopFloor + visRows).coerceAtMost(Config.FLOORS)
        for (f in start until end) {
            if (seededFloor[f]) continue
            seededFloor[f] = true

            for (c in 0 until Config.COLS) {
                if (boss.active && inBossRows(f) && c == CENTER_COL) continue
                val cell = Cell(c, f)
                if (isInSafeZone(cell)) continue
                if (rnd.nextFloat() < initialClosedRatio) {
                    winAnim[f][c]  = WindowAnim.CLOSED
                    nextTick[f][c] = 0L
                    nextEligible[f][c] = currentTimeMs + CLOSED_MIN_HOLD_MS
                }
            }
        }
    }

    // 同じ窓を連発で選ばないためのクールダウン
    private val nextEligible = Array(Config.FLOORS) { Array(Config.COLS) { 0L } }
    private fun toggleSomeVisibleWindows() {
        if (visRows <= 0 || currentTimeMs < nextSelectMs) return
        nextSelectMs = currentTimeMs + SELECT_INTERVAL_MS

        val start = visTopFloor
        val end   = (visTopFloor + visRows).coerceAtMost(Config.FLOORS)
        val cols  = Config.COLS

        var activeNow = 0
        val opens   = ArrayList<Cell>()   // 安定OPEN（抽選可）
        val closeds = ArrayList<Cell>()   // 安定CLOSED（抽選可）

        for (f in start until end) for (c in 0 until cols) {
            if (boss.active && inBossRows(f) && isVisibleFloor(f) && c == CENTER_COL) continue
            when (winAnim[f][c]) {
                WindowAnim.OPEN   -> if (currentTimeMs >= nextEligible[f][c]) opens   += Cell(c, f)
                WindowAnim.CLOSED -> if (currentTimeMs >= nextEligible[f][c]) closeds += Cell(c, f)
                WindowAnim.CLOSING1, WindowAnim.CLOSING2, WindowAnim.CLOSING3,
                WindowAnim.OPENING1, WindowAnim.OPENING2, WindowAnim.OPENING3 -> activeNow++
            }
        }

        val totalVisible = (end - start) * cols
        val maxActive    = kotlin.math.max(1, kotlin.math.floor(totalVisible * maxActiveRatio).toInt())
        var headroom     = (maxActive - activeNow).coerceAtLeast(0)
        if (headroom <= 0) return

        // バッチ上限
        val batchCloseMax = kotlin.math.max(1, kotlin.math.floor(totalVisible * closeStartBatchRatio).toInt())
        val batchOpenMax  = kotlin.math.max(1, kotlin.math.floor(totalVisible * openStartBatchRatio ).toInt())

        // ★ 閉じが多い時は“開け”を優先配分
        val stableTotal  = opens.size + closeds.size
        val closedRatio  = if (stableTotal > 0) closeds.size.toFloat() / stableTotal else 0f
        val preferOpen   = closedRatio >= 0.30f

        var toOpen  = 0
        var toClose = 0

        if (preferOpen) {
            // まず「開け」に枠を割り当て、残りを「閉じ」に
            toOpen  = kotlin.math.min(kotlin.math.min(closeds.size, batchOpenMax),  headroom)
            toClose = kotlin.math.min(kotlin.math.min(opens.size,   batchCloseMax), headroom - toOpen)
        } else {
            // これまで通り閉じ優先
            toClose = kotlin.math.min(kotlin.math.min(opens.size,   batchCloseMax), headroom)
            toOpen  = kotlin.math.min(kotlin.math.min(closeds.size, batchOpenMax),  headroom - toClose)
        }

        // ★ 候補があるなら「開け」を最低1枚は確保（枠を盗む）
        if (toOpen == 0 && closeds.isNotEmpty() && headroom > 0) {
            if (toClose > 0) { toClose -= 1; toOpen = 1 } else { toOpen = 1 }
        }
        if (toOpen <= 0 && toClose <= 0) return

        opens.shuffle(rnd); closeds.shuffle(rnd)

        repeat(toClose) {
            val cell = opens[it]; val f = cell.floor; val c = cell.col
            ojisans.removeAll { o -> o.floor == f && o.col == c }
            winAnim[f][c]  = WindowAnim.CLOSING1
            nextTick[f][c] = currentTimeMs + CLOSE_STEP_MS
            nextEligible[f][c] = currentTimeMs + OPEN_MIN_HOLD_MS
        }
        repeat(toOpen) {
            val cell = closeds[it]; val f = cell.floor; val c = cell.col
            ojisans.removeAll { o -> o.floor == f && o.col == c }
            winAnim[f][c]  = WindowAnim.OPENING3
            nextTick[f][c] = currentTimeMs + OPEN_STEP_MS
            nextEligible[f][c] = currentTimeMs + CLOSED_MIN_HOLD_MS / 2
        }
    }

    // 両手上 or 片手上 なら「上の窓」を対象にする
    private fun usesUpperGrip(): Boolean = when (player.hands) {
        HandPair.BOTH_UP, HandPair.L_UP_R_DOWN, HandPair.L_DOWN_R_UP -> true
        HandPair.BOTH_DOWN -> false
    }

    // ★追加：次の「一段登る」ために新しく掴む必要がある窓
    private fun reachCellForNextStep(): Cell {
        val needFloor =
            if (usesUpperGrip()) player.pos.floor + 2   // 両手上/片手上ならさらに+2を掴みに行く
            else                  player.pos.floor + 1   // 両手下なら+1を掴む
        return Cell(player.pos.col, needFloor.coerceAtMost(Config.FLOORS - 1))
    }

    // 追加：横移動のときに衝突判定へ使う窓
    private fun destCellForHorizontal(dx: Int): Cell {
        val col = (player.pos.col + dx).coerceIn(0, Config.COLS - 1)
        val f   =
            if (usesUpperGrip()) (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1) // 上を握って横移動
            else                  player.pos.floor                                       // 両手下は同じ階
        return Cell(col, f)
    }

    private fun targetCellForFall(): Cell =
        if (usesUpperGrip())
            Cell(player.pos.col, (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1))
        else
            player.pos

    private fun stepWindowAnimAndTrigger() {
        for (f in 0 until Config.FLOORS) for (c in 0 until Config.COLS) {
            if (currentTimeMs < nextTick[f][c]) continue
            when (winAnim[f][c]) {
                WindowAnim.OPEN -> Unit

                WindowAnim.CLOSING1 -> { winAnim[f][c] = WindowAnim.CLOSING2; nextTick[f][c] = currentTimeMs + CLOSE_STEP_MS }
                WindowAnim.CLOSING2 -> { winAnim[f][c] = WindowAnim.CLOSING3; nextTick[f][c] = currentTimeMs + CLOSE_STEP_MS }
                WindowAnim.CLOSING3 -> {
                    winAnim[f][c]  = WindowAnim.CLOSED
                    nextTick[f][c] = 0L
                    // ★ CLOSED 到達時に最低保持をセット
                    nextEligible[f][c] = (currentTimeMs + CLOSED_MIN_HOLD_MS)

                    // 閉じ切った瞬間に対象セルなら即ヒット
                    val tgt = targetCellForFall()
                    if (tgt.floor == f && tgt.col == c) notifyHit()
                }

                // ★ ここを“自動で開けない”仕様に変更
                WindowAnim.CLOSED -> {
                    // 何もしない（OPENING*は抽選でのみ開始）
                }

                WindowAnim.OPENING3 -> { winAnim[f][c] = WindowAnim.OPENING2; nextTick[f][c] = currentTimeMs + OPEN_STEP_MS }
                WindowAnim.OPENING2 -> { winAnim[f][c] = WindowAnim.OPENING1; nextTick[f][c] = currentTimeMs + OPEN_STEP_MS }
                WindowAnim.OPENING1 -> {
                    winAnim[f][c]  = WindowAnim.OPEN
                    nextTick[f][c] = 0L
                    // ★ OPEN 到達時も最低保持
                    nextEligible[f][c] = (currentTimeMs + OPEN_MIN_HOLD_MS)
                }
            }
        }
    }

    // === 安全地帯（窓を強制OPENする時間＆範囲） ===
    private var windowSafeUntilMs = 0L
    private var safeAnchor = Cell(0, 0)
    private var safeVR = 0      // 縦方向の範囲（anchor.floor 〜 anchor.floor + safeVR）
    private var safeHR = 0      // 横方向の範囲（anchor.col - safeHR 〜 + safeHR）

    private fun isInSafeZone(cell: Cell): Boolean {
        if (currentTimeMs >= windowSafeUntilMs) return false
        val f0 = safeAnchor.floor
        val c0 = safeAnchor.col
        val f = cell.floor
        val c = cell.col
        return (f in f0..(f0 + safeVR)) && (kotlin.math.abs(c - c0) <= safeHR)
    }

    /** セーフゾーンを設定＆対象窓を強制OPEN＆再抽選を抑止 */
    fun grantStartSafety(durationMs: Long = 2500L, horizRange: Int = 1, vertRange: Int = 2) {
        safeAnchor = player.pos.copy()
        safeHR = horizRange
        safeVR = vertRange
        windowSafeUntilMs = currentTimeMs + durationMs

        val fStart = safeAnchor.floor
        val fEnd   = (safeAnchor.floor + safeVR).coerceAtMost(Config.FLOORS - 1)
        val cStart = (safeAnchor.col - safeHR).coerceAtLeast(0)
        val cEnd   = (safeAnchor.col + safeHR).coerceAtMost(Config.COLS - 1)

        for (f in fStart..fEnd) for (c in cStart..cEnd) {
            winAnim[f][c]  = WindowAnim.OPEN
            nextTick[f][c] = 0L
            nextEligible[f][c] = windowSafeUntilMs   // この時間までは抽選対象にしない
            // おじさんもその窓だけ排他
            ojisans.removeAll { o -> o.floor == f && o.col == c }
        }
    }

    private fun handleForceCloseByIdle() {
        // 最上階、ボス出現中は無効
        if (atTop() || boss.active) {
            // ついでにタイマをリセットしておくと、終了直後の誤発火も防げる
            lastPosChangeMs = currentTimeMs
            return
        }
        // 位置が変わったら記録
        if (player.pos != prevCellForIdle) {
            prevCellForIdle = player.pos.copy()
            lastPosChangeMs = currentTimeMs
        }
        if (currentTimeMs - lastPosChangeMs < FORCE_CLOSE_IDLE_MS) return

        // 対象窓：両下なら現在、片手/両上なら一つ上
        val tgt = targetCellForFall()   // ★ここも統一
        val f = tgt.floor; val c = tgt.col

        // いきなり CLOSED にせず、「今の状態から」閉方向へスイッチ
        when (winAnim[f][c]) {
            // 安定OPEN／開きかけ → 閉じ始め（進捗に合わせた段へ）
            WindowAnim.OPEN,
            WindowAnim.OPENING1 -> {
                winAnim[f][c]  = WindowAnim.CLOSING1
                nextTick[f][c] = currentTimeMs + CLOSE_STEP_MS
            }
            WindowAnim.OPENING2 -> {
                winAnim[f][c]  = WindowAnim.CLOSING2
                nextTick[f][c] = currentTimeMs + CLOSE_STEP_MS
            }
            WindowAnim.OPENING3 -> {
                winAnim[f][c]  = WindowAnim.CLOSING3
                nextTick[f][c] = currentTimeMs + CLOSE_STEP_MS
            }

            // すでに閉方向に動いている／閉じているならそのまま
            WindowAnim.CLOSING1,
            WindowAnim.CLOSING2,
            WindowAnim.CLOSING3 -> {
                // 何もしない（既に閉じに向かっている）
            }
            WindowAnim.CLOSED -> {
                // もう閉じ切っているなら、ここで即落下させても良い
                // （“閉じる瞬間のみ落下”ポリシーなら、この行は外してOK）
                notifyHit()
            }
        }

        // 連続発火を防ぐ（次の移動まで余裕を持たせる）
        lastPosChangeMs = currentTimeMs + FORCE_CLOSE_IDLE_MS
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
            //val upCell = Cell(player.pos.col, (player.pos.floor + 1).coerceAtMost(Config.FLOORS - 1))
            val next = reachCellForNextStep()
            if (!isClosed(next)) {
                // 見た目と内部状態だけ不安定へ。lastUnstableもセットしておくと次の逆斜めで登れる
                if (curDiag == UnstablePattern.LUP_RDOWN) {
                    player.pose = PlayerPose.LUP_RDOWN
                    player.hands = HandPair.L_UP_R_DOWN
                } else {
                    player.pose = PlayerPose.LDOWN_RUP
                    player.hands = HandPair.L_DOWN_R_UP
                }
                lastUnstable = curDiag
            }
            // 横移動は不安定なので不可、処理終了
            return
        }
        // === 先に「仕様の2項」を強制適用 ===
        // (A) UNSTABLE → 両手下（BOTH_DOWN）で +1階（着地＝BOTH_DOWN）
        if (wasUnstable && inputBothDown) {
            if (!atTop()) player.stepClimbAccepted()
            applyPose(PlayerPose.BOTH_DOWN)
            lastUnstable = null   // シーケンス完了
            return
        }

        // (A2) STABLE 両手上（BOTH_UP） → 両手下（BOTH_DOWN）で +1階
        if (wasStable && player.hands == HandPair.BOTH_UP && inputBothDown) {
            if (!atTop()) player.stepClimbAccepted()
            applyPose(PlayerPose.BOTH_DOWN)
            return
        }

        // (B) BOTH_DOWN から BOTH_UP への直接遷移を禁止（見た目もロジックも維持）
        if (player.hands == HandPair.BOTH_DOWN && inputBothUp) {
            // ここではポーズを変えず、横移動だけ判定可能にする
            val dir = shiftLatch.feed(left, right, nowMs)
            if (dir == LeverDir.LEFT || dir == LeverDir.RIGHT) {
                tryShiftDir(dir)
            }
            return
        }

        // === 通常の上昇ロジック ===

        // 1) STABLE → 斜め（＞/＜）で必ず +1階、着地＝その斜めのUNSTABLE
        if (wasStable && curDiag != null) {
            val poseAfter = if (curDiag == UnstablePattern.LUP_RDOWN)
                PlayerPose.LUP_RDOWN else PlayerPose.LDOWN_RUP
            val next = reachCellForNextStep()
            if (!isClosed(next)) {
                if (!atTop()) player.stepClimbAccepted()
                applyPose(poseAfter)
                lastUnstable = curDiag
            }
            return
        }

        // 2) UNSTABLE → 反対斜めに切替で +1階、着地＝その斜めのUNSTABLE
        if (wasUnstable && curDiag != null && curDiag != lastUnstable) {
            val poseAfter = if (curDiag == UnstablePattern.LUP_RDOWN)
                PlayerPose.LUP_RDOWN else PlayerPose.LDOWN_RUP
            val next = reachCellForNextStep()
            if (!isClosed(next)) {
                if (!atTop()) player.stepClimbAccepted()
                applyPose(poseAfter)
                lastUnstable = curDiag
            }
            return
        }

        // === 見た目ポーズの更新（CENTERや無関係入力では維持） ===
        // ここでは禁止事項（BOTH_DOWN→BOTH_UP）を再度尊重
        val next = reachCellForNextStep()
        val canReachUp = !isClosed(next)
        //val atTopFloor = (player.pos.floor >= Config.FLOORS)
        val forbidBothUpNow = atTop() && (player.hands == HandPair.L_UP_R_DOWN || player.hands == HandPair.L_DOWN_R_UP) && (left == LeverDir.UP && right == LeverDir.UP)
        when {
            inputBothDown -> applyPose(PlayerPose.BOTH_DOWN)
            inputBothUp   -> {
                // ★UNSTABLEかつ最上段では両手上げを無効化
                if (!forbidBothUpNow && player.hands != HandPair.BOTH_DOWN) {
                    applyPose(PlayerPose.BOTH_UP)
                }
                //if (player.hands != HandPair.BOTH_DOWN) applyPose(PlayerPose.BOTH_UP)
                // 両下→両上は通さない
            }
            curDiag == UnstablePattern.LUP_RDOWN -> if (canReachUp) applyPose(PlayerPose.LUP_RDOWN)
            curDiag == UnstablePattern.LDOWN_RUP -> if (canReachUp) applyPose(PlayerPose.LDOWN_RUP)
            else -> { /* CENTERや左右はポーズ維持 */ }
        }

        // === 横移動（安定のみ） ===
        if (player.hands == HandPair.BOTH_UP || player.hands == HandPair.BOTH_DOWN) {
            val dir = shiftLatch.feed(left, right, nowMs)
            if (dir == LeverDir.LEFT || dir == LeverDir.RIGHT) {
                tryShiftDir(dir)
            }
        } else {
            shiftLatch.reset()
        }
    }

    private fun tryShiftDir(dir: LeverDir) {
        if (currentTimeMs < nextShiftOkMs) {
            //Log.d("tryShiftDir", "${currentTimeMs}, ${nextShiftOkMs}")
            return
        }  // クールタイム中は無視

        val dx = if (dir == LeverDir.LEFT) -1 else +1
        //val next = Cell((player.pos.col + dx).coerceIn(0, Config.COLS - 1), player.pos.floor)
        val next = destCellForHorizontal(dx)
        if (!isClosed(next)) {
            player.shift(dx)
            nextShiftOkMs = currentTimeMs + SHIFT_COOLDOWN_MS  // ★次回許可時刻を更新
        }
    }

    private var hitFlag = false
    fun notifyHit() { hitFlag = true } // 既存の当たり判定で呼ぶ
    fun consumeHitFlag(): Boolean {
        val v = hitFlag
        hitFlag = false
        return v
    }

    fun clearObjectsForFall() {
        pots.clear()
        ojisans.clear()
        endShirakeNow()
        endBoss()
    }

    fun reset() {
        // プレイヤー初期化
        player.pos = Cell(Config.COLS / 2, 1) // 中央列、1階
        player.lives = 3
        player.score = 0
        player.pose = PlayerPose.BOTH_UP
        player.hands = HandPair.BOTH_UP

        for (f in 0 until Config.FLOORS) for (c in 0 until Config.COLS) {
            winAnim[f][c] = WindowAnim.OPEN
            nextTick[f][c] = 0L
        }
        seededFloor.fill(false)
        nextSelectMs = 0L
        lastPosChangeMs = currentTimeMs
        prevCellForIdle = player.pos.copy()

        // 内部状態
        lastUnstable = null
        lastPoseUnstable = UnstablePattern.NONE

        // 障害物・敵をリセット
        pots.clear()
        ojisans.clear()
        visReady = false
        shirakePending = false
        shirakeDone = false
        shirake = Shirake()
        shirakeDraw = null
        hasShirakeDraw = false

        boss = Boss()
        bossDone = false
        bossDraw = null

        // フラグ・タイマーのリセット
        hitFlag = false

        //currentTimeMs = 0L ←これはリセットしちゃダメ！
        nextShiftOkMs      = currentTimeMs
        nextOjisanCheckMs  = currentTimeMs
        ojisanCooldownMs   = 0L
        nextSelectMs       = currentTimeMs
        lastPosChangeMs    = currentTimeMs
        windowSafeUntilMs  = 0L
        safeUntilMs = 3000L
    }


    // ===== しらけどり =====
    private data class Shirake(
        var active: Boolean = false,
        var dir: Int = +1,             // +1: L→R, -1: R→L
        var t: Float = 0f,             // 0..1 の進行
        var speed: Float = 0.23f,      // 1画面横断/秒 (0.23 → 約4.3秒で横断)
        var ampFloors: Float = 0.6f,   // 上下振幅（“階”単位）
        var phase: Float = 0f,         // ふらふら位相
        var drop1T: Float = 0.25f,     // フンのタイミング（0..1）
        var drop2T: Float = 0.75f,
        var dropped1: Boolean = false,
        var dropped2: Boolean = false,
        var startPlayerFloor: Int = 0, // 10F上昇の終了判定用
        var baseFloor: Float = 0f      // 画面上部の基準Y（“階”）
    )
    private var shirake = Shirake()
    private var shirakeDone = false     // 1回限りにする用

    // 置き換え：data class → 再利用する可変クラスに
    class ShirakeDraw(var xFrac: Float = 0f, var yFloor: Float = 0f, var dir: Int = +1, var flap: Int = 0)
    @Volatile private var shirakeDraw: ShirakeDraw? = null
    private var hasShirakeDraw = false
    fun getShirakeDraw(): ShirakeDraw? = shirakeDraw

    // しらけどりのトリガと終了条件
    private val SHIRAKE_TRIGGER_FLOOR = 20     // 到達で出現
    private val SHIRAKE_ASCEND_TO_END = 10     // その後10F上昇で終了

    // 可視範囲が一度でも設定されたか
    @Volatile private var visReady = false
    // 可視範囲未確定の間に出現条件を満たしたら保留
    @Volatile private var shirakePending = false

    // World.kt に追加
    private fun topRowFloorSafe(): Float {
        val vr = if (visRows > 0) visRows else 12  // フォールバック
        return visTopFloor + vr - 1f
    }

    private fun startShirake() {
        //val phase = rnd.nextFloat() * (Math.PI * 2).toFloat()
        val phase = rnd.nextFloat() * Math.PI.toFloat()
        // 0.2〜0.8 の範囲で2回、近すぎたら少し離す
        var d1 = rnd.nextFloat() * 0.6f + 0.2f
        var d2 = rnd.nextFloat() * 0.6f + 0.2f
        if (kotlin.math.abs(d1 - d2) < 0.2f) {
            if (d1 < d2) d2 = (d1 + 0.25f).coerceAtMost(0.95f) else d1 = (d2 + 0.25f).coerceAtMost(0.95f)
        }

        val amp = 0.8f
        val topRowFloor = topRowFloorSafe()
        val screenTop   = topRowFloor - 1f    // オーバースキャンぶん補正（実画面の上端付近）
        val topMargin   = 0.15f               // 画面上端からの余白（お好みで 0.1〜0.3）
        val ceiling     = screenTop - topMargin  // ← 鳥が上がれる“最大 yFloor”
        shirake = Shirake(
            active = true,
            dir = +1,
            t = 0f,
            speed = 0.13f,
            ampFloors = amp,
            phase = phase,
            drop1T = d1,
            drop2T = d2,
            dropped1 = false,
            dropped2 = false,
            startPlayerFloor = player.pos.floor,
            baseFloor = ceiling - amp * 0.5f
        )

        // ★初期スナップショットを作っておく
        val initY = (shirake.baseFloor + shirake.ampFloors * kotlin.math.sin(shirake.phase))
            .coerceAtMost(ceiling)
        shirakeDraw = ShirakeDraw(
            xFrac = if (shirake.dir > 0) 0f else 1f,
            yFloor = initY,
            dir = shirake.dir,
            flap = 0
        )
    }

    private fun resetShirakePassForReverse() {
        shirake.dir *= -1
        shirake.t = 0f
        shirake.dropped1 = false
        shirake.dropped2 = false
        shirake.phase = rnd.nextFloat() * (Math.PI * 2).toFloat()
    }

    private fun spawnBirdDrop(xFrac: Float, yFloor: Float) {
        val col = (xFrac * Config.COLS).toInt().coerceIn(0, Config.COLS - 1)
        val startFloor = (yFloor - 0.9f).coerceAtLeast(0f)
        pots += Pot(
            col = col,
            //yFloor = yFloor + 0.4f,
            yFloor = startFloor,
            speedFloorsPerSec = 4.2f,        // 好みで調整（鉢より少し速め）
            bounced = false,                  // 最初から跳ねない
            kind = Pot.Kind.BIRD_DROP         // ★フンとして生成
        )
    }

    private fun updateShirake(dtMs: Long) {
        if (debugNoShirake) return
        // 出現判定
        if (debugShirakeLoop) {
            if (!shirake.active && !shirakeDone && player.pos.floor % 10 == 0) {
                if (visReady) {
                    startShirake()
                } else {
                    shirakePending = true   // 可視行数が入るまで保留
                }
            } else {
                if (player.pos.floor % 10 != 0) {
                    shirakeDone = false
                    hasShirakeDraw = false
                }
            }
        } else {
            if (!shirake.active && !shirakeDone && player.pos.floor >= SHIRAKE_TRIGGER_FLOOR) {
                //startShirake()
                if (visReady) {
                    startShirake()
                } else {
                    shirakePending = true   // 可視行数が入るまで保留
                }
            }
        }
        // ★ 可視行数が確定した“次の update”で起動
        if (shirakePending && visReady && !shirake.active && !shirakeDone) {
            startShirake()
            shirakePending = false
        }
        if (!shirake.active) { shirakeDraw = null; return }

        // ★保険：nullならここで用意
        val draw = shirakeDraw ?: ShirakeDraw().also { shirakeDraw = it }

        // 画面上部追従。上下揺れがはみ出さないようにオフセット
        val topRowFloor = topRowFloorSafe()
        val screenTop   = topRowFloor - 1f    // オーバースキャンぶん補正（実画面の上端付近）
        val topMargin   = 0.15f               // 画面上端からの余白（お好みで 0.1〜0.3）
        val ceiling     = screenTop - topMargin  // ← 鳥が上がれる“最大 yFloor”
        shirake.baseFloor = ceiling - shirake.ampFloors * 0.5f

        val dt = dtMs / 1000f
        shirake.t += shirake.speed * dt
        val progress = shirake.t.coerceAtMost(1f)
        val xFrac = if (shirake.dir > 0) progress else 1f - progress

        val wobble = kotlin.math.sin((2f * Math.PI * (progress * 1.5f)).toFloat() + shirake.phase)
        val yFloorRaw = shirake.baseFloor + shirake.ampFloors * wobble
        val yFloor    = yFloorRaw.coerceAtMost(ceiling)

        // フン2回
        if (!shirake.dropped1 && progress >= shirake.drop1T) { spawnBirdDrop(xFrac, yFloor); shirake.dropped1 = true }
        if (!shirake.dropped2 && progress >= shirake.drop2T) { spawnBirdDrop(xFrac, yFloor); shirake.dropped2 = true }

        if (debugShirakeLoop) {
            if (player.pos.floor >= shirake.startPlayerFloor + 5) {
                shirake.active = false
                shirakeDone = true
                shirakeDraw = null
                hasShirakeDraw = false
                return
            }
        } else {
            // 10F上昇したら即終了（上に消える仕様は簡易で即消し）
            if (player.pos.floor >= shirake.startPlayerFloor + SHIRAKE_ASCEND_TO_END) {
                shirake.active = false
                shirakeDone = true
                shirakeDraw = null
                hasShirakeDraw = false
                return
            }
        }

        // 端まで到達で折返し or 終了
        if (shirake.t >= 1f) {
            if (shirake.dir > 0) {
                resetShirakePassForReverse()   // R→L へ
            } else {
                shirake.active = false         // 往復完了
                shirakeDone = true
                shirakeDraw = null
                hasShirakeDraw = false
                return
            }
        }

        // 描画用スナップショット
        val flap = ((currentTimeMs / 480L) % 2).toInt()

        // ★draw に書き込む（null安全演算子は使わない）
        draw.xFrac = xFrac
        draw.yFloor = yFloor
        draw.dir = shirake.dir
        draw.flap = flap

    }

    // World.kt
    fun endShirakeNow() {
        if (!shirake.active) return
        shirake.active = false
        shirakeDone = true
        shirakeDraw = null
        hasShirakeDraw = false
    }


    private fun startBoss(triggerFloor: Int) {
        if (boss.active) return
        boss = Boss().apply {
            active       = true
            side         = -1                          // まず左から
            areaBottom   = triggerFloor + BOSS_BOTTOM_FROM_TRIGGER
            width        = BOSS_W
            height       = BOSS_H
            startedAtMs  = currentTimeMs
            timeoutAtMs  = currentTimeMs + BOSS_FORCE_FALL_MS
            punching     = false
            punchT       = 0f
        }

        // 中央列は通路。ボス領域の間は“開いたまま＆抽選対象外”に固定
        for (f in boss.areaBottom .. bossTopFloor()) {
            winAnim[f][CENTER_COL] = WindowAnim.OPEN
            nextTick[f][CENTER_COL] = 0L
            nextEligible[f][CENTER_COL] = Long.MAX_VALUE
        }
        // ★開始時に強制閉めタイマをリセット（保険）
        lastPosChangeMs = currentTimeMs
    }

    private fun endBoss() {
        if (!boss.active) return
        // 中央列の固定を解除
        for (f in boss.areaBottom .. bossTopFloor()) {
            if (nextEligible[f][CENTER_COL] == Long.MAX_VALUE/4) {
                nextEligible[f][CENTER_COL] = currentTimeMs
            }
        }
        boss.active = false
        bossDone = true
        // ★終了直後にリセットして、すぐには発火しないように
        lastPosChangeMs = currentTimeMs
    }

    private fun isClosed(cell: Cell): Boolean {
        //if (boss.active && inBossRows(cell.floor)) {
        //    return cell.col != CENTER_COL
        //}
        if (boss.active) {
            val f = cell.floor
            //Log.d("bossTest", "inBossRows(${f})=${inBossRows(f)}  top=${bossTopFloor()} bottom=${boss.areaBottom}")
            if (inBossRows(f) && isVisibleFloor(f)) {
                // ボス帯に露出している行だけサイドを閉じる。中央は常に開。
                if (cell.col == CENTER_COL) return false
                return true
            }
        }
        return getWindowCloseProgress(cell) >= 1f
    }

    private fun updateBoss(dtMs: Long) {
        if (!boss.active) return

        // タイムアウトで強制落下
        if (currentTimeMs >= boss.timeoutAtMs) {
            notifyHit()              // ← GameView 側が落下シーケンスへ
            endBoss()
            return
        }

        // 簡易パンチ：一定周期でパンチ → 終了で反対側へ移動
        // （好みで調整）
        val PUNCH_PERIOD_MS = 2200L
        val PUNCH_TIME_MS   = 450L
        val phase = ((currentTimeMs - boss.startedAtMs) % PUNCH_PERIOD_MS).toInt()

        if (phase < PUNCH_TIME_MS) {
            boss.punching = true
            boss.punchT = phase / PUNCH_TIME_MS.toFloat()

            // ★中央列かつ “ボスのボトム+1階” のみヒット
            val hitFloor = boss.areaBottom + 1
            if (player.pos.col == CENTER_COL && player.pos.floor == hitFloor) {
                notifyHit()
                endBoss()
                return
            }
        } else {
            // パンチ終わりフレームでサイド切替
            if (boss.punching) {
                boss.punching = false
                boss.punchT = 0f
                boss.side *= -1
            }
        }

        // シーン終了：プレイヤーが領域を抜けたら（最上段を超えたら）終了
        val passedTop = bossTopFloor() + 1
        if (player.pos.floor > passedTop) {
            endBoss()
        }

        bossDraw = BossDraw(
            active      = true,
            side        = boss.side,
            pose        = if (boss.punching) 1 else 0, // 0:通常,1:パンチ
            areaBottom  = boss.areaBottom,
            areaHeight  = boss.height
        )
    }

    private fun bossTopFloor(): Int = boss.areaBottom + (boss.height - 1)
    private fun inBossRows(floor: Int): Boolean =
        boss.active && floor in boss.areaBottom .. bossTopFloor()

    private fun isVisibleFloor(floor: Int): Boolean {
        val vStart = visTopFloor
        val vEnd   = (visTopFloor + visRows - 1).coerceAtLeast(vStart)
        return floor in vStart..vEnd
    }

    // camTop/rows から “今 何段まで露出してよいか（0..3）” を返す
    private fun bossRevealRows(camTop: Int, rows: Int): Int {
        if (!boss.active) return 0
        val topVisible = camTop + rows - 1
        return (topVisible - boss.areaBottom + 1) // ← bottom 基準で差分+1
            .coerceIn(0, boss.height)
    }

    data class BossView(
        val bottom: Int, val height: Int,
        val leftCol: Int, val width: Int,
        val side: Int, val reveal: Int
    )
    fun getBossView(camTop: Int, rows: Int): BossView? {
        if (!boss.active) return null
        return BossView(
            bottom = boss.areaBottom,
            height = boss.height,
            leftCol = bossLeftCol(),
            width = BOSS_W,
            side = boss.side,
            reveal = bossRevealRows(camTop, rows)
        )
    }


    private fun bossLeftCol(): Int =
        if (boss.side < 0) 0 else Config.COLS - boss.width

    private fun bossRightCol(): Int =
        bossLeftCol() + boss.width - 1

    private fun bossCols(): IntRange =
        bossLeftCol()..bossRightCol()

}
