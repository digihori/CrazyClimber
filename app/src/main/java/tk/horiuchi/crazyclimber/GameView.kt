package tk.horiuchi.crazyclimber.ui.view

import android.content.Context
import android.graphics.*
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import tk.horiuchi.crazyclimber.audio.SoundManager
import tk.horiuchi.crazyclimber.core.*
import tk.horiuchi.crazyclimber.ui.assets.Assets

class GameView(context: Context, attrs: AttributeSet?) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {

    // ====== 外部ロジック ======
    private val world = World()

    // ====== ループ制御 ======
    private var running = false
    private var loopThread: Thread? = null

    // HUD更新（SCORE / LIVES / FLOOR）
    var onHudUpdate: ((score: Int, lives: Int, floor: Int) -> Unit)? = null

    // ====== 入力（Activity から渡す） ======
    @Volatile private var lastLeft: LeverDir = LeverDir.CENTER
    @Volatile private var lastRight: LeverDir = LeverDir.CENTER
    fun setLeverInput(left: LeverDir, right: LeverDir) {
        lastLeft = left
        lastRight = right
    }

    // ====== 進行フェーズ ======
    private enum class GamePhase { PLAYING, FALL_SEQUENCE, RESPAWN, GAME_OVER, CLEAR_SEQUENCE }
    private var phase = GamePhase.PLAYING
    private var currentStage = 1

    // ====== カメラ（整数の最上段階） ======
    private var camTopFloor: Int = 0  // 描画上の“床”基準

    // ====== 横アニメ（プレイヤーのみX補間） ======
    private var shiftFromCol = 0
    private var shiftToCol   = 0
    private var shiftT       = 1f      // 0→1
    private val shiftMs      = 220L

    // ====== 縦アニメ（背景のみY補間） ======
    private var climbFromFloor = 0
    private var climbToFloor   = 0
    private var climbT         = 1f     // 0→1
    private val climbMs        = 280L

    // ====== 差分検出用の直前“コミット済み”論理位置 ======
    private var prevCol   = 0
    private var prevFloor = 0

    // ====== 最高到達階（チェックポイント判定用） ======
    private var highestFloor = 1
    private val checkpoints = listOf(10, 20, 30, 40, 50, 60)

    // ====== 被弾の震え演出（必要なら継続使用可） ======
    private var playerShakeMs: Long = 0L
    private val playerShakeTotal: Long = 180L

    // ====== 足バタ演出（不安定ポーズの瞬間に1回だけ） ======
    private var lastPose: PlayerPose = PlayerPose.BOTH_DOWN
    private var footFxT = 1f
    private val footFxMs = 200L

    // ====== 落下～逆スクロール演出 ======
    private var fallTimer = 0f
    private var slideOffDuration = 0.8f       // プレイヤーが画面外へ消えるまで
    private var reverseScrollTime = 3.0f      // ビル逆スクロール時間
    private val totalFallSeqTime get() = slideOffDuration + reverseScrollTime

    private var playerStartTop = 0f           // 落下演出で使う：描画時のtop（上辺）
    private var playerEndTop = 0f             // 画面外のtop
    private var playerAlpha = 1f              // 0..1（フェード）
    private var reverseScrollOffsetPx = 0f    // 背景群にだけ適用する下向きスクロール量
    private var reverseFloorsPerSec = 10f      // ★ 追加：逆スクロール速度（階/秒）。4〜8で調整

    // ▼ 既存フィールド群の近くに追加
    private var dropT = 1f                 // 0→1（1で非アニメ）
    private val dropMs = 180L              // 降下アニメ時間（お好みで150〜220ms）
    private var dropFromFloor = 0
    private var dropToFloor   = 0

    // ===== GAME OVER 演出 =====
    private var goCooldownMs = 0L            // クールタイム（ms）
    private var goBlinkMs = 0L               // 点滅タイマ
    private var goBlinkOn = true             // 表示/非表示トグル
    private val goBlinkIntervalMs = 500L     // 点滅間隔（お好みで）

    private var kongFrames: Array<Bitmap?> = arrayOfNulls(2)

    private var heliFrames: Array<Bitmap?> = arrayOfNulls(2)
    private val HELI_FRAME_MS = 200L   // 100msごとに切替（=10fps相当）

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        restartGame()
    }

    // ====== ライフサイクル ======
    fun onResumeView() { startLoop() }
    fun onPauseView() { stopLoop() }

    private fun startLoop() {
        if (running) return
        running = true
        loopThread = Thread { loop() }.also { it.start() }
    }
    private fun stopLoop() {
        running = false
        try { loopThread?.join(200) } catch (_: InterruptedException) {}
        loopThread = null
    }

    // ====== メインループ ======
    private fun loop() {
        val dtMs = Config.DT_MS.toLong()
        var last = System.currentTimeMillis()
        mainLoop@while (running) {
            val now = System.currentTimeMillis()
            if (now - last >= dtMs) {

                val dtSec = dtMs / 1000f
                // 入力＆ロジック：PLAYING のときだけ動かす
                if (phase == GamePhase.PLAYING) {
                    world.update(dtMs)
                    if (!world.isClearActive()) {
                        world.handleInput(lastLeft, lastRight)
                    }
                }

                // 被弾ワンショットを確認（不安定時のみ即落下）
                if (!world.isClearActive() && phase == GamePhase.PLAYING) {
                    val wasHit = world.consumeHitFlag() // ← World側で実装（1フレームだけtrue）
                    if (wasHit /*&& world.player.stability == Stability.UNSTABLE*/) {
                        // UNSTABLEはWorld側でpodの判定をしているのでここでは不要
                        startFallSequence()
                        SoundManager.play(SoundManager.Sfx.AREEE)
                        Handler(Looper.getMainLooper()).postDelayed({
                            SoundManager.playJingle(SoundManager.Sfx.FALL)
                        }, 400L)
                    }
                }

                // 足バタ制御（姿勢変化検知）
                if (!world.isClearActive() && phase == GamePhase.PLAYING) {
                    val curPose = world.player.pose
                    if (curPose != lastPose) {
                        if (curPose == PlayerPose.LUP_RDOWN || curPose == PlayerPose.LDOWN_RUP) {
                            footFxT = 0f
                        }
                        lastPose = curPose
                    }
                    if (footFxT < 1f) {
                        footFxT = (footFxT + dtMs / footFxMs.toFloat()).coerceAtMost(1f)
                    }
                } else {
                    // 非PLAYINGでは足バタは進めない（任意：進めても悪さはしない）
                }

                if (!world.isClearActive() && phase == GamePhase.PLAYING && dropT >= 1f) {
                    val p = world.player.pos
                    // World側で BOTH_DOWN 被弾時に floor を 1 下げている前提
                    if (p.floor < prevFloor) {
                        dropFromFloor = prevFloor
                        dropToFloor   = p.floor           // たぶん prevFloor-1
                        dropT = 0f                        // 降下アニメ開始
                    }
                }

                // ---- 差分検出（PLAYING時のみ） ----
                if (!world.isClearActive() && phase == GamePhase.PLAYING) {
                    val p = world.player.pos
                    // 横：1ステップだけ開始
                    if (shiftT >= 1f && p.col != prevCol) {
                        val step = if (p.col > prevCol) +1 else -1
                        shiftFromCol = prevCol
                        shiftToCol   = prevCol + step
                        shiftT = 0f
                    }
                    // 縦：上へ1段だけ開始
                    if (climbT >= 1f && p.floor > prevFloor) {
                        climbFromFloor = prevFloor
                        climbToFloor   = prevFloor + 1
                        climbT = 0f
                    }
                }

                // ---- 補間の進行（PLAYING時のみ） ----
                if (/*!world.isClearActive() &&*/ phase == GamePhase.PLAYING) {
                    if (shiftT < 1f) {
                        shiftT = (shiftT + dtMs / shiftMs.toFloat()).coerceAtMost(1f)
                        if (shiftT >= 1f) {
                            prevCol = shiftToCol
                        }
                    }
                    if (climbT < 1f) {
                        climbT = (climbT + dtMs / climbMs.toFloat()).coerceAtMost(1f)
                        if (climbT >= 1f) {
                            prevFloor = climbToFloor
                            if (prevFloor > highestFloor) highestFloor = prevFloor // ★チェックポイント更新
                        }
                    }

                    // ▼ 既存の shift/climb 進行に続けて
                    if (!world.isClearActive() && dropT < 1f) {
                        dropT = (dropT + dtMs / dropMs.toFloat()).coerceAtMost(1f)
                        if (dropT >= 1f) {
                            // ここでようやく prevFloor を“1段下がった”値に確定
                            prevFloor = dropToFloor
                        }
                    }
                }

                // ---- 落下～逆スクロール中の進行 ----
                if (phase == GamePhase.FALL_SEQUENCE) {
                    //fallTimer += dtMs
                    fallTimer += dtSec

                    // 0..slideOffDuration: プレイヤーを画面外へスライド＆フェード
                    val tSlide = (fallTimer / slideOffDuration).coerceIn(0f, 1f)
                    playerAlpha = 1f - tSlide

                    // slide完了後：ビル逆スクロール
                    if (fallTimer > slideOffDuration) {
                        // ★ タイル高さ × 階/秒 = px/秒
                        val cols = Config.COLS
                        val tileW = width / cols
                        val tileH = tileW
                        val pxPerSec = reverseFloorsPerSec * tileH
                        reverseScrollOffsetPx += pxPerSec * dtSec   // ★ 修正（秒ベース）
                    }

                    // 完了でライフ消費→リスポーン／GAME_OVER
                    if (fallTimer >= totalFallSeqTime) {
                        consumeLifeAndTransit()
                    }
                }

                // （演出タイマ：震え）
                if (playerShakeMs > 0L) {
                    playerShakeMs -= dtMs
                    if (playerShakeMs < 0L) playerShakeMs = 0L
                }

                // ===== GAME OVER 中の点滅・クールタイム更新 =====
                if (phase == GamePhase.GAME_OVER) {
                    if (goCooldownMs > 0L) {
                        // クールタイム中だけ点滅
                        goBlinkMs += dtMs
                        if (goBlinkMs >= goBlinkIntervalMs) {
                            goBlinkMs = 0L
                            goBlinkOn = !goBlinkOn
                        }
                        goCooldownMs -= dtMs
                        if (goCooldownMs < 0L) goCooldownMs = 0L
                    } else {
                        // 3秒経過後は常時点灯＆点滅停止
                        goBlinkOn = true
                    }
                }

                drawFrame()
                if (world.consumeClearDone() /*world.isClearDone()*/) {
                    //Log.d("StageClearTest", "StageClear!!!")
                    startNextStage()
                    continue@mainLoop
                }
                post {
                    onHudUpdate?.invoke(world.player.score, world.player.lives, world.player.pos.floor)
                }
                last = now

                // 再スタートのトリガはここで拾う
                if (phase == GamePhase.GAME_OVER && goCooldownMs <= 0L) {
                    if (lastLeft != LeverDir.CENTER || lastRight != LeverDir.CENTER) {
                        restartGame()
                    }
                }
            } else {
                try { Thread.sleep(2) } catch (_: InterruptedException) {}
            }
        }
    }

    // ====== フェーズ遷移：落下開始 ======
    private fun startFallSequence() {
        world.clearObjectsForFall()
        phase = GamePhase.FALL_SEQUENCE
        fallTimer = 0f
        playerAlpha = 1f
        reverseScrollOffsetPx = 0f

        // プレイヤー矩形のtop（描画基準）を計算して、そこから画面外へ
        val cols = Config.COLS
        val tileW = width / cols
        val tileH = tileW
        val fyFixed = 1
        val baseTop  = (height - (fyFixed + 1) * tileH).toFloat()
        val scale = 1.6f
        val halfH = tileH * scale / 2f
        val destCenterY = baseTop + tileH / 2f
        val curTop = destCenterY - halfH

        playerStartTop = curTop
        playerEndTop   = height.toFloat() + halfH  // 画面下へ抜ける
    }

    // ====== フェーズ遷移：落下完了 → ライフ処理 ======
    private fun consumeLifeAndTransit() {
        world.player.lives -= 1
        if (world.player.lives <= 0) {
            phase = GamePhase.GAME_OVER
            goCooldownMs = 3000L      // ★ 3秒クールタイム開始
            goBlinkMs = 0L
            goBlinkOn = true
            return
        }

        // 復帰地点：最後に通過したチェックポイント（無ければ10F）
        val respawnFloor = checkpoints.filter { it <= highestFloor }.maxOrNull() ?: 1
        placePlayerAtFloor(respawnFloor)
        resetCameraForRespawn(respawnFloor)
        world.grantStartSafety(durationMs = 2000L, horizRange = 1, vertRange = 2)

        // 演出系を初期化
        playerAlpha = 1f
        reverseScrollOffsetPx = 0f
        fallTimer = 0f

        // 補間を初期化
        shiftFromCol = prevCol
        shiftToCol   = prevCol
        shiftT = 1f
        climbFromFloor = prevFloor
        climbToFloor   = prevFloor
        climbT = 1f

        phase = GamePhase.RESPAWN
        // 次フレームで自動的に PLAYING に入る
        SoundManager.playJingle(SoundManager.Sfx.PLAY_START)
        phase = GamePhase.PLAYING
    }

    private fun placePlayerAtFloor(floor: Int) {
        world.player.pos = world.player.pos.copy(floor = floor)
        prevFloor = floor
    }

    private fun resetCameraForRespawn(floor: Int) {
        val cols = Config.COLS
        val tileW = width / cols
        val tileH = tileW
        val visibleRows = height / tileH + 2
        val maxTop = (Config.FLOORS - visibleRows).coerceAtLeast(0)
        val targetFromBottom = 1
        camTopFloor = (floor - targetFromBottom).coerceIn(0, maxTop)
    }

    // ====== イージング ======
    private fun easeOutCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    val shirakeScale = 2.2f
    private var shirakeFrames: Array<Bitmap?> = arrayOfNulls(2) // 羽ばたき 0/1
    private val shirakePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }
    private val shirakeSrc = Rect()
    private val shirakeDst = RectF()
    // ====== 描画 ======

    private fun drawFrame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            // ========= 画面・タイル寸法 =========
            val cols = Config.COLS
            val tileW = width / cols
            val tileH = tileW
            val visibleRows = height / tileH + 2
            world.setScreenRowsHint(visibleRows)

            // ========= 配色 =========
            val facadeColor  = Color.rgb(255, 248, 170) // 外壁クリーム
            val mullionColor = Color.rgb(150,   0,   0) // 赤い縦柱
            val winOpen      = Color.rgb(  6,   8,  12) // OPEN: 黒
            val winClosed    = Color.rgb( 58, 220, 255) // CLOSE: 水色

            // ========= 背景 =========
            //canvas.drawColor(facadeColor)
            val skyColor = Color.rgb(120, 170, 255) // 好みで
            canvas.drawColor(skyColor)

            // ========= カメラ（下から2段目を狙う） =========
            val targetFromBottom = 1
            //val maxTop = (Config.FLOORS - visibleRows).coerceAtLeast(0)

            val animatingClimb = climbT < 1f
            val animatingDrop  = dropT  < 1f

            //val desiredTop = (world.player.pos.floor - targetFromBottom).coerceIn(0, maxTop)
            //val cameraTopBase =
            //    if (animatingClimb)
            //        (climbFromFloor - targetFromBottom).coerceIn(0, maxTop)
            //    else if (animatingDrop)
            //        (dropFromFloor  - targetFromBottom).coerceIn(0, maxTop)
            //    else
            //        desiredTop

            //camTopFloor = cameraTopBase

            val desiredTopUnclamped = (world.player.pos.floor - targetFromBottom)
            val cameraTopBase =
                if (animatingClimb)
                    (climbFromFloor - targetFromBottom)
                else if (animatingDrop)
                    (dropFromFloor  - targetFromBottom)
                else
                    desiredTopUnclamped
            camTopFloor = cameraTopBase.coerceAtLeast(0)


            // ========= “逆スクロール”（落下演出） =========
            val revRows = if (phase == GamePhase.FALL_SEQUENCE)
                (reverseScrollOffsetPx / tileH).toInt() else 0
            val revPx = if (phase == GamePhase.FALL_SEQUENCE)
                (reverseScrollOffsetPx - revRows * tileH) else 0f

            // 今フレームの有効カメラ上端
            //val cameraTopEffective = camTopFloor.coerceIn(0, maxTop)
            val cameraTopEffective = camTopFloor.coerceAtLeast(0)

            // 屋上より下に実際に描ける行数（ここがポイント）
            //val drawRows = (Config.FLOORS - cameraTopEffective).coerceAtMost(visibleRows).coerceAtLeast(0)
            val drawRows = (Config.FLOORS - cameraTopEffective).coerceIn(0, visibleRows)

            val centerCol = Config.COLS / 2

            fun easeOutCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
            val kUp = easeOutCubic(climbT)
            val climbOffsetPxBase = if (animatingClimb) kUp * tileH else 0f
            val kDown = easeOutCubic(dropT)
            val dropOffsetPx = if (animatingDrop) (-kDown * tileH) else 0f
            val climbOffsetPx = climbOffsetPxBase + dropOffsetPx - revPx

            // 最上階より上は描かないための行数と屋上Y
            //val drawRows = (Config.FLOORS - cameraTopEffective).coerceIn(0, visibleRows)
            val roofTopY = (height - drawRows * tileH + climbOffsetPx).coerceAtLeast(0f)
            // 外壁（クリーム）は屋上から下だけ塗る
            val facadePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = facadeColor }
            canvas.drawRect(0f, roofTopY, width.toFloat(), height.toFloat(), facadePaint)

            val roofRow = (Config.FLOORS - 1) - cameraTopEffective
            if (roofRow in 0 until visibleRows) {
                val yTop = height - ((roofRow + 1) * tileH) + climbOffsetPx
                val roofH = (tileH * 0.18f)
                val roofPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 225, 140) } // お好み
                canvas.drawRect(0f, yTop - roofH, width.toFloat(), yTop, roofPaint)
            }

            // ========= 縦柱（屋上より上には描かない） =========
            run {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mullionColor }
                val barW = tileW * 0.18f
                // 屋上の画面上 Y（この上は空）
                val roofClipTop = (height - (drawRows * tileH) + climbOffsetPx).coerceAtLeast(0f)
                for (i in 0..cols) {
                    val x = i * tileW.toFloat()
                    canvas.drawRect(x - barW * 0.5f, roofClipTop, x + barW * 0.5f, height.toFloat(), p)
                }
            }

            fun isThreeColFloor(floor: Int): Boolean = false
            fun colVisibleMaskFor(floor: Int): BooleanArray {
                return if (isThreeColFloor(floor))
                    booleanArrayOf(true, false, true, false, true)
                else
                    booleanArrayOf(true, true, true, true, true)
            }
            fun drawSidePanelsIfNeeded(canvas: Canvas, floor: Int, top: Float, tileH: Int) {
                if (!isThreeColFloor(floor)) return
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = winOpen }
                val panelW = tileW * 0.95f
                canvas.drawRect(0f, top, panelW, top + tileH, p) // 左
                canvas.drawRect(width - panelW, top, width.toFloat(), top + tileH, p) // 右
            }

            // 可視範囲（Worldへ連携）※“描ける行数”で渡す
            world.setVisibleRange(cameraTopEffective, drawRows)

            val bossView = world.getBossView(cameraTopEffective, drawRows)

            // ========= 窓タイル（屋上より上は描かない） =========
            run {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val windowScale = 0.7f
                val winW = tileW * windowScale
                val winH = tileH * windowScale
                val offsetX = (tileW - winW) / 2f
                val offsetY = (tileH - winH) / 2f

                for (r in 0 until drawRows) {
                    val floor = cameraTopEffective + r
                    val top = height - ((r + 1) * tileH) + climbOffsetPx

                    // この行が“ボス露出許可”内か？
                    val bossRowVisible = bossView?.let {
                        floor in it.bottom..(it.bottom + it.reveal - 1)
                    } ?: false
                    val bossLeft  = bossView?.leftCol ?: -999
                    val bossRight = if (bossView != null) bossLeft + bossView.width - 1 else -999

                    val mask = colVisibleMaskFor(floor)
                    for (col in 0 until cols) {
                        val left = col * tileW.toFloat()

                        if (bossRowVisible && col in bossLeft..bossRight) {
                            if (col == centerCol) {
                                // 中央列は常に通路（黒窓のみ）
                                p.style = Paint.Style.FILL
                                p.color = winOpen
                                canvas.drawRect(left + offsetX, top + offsetY, left + offsetX + winW, top + offsetY + winH, p)
                            } else {
                                // サイド2列は白で占有
                                p.style = Paint.Style.FILL
                                p.color = Color.WHITE
                                canvas.drawRect(left + offsetX, top + offsetY, left + offsetX + winW, top + offsetY + winH, p)
                            }
                            continue
                        }

                        if (!mask[col]) continue

                        // 1) 黒い窓
                        p.style = Paint.Style.FILL
                        p.color = winOpen
                        canvas.drawRect(left + offsetX, top + offsetY, left + offsetX + winW, top + offsetY + winH, p)

                        // 2) 閉じ進捗（水色）
                        val prog = world.getWindowCloseProgress(Cell(col, floor))
                        if (prog > 0f) {
                            p.color = winClosed
                            val panelBottom = top + offsetY + winH * prog
                            canvas.drawRect(left + offsetX, top + offsetY, left + offsetX + winW, panelBottom, p)
                        }
                    }
                    drawSidePanelsIfNeeded(canvas, floor, top.toFloat(), tileH)
                }
            }

            // ==== ボス領域の白塗り（左右どちらに居ても両側固定） ====
            run {
                val bv = world.getBossView(cameraTopEffective, drawRows) ?: return@run
                val rows = bv.reveal
                if (rows <= 0) return@run

                val rBottom  = (bv.bottom - cameraTopEffective)
                val bottomPx = height - (rBottom * tileH) + climbOffsetPx
                val topPx    = bottomPx - rows * tileH

                // 屋上より上に出さない
                val roofClipTop = (height - (drawRows * tileH) + climbOffsetPx).coerceAtLeast(0f)
                canvas.save()
                canvas.clipRect(0f, roofClipTop, width.toFloat(), height.toFloat())

                val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
                val center = Config.COLS / 2
                for (c in 0 until Config.COLS) {
                    if (c == center) continue
                    val xL = c * tileW.toFloat()
                    canvas.drawRect(xL, topPx, xL + tileW, bottomPx, mask)
                }
                canvas.restore()
            }

            // ===== キングコング描画（拡大して通路へ1列はみ出し） =====
            run {
                val bd  = world.getBossDraw() ?: return@run
                val bmp = kongFrames[bd.pose.coerceIn(0,1)] ?: return@run

                val rBottom  = bd.areaBottom - cameraTopEffective
                val bottomPx = height - (rBottom * tileH) + climbOffsetPx

                val reveal   = world.getBossView(cameraTopEffective, drawRows)?.reveal ?: 0
                if (reveal <= 0) return@run
                val clipTop  = bottomPx - reveal * tileH

                val tileWf = tileW.toFloat()
                val sideAreaW = 2 * tileWf
                val overhangPx = 1f * tileWf

                val clipLeft  = if (bd.side < 0) 0f else width - sideAreaW - overhangPx
                val clipRight = if (bd.side < 0) sideAreaW + overhangPx else width.toFloat()

                val boxW = (sideAreaW + overhangPx) * 0.94f
                val boxH = (bd.areaHeight * tileH) * 1.20f
                val s = minOf(boxW / bmp.width, boxH / bmp.height)
                val w = bmp.width  * s
                val h = bmp.height * s

                val cx = (clipLeft + clipRight) * 0.5f
                val dst = RectF(cx - w/2f, bottomPx - h, cx + w/2f, bottomPx)
                val src = Rect(0, 0, bmp.width, bmp.height)

                val roofClipTop = (height - (drawRows * tileH) + climbOffsetPx).coerceAtLeast(0f)
                canvas.save()
                canvas.clipRect(clipLeft.coerceAtLeast(0f), maxOf(clipTop, roofClipTop), clipRight, bottomPx)

                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false; alpha = 255 }
                if (bd.side > 0) {
                    canvas.scale(-1f, 1f, dst.centerX(), dst.centerY())
                }
                canvas.drawBitmap(bmp, src, dst, p)
                canvas.restore()
            }

            // ===== おじさん（DONE は非表示、窓内クリップ） =====
            if (!world.isClearActive() && phase != GamePhase.FALL_SEQUENCE) {
                run {
                    val bmp = Assets.getOjisanBitmap()
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }
                    val windowScale = 0.7f
                    val winW = tileW * windowScale
                    val winH = tileH * windowScale
                    val offX = (tileW - winW) / 2f
                    val offY = (tileH - winH) / 2f

                    for (o in world.ojisans) {
                        if (o.state == Ojisan.State.DONE) continue
                        val r = (o.floor - cameraTopEffective)
                        if (r !in 0 until drawRows) continue

                        val tileLeft = o.col * tileW.toFloat()
                        val tileTop = height - (r + 1) * tileH + climbOffsetPx

                        val winLeft = tileLeft + offX
                        val winTop = tileTop + offY
                        val winRight = winLeft + winW
                        val winBottom = winTop + winH
                        val cx = (winLeft + winRight) * 0.5f

                        val boxW = winW * 0.90f
                        val boxH = winH * 0.86f

                        val (w, h) = if (bmp != null) {
                            val sBmp = minOf(boxW / bmp.width, boxH / bmp.height)
                            bmp.width * sBmp to bmp.height * sBmp
                        } else {
                            boxW to boxH
                        }

                        val t = o.t.coerceIn(0f, 1f)
                        val eased = 1f - (1f - t) * (1f - t) * (1f - t)

                        val yBottom = when (o.state) {
                            Ojisan.State.SLIDE_IN -> winBottom + h * (1f - eased)
                            Ojisan.State.THROW    -> winBottom
                            Ojisan.State.SLIDE_OUT-> winBottom + h * eased
                            Ojisan.State.DONE     -> Float.NaN
                        }
                        if (yBottom.isNaN()) continue
                        val yTop = yBottom - h

                        if (yBottom <= winTop || yTop >= winBottom) continue
                        val clipBottom = winBottom - 0.5f

                        canvas.save()
                        canvas.clipRect(winLeft, winTop, winRight, clipBottom)
                        if (bmp != null) {
                            paint.isFilterBitmap = false
                            val src = Rect(0, 0, bmp.width, bmp.height)
                            val dst = RectF(cx - w / 2f, yTop, cx + w / 2f, yBottom)
                            canvas.drawBitmap(bmp, src, dst, paint)
                        } else {
                            canvas.drawRect(cx - w / 2f, yTop, cx + w / 2f, yBottom, paint)
                        }
                        canvas.restore()
                    }
                }
            }

            // ===== 植木鉢 =====
            if (!world.isClearActive() && phase != GamePhase.FALL_SEQUENCE) {
                run {
                    val bmpPot  = Assets.getPotBitmap()
                    val bmpDrop = Assets.getBirdDropBitmap()
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 60, 30) }

                    for (pot in world.pots) {
                        val r = (pot.yFloor - cameraTopEffective)
                        if (r + 1 < 0 || r >= drawRows) continue

                        val top = height - ((r + 1f) * tileH) + climbOffsetPx
                        val left = pot.col * tileW.toFloat()
                        val cx = left + tileW * 0.5f

                        val isBird = (pot.kind == Pot.Kind.BIRD_DROP)
                        val bmp = if (isBird) bmpDrop else bmpPot
                        if (bmp != null) {
                            val box = tileW * 0.8f
                            val scale = minOf(box / bmp.width, box / bmp.height)
                            val w = bmp.width * scale
                            val h = bmp.height * scale
                            p.isFilterBitmap = false
                            val src = Rect(0, 0, bmp.width, bmp.height)
                            val dst = RectF(cx - w / 2f, top, cx + w / 2f, top + h)
                            canvas.drawBitmap(bmp, src, dst, p)
                        } else {
                            val size = tileW * 0.35f
                            canvas.drawRect(cx - size / 2f, top, cx + size / 2f, top + size, p)
                        }
                    }
                }
            }

            // ===== しらけどり =====
            run {
                val sd = world.getShirakeDraw() ?: return@run
                val bmp = shirakeFrames[sd.flap.coerceIn(0,1)] ?: return@run

                val r  = (sd.yFloor - camTopFloor)
                val cy = height - ((r + 0.5f) * tileH)
                val cx = sd.xFrac * width

                val baseW = tileW * 1.1f
                val baseH = tileH * 0.9f
                val boxW  = baseW * shirakeScale
                val boxH  = baseH * shirakeScale
                val s     = minOf(boxW / bmp.width, boxH / bmp.height)
                val w     = bmp.width * s
                val h     = bmp.height * s

                val left   = cx - w / 2f
                val top    = cy - h / 2f
                val right  = cx + w / 2f
                val bottom = top + h

                shirakeSrc.set(0, 0, bmp.width, bmp.height)
                shirakeDst.set(left, top, right, bottom)

                if (sd.dir < 0) {
                    canvas.save()
                    canvas.scale(-1f, 1f, cx, cy)
                    canvas.drawBitmap(bmp, shirakeSrc, shirakeDst, shirakePaint)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(bmp, shirakeSrc, shirakeDst, shirakePaint)
                }
            }

            // ===== ヘリ（クリア演出） =====
            run {
                val hd = world.getHeliDraw() ?: return@run

                // 既存のカメラ・タイル寸法を流用
                val tileWf = tileW.toFloat()
                val tileHf = tileH.toFloat()

                // yFloor → ピクセル：他の箇所と“同じ”基準で変換（←ズレ防止の要）
                val r = (hd.yFloor - cameraTopEffective)
                // “床の水平線”を bottomPx にする（白塗り・ボスと同じ基準）
                val bottomPx = height - (r * tileHf) + climbOffsetPx

                // ヘリの描画サイズ（お好みで調整）
                val frame = ((world.currentTimeMs / HELI_FRAME_MS) % heliFrames.size).toInt()
                val bmp = heliFrames[frame] ?: heliFrames[0]
                //val bmp = Assets.getHeliBitmap(0) // 無ければ null を返す実装想定
                val baseW = tileWf * 2.3f      // 横は2列ちょい
                val baseH = tileHf * 1.1f      // 縦は1列ちょい
                val boxW  = baseW * 2.0f //HELI_SCALE
                val boxH  = baseH * 2.0f //HELI_SCALE
                val (w, h) = if (bmp != null) {
                    val s = kotlin.math.min(boxW / bmp.width, boxH / bmp.height)
                    bmp.width * s to bmp.height * s
                } else {
                    boxW to boxH
                }

                // ヘリ中心X（xFrac は 0..1 の画面比率）
                val cx = (hd.xFrac * width).coerceIn(0f, width.toFloat())
                // 足（スキッド）の下端を bottomPx に合わせる
                val yTop = bottomPx - h
                val yBottom = bottomPx

                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = false }

                if (bmp != null) {
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    val dst = RectF(cx - w/2f, yTop, cx + w/2f, yBottom)
                    // 斜め感がほしければ rotate してもOK（省略）
                    canvas.drawBitmap(bmp, src, dst, p)
                } else {
                    // フォールバック描画（四角）
                    p.color = Color.DKGRAY
                    canvas.drawRect(cx - w/2f, yTop, cx + w/2f, yBottom, p)
                    // 簡単なローター
                    p.strokeWidth = tileHf * 0.06f
                    canvas.drawLine(cx - w*0.6f, yTop - tileHf*0.15f, cx + w*0.6f, yTop - tileHf*0.15f, p)
                }
            }


            // ===== プレイヤー（固定Y。落下中のみスライド） =====
            run {
                val p = Paint()
                val pose = world.player.pose

                val useFoot = (pose == PlayerPose.LUP_RDOWN || pose == PlayerPose.LDOWN_RUP) && footFxT < 1f
                val bmp = if (useFoot) {
                    Assets.getPlayerFootBitmap(pose, context) ?: Assets.getPlayerBitmap(pose)
                } else {
                    Assets.getPlayerBitmap(pose)
                }

                val fyFixed = 1
                val baseTop  = (height - (fyFixed + 1) * tileH).toFloat()
                val scale = 1.6f
                val cx = (shiftFromCol * (1f - easeOutCubic(shiftT)) + shiftToCol * easeOutCubic(shiftT)) * tileW + tileW / 2f
                val halfW = tileW * scale / 2f
                val halfH = tileH * scale / 2f

                var cy = if (phase == GamePhase.FALL_SEQUENCE) {
                    val tSlide = (fallTimer / slideOffDuration).coerceIn(0f, 1f)
                    val curTop = playerStartTop + (playerEndTop - playerStartTop) * tSlide
                    curTop + halfH
                } else {
                    baseTop + tileH / 2f
                }

                // ★クリア演出中はヘリに追従（ヘリ足の少し下にぶら下がる）
                if (world.isPlayerAttachedToHeli()) {
                    world.getHeliDraw()?.let { hd ->
                        val r = (hd.yFloor - cameraTopEffective)
                        val heliBottomPx = height - (r * tileH.toFloat()) + climbOffsetPx
                        cy = heliBottomPx + (tileH * 0.20f) // ぶら下がり分のオフセット
                    }
                }

                var shakeX = 0f
                var shakeY = 0f
                if (world.player.stability == Stability.STABLE && playerShakeMs > 0L) {
                    val amp = 3f
                    shakeX = (-amp..amp).random()
                    shakeY = (-amp..amp).random()
                }

                val left   = cx - halfW + shakeX
                val top    = cy - halfH + shakeY
                val right  = cx + halfW
                val bottom = cy + halfH

                val oldAlpha = p.alpha
                if (phase == GamePhase.FALL_SEQUENCE) {
                    p.alpha = (playerAlpha * 255).toInt().coerceIn(0, 255)
                }

                if (bmp != null) {
                    p.isFilterBitmap = false
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    val dst = RectF(left, top, right, bottom)
                    canvas.drawBitmap(bmp, src, dst, p)
                } else {
                    // 簡易記号
                    val symbol = when (pose) {
                        PlayerPose.BOTH_UP   -> "▽"
                        PlayerPose.BOTH_DOWN -> "△"
                        PlayerPose.LUP_RDOWN -> "＞"
                        PlayerPose.LDOWN_RUP -> "＜"
                    }
                    p.reset()
                    p.isAntiAlias = true
                    p.textAlign = Paint.Align.CENTER
                    p.typeface = Typeface.MONOSPACE
                    p.textSize = tileH * 0.8f
                    p.color = Color.WHITE
                    p.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                    val tx = left + tileW * 0.5f
                    val by = top + tileH * 0.72f
                    canvas.drawText(symbol, tx, by, p)
                }

                if (phase == GamePhase.FALL_SEQUENCE) p.alpha = oldAlpha
            }

            if (phase == GamePhase.GAME_OVER) {
                drawGameOver(canvas)
            }
        } finally {
            holder.unlockCanvasAndPost(canvas)
        }
    }


    // ====== 乱数ユーティリティ ======
    fun ClosedFloatingPointRange<Float>.random() =
        (start + Math.random().toFloat() * (endInclusive - start))

    // ====== SurfaceHolder.Callback ======
    override fun surfaceCreated(holder: SurfaceHolder) {
        Assets.init(context)

        // ヘリ画像
        heliFrames[0] = Assets.getHeliBitmap(0)
        heliFrames[1] = Assets.getHeliBitmap(1) ?: heliFrames[0]  // 2枚目が無ければ1枚目で代用

        // しらけどりの2フレーム（なければ同じ画像を2回返す実装でOK）
        shirakeFrames[0] = Assets.getShirakeBitmap(0)
        shirakeFrames[1] = Assets.getShirakeBitmap(1)
        // ボスの2フレーム
        kongFrames[0] = Assets.getKongBitmap(0)
        kongFrames[1] = Assets.getKongBitmap(1)
        // 現在の論理位置で初期化
        prevCol   = world.player.pos.col
        prevFloor = world.player.pos.floor
        highestFloor = prevFloor

        // 横アニメ初期化
        shiftFromCol = prevCol
        shiftToCol   = prevCol
        shiftT = 1f

        // 縦アニメ初期化
        climbFromFloor = prevFloor
        climbToFloor   = prevFloor
        climbT = 1f

        // カメラ初期化（最下段表示スタート）
        camTopFloor = 0

        startLoop()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { stopLoop() }

    // ====== 入力（GAME OVER → リスタート） ======
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (phase == GamePhase.GAME_OVER && event.action == MotionEvent.ACTION_DOWN) {
            if (goCooldownMs <= 0L) {
                restartGame()
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (phase == GamePhase.GAME_OVER) {
            if (goCooldownMs <= 0L) {
                restartGame()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun restartGame() {
        world.reset() // ★World側で初期化処理を用意
        world.grantStartSafety(durationMs = 2500L, horizRange = 1, vertRange = 2)
        reverseScrollOffsetPx = 0f
        fallTimer = 0f
        playerAlpha = 1f
        goCooldownMs = 0L

        // prevの初期化
        prevCol   = world.player.pos.col
        prevFloor = world.player.pos.floor
        highestFloor = prevFloor

        // 補間初期化
        shiftFromCol = prevCol; shiftToCol = prevCol; shiftT = 1f
        climbFromFloor = prevFloor; climbToFloor = prevFloor; climbT = 1f

        // カメラ初期化
        camTopFloor = 0

        phase = GamePhase.PLAYING
        SoundManager.playJingle(SoundManager.Sfx.PLAY_START)
    }

    private fun startNextStage() {
        currentStage += 1
        world.prepareNextStage(currentStage)
        world.grantStartSafety(durationMs = 2000L, horizRange = 1, vertRange = 2)

        // 位置・カメラ・補間の再初期化（restartGame() 相当だがスコア/残機は維持）
        prevCol   = world.player.pos.col
        prevFloor = world.player.pos.floor
        highestFloor = prevFloor

        shiftFromCol = prevCol; shiftToCol = prevCol; shiftT = 1f
        climbFromFloor = prevFloor; climbToFloor = prevFloor; climbT = 1f
        dropT = 1f

        camTopFloor = 0
        phase = GamePhase.PLAYING
        SoundManager.playJingle(SoundManager.Sfx.PLAY_START)
    }


    private fun drawGameOver(canvas: Canvas) {
        if (!goBlinkOn) return  // ★ 点滅OFFのフレームは描かない

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            textSize = (width * 0.16f).coerceAtLeast(56f)
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val cx = width / 2f
        val cy = height / 2f
        canvas.drawText("GAME OVER", cx, cy, paint)
    }

}
