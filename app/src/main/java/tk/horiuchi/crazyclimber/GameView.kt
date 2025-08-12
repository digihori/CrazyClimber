package tk.horiuchi.crazyclimber.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
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
    private enum class GamePhase { PLAYING, FALL_SEQUENCE, RESPAWN, GAME_OVER }
    private var phase = GamePhase.PLAYING

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
    private val checkpoints = listOf(10, 20, 30, 40)

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
    private var reverseScrollTime = 2.0f      // ビル逆スクロール時間
    private val totalFallSeqTime get() = slideOffDuration + reverseScrollTime

    private var playerStartTop = 0f           // 落下演出で使う：描画時のtop（上辺）
    private var playerEndTop = 0f             // 画面外のtop
    private var playerAlpha = 1f              // 0..1（フェード）
    private var reverseScrollOffsetPx = 0f    // 背景群にだけ適用する下向きスクロール量
    private var reverseFloorsPerSec = 10f      // ★ 追加：逆スクロール速度（階/秒）。4〜8で調整

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
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
        while (running) {
            val now = System.currentTimeMillis()
            if (now - last >= dtMs) {

                val dtSec = dtMs / 1000f
                // 入力＆ロジック：PLAYING のときだけ動かす
                if (phase == GamePhase.PLAYING) {
                    world.handleInput(lastLeft, lastRight)
                    world.update(dtMs)
                }

                // 被弾ワンショットを確認（不安定時のみ即落下）
                if (phase == GamePhase.PLAYING) {
                    val wasHit = world.consumeHitFlag() // ← World側で実装（1フレームだけtrue）
                    if (wasHit && world.player.stability == Stability.UNSTABLE) {
                        startFallSequence()
                    }
                }

                // 足バタ制御（姿勢変化検知）
                if (phase == GamePhase.PLAYING) {
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

                // ---- 差分検出（PLAYING時のみ） ----
                if (phase == GamePhase.PLAYING) {
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
                if (phase == GamePhase.PLAYING) {
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
                        //val speedPxPerSec = height * 0.25f // 目安：画面高の0.25倍/秒
                        //reverseScrollOffsetPx += speedPxPerSec * (dtMs / 1000f)
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

                drawFrame()
                onHudUpdate?.invoke(world.player.score, world.player.lives, world.player.pos.floor)
                last = now
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
            return
        }

        // 復帰地点：最後に通過したチェックポイント（無ければ10F）
        val respawnFloor = checkpoints.filter { it <= highestFloor }.maxOrNull() ?: 10
        placePlayerAtFloor(respawnFloor)
        resetCameraForRespawn(respawnFloor)

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

    // ====== 描画 ======
    private fun drawFrame() {
        val canvas = holder.lockCanvas() ?: return
        try {
            // ========= 画面・タイル寸法 =========
            val cols = Config.COLS
            val tileW = width / cols
            val tileH = tileW
            val visibleRows = height / tileH + 2

            // ========= 配色 =========
            val facadeColor  = Color.rgb(255, 248, 170) // 外壁クリーム
            val mullionColor = Color.rgb(150,   0,   0) // 赤い縦柱
            val winOpen      = Color.rgb(  6,   8,  12) // 閉：黒
            val winClosed    = Color.rgb( 58, 220, 255) // 開：明るい水色
            val winHalf      = Color.rgb( 30,  80, 120) // 半開

            // ========= 背景クリア =========
            canvas.drawColor(facadeColor)

            // ========= カメラ：下から2段目を狙う（整数） =========
            val targetFromBottom = 1
            val maxTop = (Config.FLOORS - visibleRows).coerceAtLeast(0)
            val desiredTop = (world.player.pos.floor - targetFromBottom).coerceIn(0, maxTop)
            val animatingClimb = climbT < 1f
            val cameraTop = if (animatingClimb)
                (climbFromFloor - targetFromBottom).coerceIn(0, maxTop)
            else
                desiredTop
            camTopFloor = cameraTop

            // ========= 補間量算出 =========
            //val k = easeOutCubic(climbT)
            //val climbOffsetPx = if (animatingClimb) k * tileH else 0f

            val s = easeOutCubic(shiftT)
            val drawColF = shiftFromCol * (1f - s) + shiftToCol * s


// ここまでに cameraTop, climbT, tileW/tileH/visibleRows はある前提

// ★ 逆スクロールを行数と端数pxに分解
            val revRows = if (phase == GamePhase.FALL_SEQUENCE)
                (reverseScrollOffsetPx / tileH).toInt()
            else 0

            val revPx = if (phase == GamePhase.FALL_SEQUENCE)
                (reverseScrollOffsetPx - revRows * tileH)
            else 0f

// ★ 有効カメラ：下に revRows 階ぶんズラす（＝建物が下へ流れて見える）
            //val maxTop = (Config.FLOORS - visibleRows).coerceAtLeast(0)
            val cameraTopEffective = (cameraTop + revRows).coerceIn(0, maxTop)

            // ★ 背景の縦オフセット：元の climbOffsetPx から“端数px”を引く
            fun easeOutCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
            val k = easeOutCubic(climbT)
            val climbOffsetPxBase = if (climbT < 1f) k * tileH else 0f
            val climbOffsetPx = climbOffsetPxBase - revPx



            // ========= 逆スクロール適用ブロック（背景・障害物のみ） =========
            //canvas.save()
            //if (phase == GamePhase.FALL_SEQUENCE) {
            //    canvas.translate(0f, reverseScrollOffsetPx)
            //}

            // 先に外観の縦柱
            run {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mullionColor }
                val barW = tileW * 0.18f
                for (i in 0..cols) {
                    val x = i * tileW.toFloat()
                    canvas.drawRect(x - barW * 0.5f, 0f, x + barW * 0.5f, height.toFloat(), p)
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

            // 窓タイル（背景のみ縦補間）
            run {
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                val windowScale = 0.7f
                val winW = tileW * windowScale
                val winH = tileH * windowScale
                val offsetX = (tileW - winW) / 2f
                val offsetY = (tileH - winH) / 2f

                for (r in 0 until visibleRows) {
                    val floor = (cameraTopEffective + r).coerceAtMost(Config.FLOORS - 1)
                    val mask = colVisibleMaskFor(floor)
                    val top = (height - (r + 1) * tileH + climbOffsetPx)

                    for (col in 0 until cols) {
                        val left = col * tileW.toFloat()
                        p.style = Paint.Style.FILL
                        p.color = if (mask[col]) {
                            when (world.getWindow(Cell(col, floor))) {
                                WindowState.OPEN   -> winOpen
                                WindowState.HALF   -> winHalf
                                WindowState.CLOSED -> winClosed
                            }
                        } else {
                            facadeColor
                        }
                        canvas.drawRect(
                            left + offsetX, top + offsetY,
                            left + offsetX + winW, top + offsetY + winH, p
                        )
                    }
                    drawSidePanelsIfNeeded(canvas, floor, top.toFloat(), tileH)
                }
            }

            // おじさん（DONEは非表示、窓内クリップ）
            if (phase != GamePhase.FALL_SEQUENCE) {
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
                        if (r !in 0 until visibleRows) continue

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
                        val eased = 1f - (1f - t) * (1f - t) * (1f - t)  // easeOutCubic

                        val yBottom = when (o.state) {
                            Ojisan.State.SLIDE_IN -> winBottom + h * (1f - eased)
                            Ojisan.State.THROW -> winBottom
                            Ojisan.State.SLIDE_OUT -> winBottom + h * eased
                            Ojisan.State.DONE -> Float.NaN
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

            // 植木鉢
            if (phase != GamePhase.FALL_SEQUENCE) {
                run {
                    val bmp = Assets.getPotBitmap()
                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 60, 30) }
                    for (pot in world.pots) {
                        val r = (pot.yFloor - cameraTopEffective)
                        val top = height - ((r + 1f) * tileH) + climbOffsetPx
                        val left = pot.col * tileW.toFloat()
                        val cx = left + tileW * 0.5f

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

            // ===== 背景群の逆スクロール適用終了 =====
            //canvas.restore()

            // ===== プレイヤー描画（逆スクロールをかけない） =====
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
                val destCenterX = (shiftFromCol * (1f - easeOutCubic(shiftT)) + shiftToCol * easeOutCubic(shiftT)) * tileW + tileW / 2f
                val halfW = tileW * scale / 2f
                val halfH = tileH * scale / 2f

                // 縦位置：PLAYING時は固定、FALL_SEQUENCEはスライド値
                val destCenterY = if (phase == GamePhase.FALL_SEQUENCE) {
                    val tSlide = (fallTimer / slideOffDuration).coerceIn(0f, 1f)
                    val curTop = playerStartTop + (playerEndTop - playerStartTop) * tSlide
                    curTop + halfH
                } else {
                    baseTop + tileH / 2f
                }

                var shakeX = 0f
                var shakeY = 0f
                if (world.player.stability == Stability.STABLE && playerShakeMs > 0L) {
                    val amp = 3f
                    shakeX = (-amp..amp).random()
                    shakeY = (-amp..amp).random()
                }

                val destLeft   = destCenterX - halfW + shakeX
                val destTop    = destCenterY - halfH + shakeY
                val destRight  = destCenterX + halfW
                val destBottom = destCenterY + halfH

                val oldAlpha = p.alpha
                if (phase == GamePhase.FALL_SEQUENCE) {
                    p.alpha = (playerAlpha * 255).toInt().coerceIn(0, 255)
                }

                if (bmp != null) {
                    p.isFilterBitmap = false
                    val src = Rect(0, 0, bmp.width, bmp.height)
                    val dst = RectF(destLeft, destTop, destRight, destBottom)
                    canvas.drawBitmap(bmp, src, dst, p)
                } else {
                    // フォールバック：記号（▽ △ ＞ ＜）
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
                    val cx = destLeft + tileW * 0.5f
                    val by = destTop + tileH * 0.72f
                    canvas.drawText(symbol, cx, by, p)
                }

                if (phase == GamePhase.FALL_SEQUENCE) {
                    p.alpha = oldAlpha
                }
            }

            // TODO: GAME OVER 表示を出したい場合はここで描画する
            // if (phase == GamePhase.GAME_OVER) { ... }

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
            restartGame()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (phase == GamePhase.GAME_OVER) {
            restartGame()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun restartGame() {
        world.reset() // ★World側で初期化処理を用意
        reverseScrollOffsetPx = 0f
        fallTimer = 0f
        playerAlpha = 1f

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
    }
}
