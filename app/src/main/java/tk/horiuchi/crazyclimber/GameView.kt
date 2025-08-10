package tk.horiuchi.crazyclimber.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView
import tk.horiuchi.crazyclimber.core.*

import tk.horiuchi.crazyclimber.ui.assets.Assets
import tk.horiuchi.crazyclimber.core.PlayerPose
import tk.horiuchi.crazyclimber.core.LeverDir


class GameView(context: Context, attrs: AttributeSet?) : SurfaceView(context, attrs), SurfaceHolder.Callback {

    private val world = World()
    private var running = false
    private var loopThread: Thread? = null

    // HUD更新コールバック
    var onHudUpdate: ((score: Int, lives: Int, floor: Int) -> Unit)? = null

    // 直近のレバー入力（毎フレームWorldに渡す）
    @Volatile private var lastLeft: LeverDir = LeverDir.CENTER
    @Volatile private var lastRight: LeverDir = LeverDir.CENTER

    init { holder.addCallback(this) }

    private fun currentPose(left: LeverDir, right: LeverDir): PlayerPose =
        when {
            left == LeverDir.UP   && right == LeverDir.UP   -> PlayerPose.BOTH_UP
            left == LeverDir.DOWN && right == LeverDir.DOWN -> PlayerPose.BOTH_DOWN
            left == LeverDir.UP   && right == LeverDir.DOWN -> PlayerPose.LUP_RDOWN
            left == LeverDir.DOWN && right == LeverDir.UP   -> PlayerPose.LDOWN_RUP
            // それ以外（左右/CENTER混在）は、直近の安定側に寄せても良いが、まずは両下に寄せておく
            else -> PlayerPose.BOTH_DOWN
        }

    fun setLeverInput(left: LeverDir, right: LeverDir) {
        lastLeft = left
        lastRight = right
    }

    fun onResumeView() { startLoop() }
    fun onPauseView() { stopLoop() }

    private fun startLoop() {
        if (running) return
        running = true
        loopThread = Thread { loop() }.also { it.start() }
    }

    private fun stopLoop() {
        running = false
        loopThread?.join(200)
        loopThread = null
    }

    private fun loop() {
        val dt = Config.DT_MS.toLong()
        var last = System.currentTimeMillis()
        while (running) {
            val now = System.currentTimeMillis()
            if (now - last >= dt) {
                // 入力適用→更新
                world.handleInput(lastLeft, lastRight)
                world.update(dt)
                drawFrame()
                onHudUpdate?.invoke(world.player.score, world.player.lives, world.player.pos.floor)
                last = now
            } else {
                try { Thread.sleep(2) } catch (_: InterruptedException) {}
            }
        }
    }

    private var camTopFloor: Int = 0
    private fun drawFrame() {
        val c = holder.lockCanvas() ?: return
        try {
            // 背景
            c.drawColor(Color.rgb(18, 18, 24))

            // タイル寸法
            val tileW = width / Config.COLS
            val tileH = tileW // 正方形
            val visibleRows = height / tileH + 2

            // ===== カメラ：最初は最下段、以後は「下から2段目」を維持（上方向のみ追従） =====
            val targetFromBottom = 1 // 0=最下段, 1=下から2段目
            val maxTop = (Config.FLOORS - visibleRows).coerceAtLeast(0)
            val desiredTop = (world.player.pos.floor - targetFromBottom).coerceIn(0, maxTop)
            camTopFloor = camTopFloor.coerceAtLeast(desiredTop).coerceAtMost(maxTop)
            val cameraFloorTop = camTopFloor

            val p = Paint()

            // ===== 窓（矩形プレースホルダ）を描画 =====
            for (r in 0 until visibleRows) {
                val floor = (cameraFloorTop + r).coerceAtMost(Config.FLOORS - 1)
                for (cIdx in 0 until Config.COLS) {
                    val st = worldRunSafe { world.getWindow(Cell(cIdx, floor)) } ?: WindowState.OPEN
                    val left = cIdx * tileW.toFloat()
                    val top  = (height - (r + 1) * tileH).toFloat()

                    // 充填
                    p.style = Paint.Style.FILL
                    p.color = when (st) {
                        WindowState.OPEN   -> Color.rgb(60, 180, 255)
                        WindowState.HALF   -> Color.rgb(40, 120, 180)
                        WindowState.CLOSED -> Color.rgb(20, 60, 90)
                    }
                    c.drawRect(left + 4, top + 4, left + tileW - 4f, top + tileH - 4f, p)

                    // 枠線
                    p.style = Paint.Style.STROKE
                    p.color = Color.BLACK
                    p.strokeWidth = 3f
                    c.drawRect(left + 4, top + 4, left + tileW - 4f, top + tileH - 4f, p)
                }
            }

            // ===== プレイヤー：画像があれば画像、無ければ記号（▽△＞＜） =====
            fun currentPose(left: LeverDir, right: LeverDir): PlayerPose =
                when {
                    left == LeverDir.UP   && right == LeverDir.UP   -> PlayerPose.BOTH_UP
                    left == LeverDir.DOWN && right == LeverDir.DOWN -> PlayerPose.BOTH_DOWN
                    left == LeverDir.UP   && right == LeverDir.DOWN -> PlayerPose.LUP_RDOWN
                    left == LeverDir.DOWN && right == LeverDir.UP   -> PlayerPose.LDOWN_RUP
                    else -> PlayerPose.BOTH_DOWN
                }

            //val pose = currentPose(lastLeft, lastRight)
            val pose = world.player.pose
            val bmp = tk.horiuchi.crazyclimber.ui.assets.Assets.getPlayerBitmap(pose)

            // タイル→画面座標
            val col = world.player.pos.col
            val fy  = world.player.pos.floor - cameraFloorTop
            val destLeft   = col * tileW.toFloat()
            val destTop    = (height - (fy + 1) * tileH).toFloat()
            val destRight  = destLeft + tileW
            val destBottom = destTop  + tileH

            if (bmp != null) {
                // ピクセルアート向け：補間オフ
                p.isFilterBitmap = false
                val src = Rect(0, 0, bmp.width, bmp.height)
                val dst = RectF(destLeft, destTop, destRight, destBottom)
                c.drawBitmap(bmp, src, dst, p)
            } else {
                // フォールバック：記号描画
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
                val centerX = destLeft + tileW * 0.5f
                val baseY   = destTop  + tileH * 0.72f // ベースライン調整
                c.drawText(symbol, centerX, baseY, p)
            }

        } finally {
            holder.unlockCanvasAndPost(c)
        }
    }



    // Worldアクセス時の保護（描画と更新の競合を避けるための簡易ラッパ）
    private inline fun <T> worldRunSafe(block: () -> T): T = block()

    // SurfaceHolder.Callback
    override fun surfaceCreated(holder: SurfaceHolder) {
        Assets.init(context)
        startLoop()
    }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { stopLoop() }
}
