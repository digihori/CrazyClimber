package tk.horiuchi.crazyclimber.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
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
    private fun loop() {
        val dt = Config.DT_MS.toLong()
        var last = System.currentTimeMillis()
        while (running) {
            val now = System.currentTimeMillis()
            if (now - last >= dt) {
                // 入力 & ロジック
                world.handleInput(lastLeft, lastRight)
                world.update(dt)

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

                last = now
            } else {
                try { Thread.sleep(2) } catch (_: InterruptedException) {}
            }
        }
    }

    // イージング（必要に応じて差し替え）
    private fun easeOutCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)

    // ===== 描画 =====
    private fun drawFrame() {
        val c = holder.lockCanvas() ?: return
        try {
            // 背景
            c.drawColor(Color.rgb(18, 18, 24))

            // タイル寸法
            val tileW = width / Config.COLS
            val tileH = tileW
            val visibleRows = height / tileH + 2

            // ===== カメラ：常に“下から2段目”を狙う（整数） =====
            val targetFromBottom = 1
            val maxTop = (Config.FLOORS - visibleRows).coerceAtLeast(0)
            //val desiredTop = (world.player.pos.floor - targetFromBottom).coerceIn(0, maxTop)
            val desiredTop = (world.player.pos.floor - targetFromBottom).coerceIn(0, maxTop)
            val animatingClimb = climbT < 1f

            // ★上昇アニメ中は「開始時の整数位置」で固定（逆向きに見えるのを防ぐ）
            //val cameraTop =
            //    if (climbT < 1f)
            //        (climbFromFloor - targetFromBottom).coerceIn(0, maxTop)
            //    else
            //        desiredTop
            val cameraTop =
                if (animatingClimb)
                    (climbFromFloor - targetFromBottom).coerceIn(0, maxTop)
                else
                    desiredTop

            // 必要に応じて camTopFloor を追従（ここでは保持しておくが描画には cameraTop を使う）
            camTopFloor = cameraTop

            val paint = Paint()

            // ===== 背景タイル（縦だけ補間） =====
            val k = easeOutCubic(climbT)
            //val climbOffsetPx = (1f - k) * tileH  // 上昇中に下へ流す（0→tileH）
            val climbOffsetPx =
                if (animatingClimb) k * tileH else 0f

            for (r in 0 until visibleRows) {
                val floor = (cameraTop + r).coerceAtMost(Config.FLOORS - 1)
                for (cx in 0 until Config.COLS) {
                    val left = cx * tileW.toFloat()
                    val top  = (height - (r + 1) * tileH + climbOffsetPx)

                    // 充填
                    paint.style = Paint.Style.FILL
                    paint.color = when (world.getWindow(Cell(cx, floor))) {
                        WindowState.OPEN   -> Color.rgb(60, 180, 255)
                        WindowState.HALF   -> Color.rgb(40, 120, 180)
                        WindowState.CLOSED -> Color.rgb(20, 60, 90)
                    }
                    c.drawRect(left + 4f, top + 4f, left + tileW - 4f, top + tileH - 4f, paint)

                    // 枠線
                    paint.style = Paint.Style.STROKE
                    paint.color = Color.BLACK
                    paint.strokeWidth = 3f
                    c.drawRect(left + 4f, top + 4f, left + tileW - 4f, top + tileH - 4f, paint)
                }
            }

            // ===== プレイヤー（下から2段目固定 / 横だけ補間） =====
            val s = easeOutCubic(shiftT)
            val pose = world.player.pose
            val bmp  = Assets.getPlayerBitmap(pose)

            val fyFixed = 1 // 常に下から2段目
            val baseTop  = (height - (fyFixed + 1) * tileH).toFloat()

            // 向きが狂わない絶対式：from→to の線形補間
            val drawColF = shiftFromCol * (1f - s) + shiftToCol * s
            val destLeft = drawColF * tileW
            val destTop  = baseTop
            val destRight  = destLeft + tileW
            val destBottom = destTop  + tileH

            if (bmp != null) {
                paint.isFilterBitmap = false // ドット感
                val src = Rect(0, 0, bmp.width, bmp.height)
                val dst = RectF(destLeft, destTop, destRight, destBottom)
                c.drawBitmap(bmp, src, dst, paint)
            } else {
                // 記号描画（▽ △ ＞ ＜）
                val symbol = when (pose) {
                    PlayerPose.BOTH_UP   -> "▽"
                    PlayerPose.BOTH_DOWN -> "△"
                    PlayerPose.LUP_RDOWN -> "＞"
                    PlayerPose.LDOWN_RUP -> "＜"
                }
                paint.reset()
                paint.isAntiAlias = true
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.MONOSPACE
                paint.textSize = tileH * 0.8f
                paint.color = Color.WHITE
                paint.setShadowLayer(4f, 0f, 0f, Color.BLACK)
                val cx = destLeft + tileW * 0.5f
                val by = destTop + tileH * 0.72f
                c.drawText(symbol, cx, by, paint)
            }

        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }

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
