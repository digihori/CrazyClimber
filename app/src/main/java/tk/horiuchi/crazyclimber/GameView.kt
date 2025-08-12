package tk.horiuchi.crazyclimber.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import tk.horiuchi.crazyclimber.core.*
import tk.horiuchi.crazyclimber.ui.assets.Assets

class GameView(context: Context, attrs: AttributeSet?) :
    SurfaceView(context, attrs), SurfaceHolder.Callback {

    // ===== ゲームロジック =====
    private val world = World()

    // ===== ループ制御 =====
    private var running = false
    private var loopThread: Thread? = null

    // HUD更新（SCORE / LIVES / FLOOR）
    var onHudUpdate: ((score: Int, lives: Int, floor: Int) -> Unit)? = null

    // ===== 入力（Activity から渡す） =====
    @Volatile private var lastLeft: LeverDir = LeverDir.CENTER
    @Volatile private var lastRight: LeverDir = LeverDir.CENTER
    fun setLeverInput(left: LeverDir, right: LeverDir) {
        lastLeft = left
        lastRight = right
    }

    // ===== カメラ（整数の最上段階） =====
    private var camTopFloor: Int = 0  // drawFrame 内で使う見た目の“床”基準

    // ===== 横アニメ（プレイヤーのみX補間） =====
    private var shiftFromCol = 0
    private var shiftToCol   = 0
    private var shiftT       = 1f      // 0→1
    private val shiftMs      = 220L

    // ===== 縦アニメ（背景のみY補間） =====
    private var climbFromFloor = 0
    private var climbToFloor   = 0
    private var climbT         = 1f     // 0→1
    private val climbMs        = 280L

    // ===== 差分検出用の直前“コミット済み”論理位置 =====
    private var prevCol   = 0
    private var prevFloor = 0

    // 被弾の震え演出
    private var playerShakeMs: Long = 0L
    private val playerShakeTotal: Long = 180L

    // 簡易落下演出（背景を一気に下へ流す）
    private var fallingFxT = 1f    // 0→1
    private val fallingMs = 350L


    init { holder.addCallback(this) }

    // ===== ライフサイクル =====
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

    // ===== メインループ =====
    // 足バタ演出
    private var lastPose: PlayerPose = PlayerPose.BOTH_DOWN
    private var footFxT = 1f                 // 0→1、1で非表示
    private val footFxMs = 200L              // 1コマ〜100ms程度

    private fun loop() {
        val dt = Config.DT_MS.toLong()
        var last = System.currentTimeMillis()
        while (running) {
            val now = System.currentTimeMillis()
            if (now - last >= dt) {
                // 入力 & ロジック
                world.handleInput(lastLeft, lastRight)
                world.update(dt)

                val curPose = world.player.pose
                if (curPose != lastPose) {
                    if (curPose == PlayerPose.LUP_RDOWN || curPose == PlayerPose.LDOWN_RUP) {
                        footFxT = 0f   // 不安定ポーズに入った瞬間、足バタ開始（1回だけ）
                    }
                    lastPose = curPose
                }

                // 足バタ進行
                if (footFxT < 1f) {
                    footFxT = (footFxT + dt / footFxMs.toFloat()).coerceAtMost(1f)
                }


                // --- 差分検出（横：ターゲットと prevCol が異なるなら1ステップだけ開始） ---
                val p = world.player.pos
                if (shiftT >= 1f && p.col != prevCol) {
                    val step = if (p.col > prevCol) +1 else -1
                    shiftFromCol = prevCol
                    shiftToCol   = prevCol + step
                    shiftT = 0f
                    // prevCol は完了時にのみ進める（ここでは更新しない）
                }

                // --- 差分検出（縦：ターゲットが大きければ1段だけ開始） ---
                if (climbT >= 1f && p.floor > prevFloor) {
                    climbFromFloor = prevFloor
                    climbToFloor   = prevFloor + 1
                    climbT = 0f
                    // prevFloor も完了時にのみ進める
                }

                // --- 進行（完了時にだけコミット） ---
                if (shiftT < 1f) {
                    shiftT = (shiftT + dt / shiftMs.toFloat()).coerceAtMost(1f)
                    if (shiftT >= 1f) {
                        prevCol = shiftToCol
                    }
                }
                if (climbT < 1f) {
                    climbT = (climbT + dt / climbMs.toFloat()).coerceAtMost(1f)
                    if (climbT >= 1f) {
                        prevFloor = climbToFloor
                    }
                }

                drawFrame()
                onHudUpdate?.invoke(world.player.score, world.player.lives, world.player.pos.floor)

                // World 側の状態に応じて演出起動
                // （安定→片手に崩れた “直後” を検知するには、World からフラグを渡すのが一番だが、
                //  まずは鉢ヒット時に playerShakeMs をセットするために onPlayerHitByPot 内で
                //  画面側へ通知できないので、当面はここで簡易に：）
                if (playerShakeMs > 0L) {
                    playerShakeMs -= dt
                    if (playerShakeMs < 0L) playerShakeMs = 0L
                }

                // 落下中の簡易スクロール
                if (fallingFxT < 1f) {
                    fallingFxT = (fallingFxT + dt / fallingMs.toFloat()).coerceAtMost(1f)
                }


                last = now
            } else {
                try { Thread.sleep(2) } catch (_: InterruptedException) {}
            }
        }
    }

    // イージング（必要に応じて差し替え）
    //private fun easeOutCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    // ===== 描画 =====
    private fun drawFrame() {
        val c = holder.lockCanvas() ?: return
        try {
            // ========= 画面・タイル寸法 =========
            val cols = Config.COLS
            val tileW = width / cols
            val tileH = tileW
            val visibleRows = height / tileH + 2

            // ========= 配色（画像の雰囲気に合わせたサンプル） =========
            val facadeColor  = Color.rgb(255, 248, 170) // 外壁クリーム
            val mullionColor = Color.rgb(150,   0,   0) // 赤い縦柱
            val winOpen      = Color.rgb(  6,   8,  12) // 閉：黒
            val winClosed    = Color.rgb( 58, 220, 255) // 開：明るい水色
            val winHalf      = Color.rgb( 30,  80, 120) // 半開

            // ========= 3列フロア判定＆描画補助（ローカル関数） =========
            fun isThreeColFloor(floor: Int): Boolean {
                // 例：20～27階だけ3列にする（自由に変えてOK）
                //return floor in 20..27
                return false
            }
            fun colVisibleMaskFor(floor: Int): BooleanArray {
                return if (isThreeColFloor(floor)) {
                    booleanArrayOf(true, false, true, false, true) // 端・中央・端のみ可視
                } else {
                    booleanArrayOf(true, true, true, true, true)
                }
            }
            fun drawFacadeStripes(canvas: Canvas) {
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = mullionColor }
                val barW = tileW * 0.18f
                for (i in 0..cols) {
                    val x = i * tileW.toFloat()
                    canvas.drawRect(x - barW * 0.5f, 0f, x + barW * 0.5f, height.toFloat(), p)
                }
            }
            fun drawSidePanelsIfNeeded(
                canvas: Canvas, floor: Int, top: Float, tileH: Int
            ) {
                if (!isThreeColFloor(floor)) return
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = winOpen }
                val panelW = tileW * 0.95f
                // 左サイド
                canvas.drawRect(0f, top, panelW, top + tileH, p)
                // 右サイド
                canvas.drawRect(width - panelW, top, width.toFloat(), top + tileH, p)
            }

            // ========= 背景（外壁一色） =========
            c.drawColor(facadeColor)

            // ========= カメラ：常に“下から2段目”を狙う（整数） =========
            val targetFromBottom = 1
            val maxTop = (Config.FLOORS - visibleRows).coerceAtLeast(0)
            val desiredTop =
                (world.player.pos.floor - targetFromBottom).coerceIn(0, maxTop)

            // 上昇アニメ中は「開始時の整数カメラ」で固定、完了後に desiredTop へ
            val animatingClimb = climbT < 1f
            val cameraTop =
                if (animatingClimb)
                    (climbFromFloor - targetFromBottom).coerceIn(0, maxTop)
                else
                    desiredTop
            camTopFloor = cameraTop //（保持用。描画は cameraTop を使う）

            // ========= 補間量（縦：背景のみ／横：プレイヤーのみ） =========
            fun easeOutCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
            val k = easeOutCubic(climbT)               // 0→1
            val climbOffsetPx = if (animatingClimb) k * tileH else 0f

            val s = easeOutCubic(shiftT)               // 0→1
            val drawColF = shiftFromCol * (1f - s) + shiftToCol * s // from→to のlerp

            val p = Paint()

            // ========= 先に赤い縦柱（外観の縦モールディング） =========
            drawFacadeStripes(c)

            // ========= 窓（or 壁）タイルを描画（背景だけ縦補間） =========
            for (r in 0 until visibleRows) {
                val floor = (cameraTop + r).coerceAtMost(Config.FLOORS - 1)
                val mask = colVisibleMaskFor(floor)

                val top = (height - (r + 1) * tileH + climbOffsetPx)
                val windowScale = 0.7f // 窓の比率（80%）
                val winW = tileW * windowScale
                val winH = tileH * windowScale
                val offsetX = (tileW - winW) / 2f
                val offsetY = (tileH - winH) / 2f

                for (col in 0 until cols) {
                    val left = col * tileW.toFloat()

                    p.style = Paint.Style.FILL
                    if (mask[col]) {
                        // 可視列：窓の開閉で色分け
                        p.color = when (world.getWindow(Cell(col, floor))) {
                            WindowState.OPEN   -> winOpen
                            WindowState.HALF   -> winHalf
                            WindowState.CLOSED -> winClosed
                        }
                    } else {
                        // 非表示列：壁として外壁色で塗る
                        p.color = facadeColor
                    }

                    c.drawRect(
                        left + offsetX,
                        top + offsetY,
                        left + offsetX + winW,
                        top + offsetY + winH,
                        p
                    )

                    //c.drawRect(left + 4f, top + 4f, left + tileW - 4f, top + tileH - 4f, p)
                }

                // 3列階なら左右の水色サイドパネル（任意）
                drawSidePanelsIfNeeded(c, floor, top.toFloat(), tileH)
            }


            // ===== おじさん描画 =====
            // ===== おじさん描画（DONEは非表示、窓内クリップ、下辺ベース） =====
            run {
                val bmp = Assets.getOjisanBitmap()
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY }

                val windowScale = 0.7f                 // ← 窓と同じ値に合わせる
                val winW = tileW * windowScale
                val winH = tileH * windowScale
                val offX = (tileW - winW) / 2f
                val offY = (tileH - winH) / 2f

                for (o in world.ojisans) {
                    if (o.state == Ojisan.State.DONE) continue   // ★ DONE は描かない

                    val r = (o.floor - cameraTop)
                    if (r !in 0 until visibleRows) continue

                    val tileLeft = o.col * tileW.toFloat()
                    val tileTop  = height - (r + 1) * tileH + climbOffsetPx

                    val winLeft   = tileLeft + offX
                    val winTop    = tileTop  + offY
                    val winRight  = winLeft  + winW
                    val winBottom = winTop   + winH
                    val cx = (winLeft + winRight) * 0.5f

                    // 少し大きく見せる（係数は好みで）
                    val boxW = winW * 0.90f
                    val boxH = winH * 0.86f

                    val (w, h) = if (bmp != null) {
                        val s = minOf(boxW / bmp.width, boxH / bmp.height)
                        bmp.width * s to bmp.height * s
                    } else {
                        boxW to boxH
                    }

                    val t = o.t.coerceIn(0f, 1f)
                    val eased = 1f - (1f - t) * (1f - t) * (1f - t)  // easeOutCubic

                    // 状態ごとの yBottom（下辺基準）。else は作らない（＝描かない）
                    val yBottom = when (o.state) {
                        Ojisan.State.SLIDE_IN  -> winBottom + h * (1f - eased)  // 下→上へ
                        Ojisan.State.THROW     -> winBottom                      // 下辺に揃える
                        Ojisan.State.SLIDE_OUT -> winBottom + h * eased          // 上→下へ
                        Ojisan.State.DONE      -> Float.NaN                      // ここには来ないが保険
                    }
                    if (yBottom.isNaN()) continue
                    val yTop = yBottom - h

                    // 完全に窓外なら描かない（境界にじみ対策）
                    if (yBottom <= winTop || yTop >= winBottom) continue

                    // クリップの下端を少しだけ内側にして境界ラインを切る
                    val clipBottom = winBottom - 0.5f

                    c.save()
                    c.clipRect(winLeft, winTop, winRight, clipBottom)

                    if (bmp != null) {
                        paint.isFilterBitmap = false
                        val src = Rect(0, 0, bmp.width, bmp.height)
                        val dst = RectF(cx - w / 2f, yTop, cx + w / 2f, yBottom)
                        c.drawBitmap(bmp, src, dst, paint)
                    } else {
                        c.drawRect(cx - w / 2f, yTop, cx + w / 2f, yBottom, paint)
                    }

                    c.restore()
                }
            }


            // ===== 植木鉢描画 =====
            run {
                val bmp = Assets.getPotBitmap()
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 60, 30) } // フォールバック
                for (pot in world.pots) {
                    val r = (pot.yFloor - cameraTop)
                    val top = height - ((r + 1f) * tileH) + climbOffsetPx
                    val left = pot.col * tileW.toFloat()
                    val cx = left + tileW * 0.5f

                    if (bmp != null) {
                        val box = tileW * 0.8f // 鉢の見かけサイズ
                        val scale = minOf(box / bmp.width, box / bmp.height)
                        val w = bmp.width * scale
                        val h = bmp.height * scale
                        p.isFilterBitmap = false
                        val src = Rect(0, 0, bmp.width, bmp.height)
                        val dst = RectF(cx - w/2f, top, cx + w/2f, top + h)
                        c.drawBitmap(bmp, src, dst, p)
                    } else {
                        val size = tileW * 0.35f
                        c.drawRect(cx - size/2f, top, cx + size/2f, top + size, p)
                    }
                }
            }


            // ========= プレイヤー（下から2段目固定／横だけ補間） =========
            val pose = world.player.pose

            // 足バタを使うか？
            val useFoot = (pose == PlayerPose.LUP_RDOWN || pose == PlayerPose.LDOWN_RUP) && footFxT < 1f

            // 差分画像（あれば）を優先
            val bmp = if (useFoot) {
                Assets.getPlayerFootBitmap(pose, context) ?: Assets.getPlayerBitmap(pose)
            } else {
                Assets.getPlayerBitmap(pose)
            }

            val fyFixed = 1
            val baseTop  = (height - (fyFixed + 1) * tileH).toFloat()

            val scale = 1.6f // 拡大率

            val destCenterX = drawColF * tileW + tileW / 2f
            val destCenterY = baseTop + tileH / 2f

            val halfW = tileW * scale / 2f
            val halfH = tileH * scale / 2f

            var shakeX = 0f
            var shakeY = 0f
            if (world.player.stability == Stability.STABLE && playerShakeMs > 0L) {
                // 両手状態で被弾直後のビリビリ
                val amp = 3f
                shakeX = (-amp..amp).random()
                shakeY = (-amp..amp).random()
            }

            val destLeft   = destCenterX - halfW + shakeX
            val destTop    = destCenterY - halfH + shakeY
            val destRight  = destCenterX + halfW
            val destBottom = destCenterY + halfH


            if (bmp != null) {
                p.isFilterBitmap = false // ドット感キープ
                val src = Rect(0, 0, bmp.width, bmp.height)
                val dst = RectF(destLeft, destTop, destRight, destBottom)
                c.drawBitmap(bmp, src, dst, p)
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
                c.drawText(symbol, cx, by, p)
            }

        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

    fun ClosedFloatingPointRange<Float>.random() =
        (start + Math.random().toFloat() * (endInclusive - start))


    // ===== SurfaceHolder.Callback =====
    override fun surfaceCreated(holder: SurfaceHolder) {
        Assets.init(context)

        // 現在の論理位置で初期化
        prevCol   = world.player.pos.col
        prevFloor = world.player.pos.floor

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
}
