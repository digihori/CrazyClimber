package tk.horiuchi.crazyclimber.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

class LeverView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    enum class Direction { CENTER, UP, DOWN, LEFT, RIGHT }

    // 追加：アナログ入力ベクトル（-1.0〜+1.0想定）
    private var vecX = 0f
    private var vecY = 0f

    var direction: Direction = Direction.CENTER
        private set
    var onDirectionChanged: ((Direction) -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private val swipeThresholdPx = 50
    private val deadZone = 0.2f  // アナログ用のデッドゾーン

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // 背景（ベース）
        paint.color = Color.DKGRAY
        canvas.drawCircle(width/2f, height/2f, width/2.5f, paint)

        // ノブ：ベクトルに応じて中心からオフセット表示
        val radius = width / 2.8f
        val knobR = width / 10f
        val cx = width / 2f + vecX * radius
        val cy = height / 2f + vecY * radius
        paint.color = Color.LTGRAY
        canvas.drawCircle(cx, cy, knobR, paint)

        // 文字（デバッグ）
        paint.color = Color.WHITE
        paint.textSize = 36f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(direction.name, width/2f, height/2f + radius + 48f, paint)
    }

    // 方向判定（共通）
    private fun updateDirectionFromVector(x: Float, y: Float) {
        val nx = if (kotlin.math.abs(x) < deadZone) 0f else x
        val ny = if (kotlin.math.abs(y) < deadZone) 0f else y
        val newDir = when {
            kotlin.math.abs(nx) < 1e-4 && kotlin.math.abs(ny) < 1e-4 -> Direction.CENTER
            kotlin.math.abs(nx) >= kotlin.math.abs(ny) && nx > 0 -> Direction.RIGHT
            kotlin.math.abs(nx) >= kotlin.math.abs(ny) && nx < 0 -> Direction.LEFT
            kotlin.math.abs(ny) >  kotlin.math.abs(nx) && ny > 0 -> Direction.DOWN
            else -> Direction.UP
        }
        if (newDir != direction) {
            direction = newDir
            onDirectionChanged?.invoke(direction)
        }
    }

    /** 外部（ゲームパッド等）からベクトルを与える */
    fun setAnalogVector(x: Float, y: Float) {
        vecX = x.coerceIn(-1f, 1f)
        vecY = y.coerceIn(-1f, 1f)
        updateDirectionFromVector(vecX, vecY)
        invalidate()
    }

    // 既存：タッチスワイプでも動く
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> { startX = event.x; startY = event.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - startX
                val dy = event.y - startY
                val newVecX = when {
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx >  swipeThresholdPx ->  1f
                    kotlin.math.abs(dx) > kotlin.math.abs(dy) && dx < -swipeThresholdPx -> -1f
                    else -> 0f
                }
                val newVecY = when {
                    kotlin.math.abs(dy) > kotlin.math.abs(dx) && dy >  swipeThresholdPx ->  1f
                    kotlin.math.abs(dy) > kotlin.math.abs(dx) && dy < -swipeThresholdPx -> -1f
                    else -> 0f
                }
                setAnalogVector(newVecX, newVecY)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> setAnalogVector(0f, 0f)
        }
        return true
    }

}
