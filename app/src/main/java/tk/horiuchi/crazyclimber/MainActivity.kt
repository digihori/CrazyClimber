package tk.horiuchi.crazyclimber.ui

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import tk.horiuchi.crazyclimber.R
import tk.horiuchi.crazyclimber.audio.SoundManager
import tk.horiuchi.crazyclimber.core.*
import tk.horiuchi.crazyclimber.ui.view.GameView
import tk.horiuchi.crazyclimber.ui.view.LeverView

class MainActivity : AppCompatActivity() {

    private lateinit var scoreText: TextView
    private lateinit var gameView: GameView
    private lateinit var leftLever: LeverView
    private lateinit var rightLever: LeverView

    // 右レバーをボタンで操作中かどうか（ABXY押下保持）
    private val rightPressed = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SoundManager.init(applicationContext)
        hideSystemUI()
        supportActionBar?.title = "ClimberClimber"
        setContentView(R.layout.activity_main)

        // ステータスバーを隠す処理を安全に呼ぶ
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(WindowInsets.Type.statusBars())
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN
            }
        }
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        toolbar.overflowIcon?.setTint(Color.WHITE)

        //setContentView(R.layout.activity_main)

        scoreText = findViewById(R.id.scoreText)
        gameView   = findViewById(R.id.gameView)
        leftLever  = findViewById(R.id.leftLever)
        rightLever = findViewById(R.id.rightLever)

        // LeverView → GameView へ入力を流す
        fun pushLeverToInput() {
            gameView.setLeverInput(
                left  = leftLever.direction.toLeverDir(),
                right = rightLever.direction.toLeverDir()
            )
        }
        leftLever.onDirectionChanged  = { pushLeverToInput() }
        rightLever.onDirectionChanged = { pushLeverToInput() }

        // スコア表示更新（GameViewからコール）
        gameView.onHudUpdate = { score, lives, floor ->
            scoreText.text = "SCORE: $score   ♥:$lives   FLOOR: $floor/${Config.FLOORS}"
        }

        // フォーカス（Pad用）
        findViewById<View>(R.id.rootLayout).apply {
            isFocusableInTouchMode = true
            requestFocus()
        }
    }

    private fun hideSystemUI() {
        // フルスクリーン化
        window.decorView.post {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.hide(WindowInsets.Type.systemBars())
                window.insetsController?.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        )
            }
        }
    }
    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_about -> {
                SoundManager.onPause()
                gameView.onPauseView()
                showAboutDialog()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.about_title))
            .setMessage(getString(R.string.about_message))
            .setPositiveButton(getString(R.string.about_ok)) { dialog, _ ->
                dialog.dismiss()
                SoundManager.onResume()
                gameView.onResumeView()  // ダイアログが閉じられたときにゲームを再開
            }
            .setNeutralButton(getString(R.string.about_hyperlink_name)) { _, _ ->
                val url = getString(R.string.about_hyperlink)
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                SoundManager.onResume()
                gameView.onResumeView()
            }
            .setOnCancelListener {
                SoundManager.onResume()
                gameView.onResumeView()  // 戻るボタンなどでもゲームを再開
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        SoundManager.onResume()
        gameView.onResumeView()
        findViewById<View>(R.id.rootLayout).requestFocus()
    }

    override fun onPause() {
        super.onPause()
        SoundManager.onPause()
        gameView.onPauseView()
    }

    override fun onDestroy() {
        SoundManager.release()
        super.onDestroy()
    }

    // ---- ゲームパッド入力（アナログ＆HAT） ----
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val srcOk =
            event.source and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK ||
                    event.source and InputDevice.SOURCE_GAMEPAD  == InputDevice.SOURCE_GAMEPAD  ||
                    event.source and InputDevice.SOURCE_DPAD     == InputDevice.SOURCE_DPAD

        if (srcOk && event.action == MotionEvent.ACTION_MOVE) {
            // 左：X/Y or HAT
            var lx = centeredAxis(event, MotionEvent.AXIS_X)
            var ly = centeredAxis(event, MotionEvent.AXIS_Y)
            val hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            if (hatX != 0f || hatY != 0f) { lx = hatX; ly = hatY }

            // 右：Z/RZ or RX/RY（機種差対応）
            val rz = centeredAxis(event, MotionEvent.AXIS_RZ)
            val z  = centeredAxis(event, MotionEvent.AXIS_Z)
            val rx = centeredAxis(event, MotionEvent.AXIS_RX)
            val ry = centeredAxis(event, MotionEvent.AXIS_RY)
            val (rxF, ryF) = when {
                kotlin.math.abs(z)+kotlin.math.abs(rz)  > 0f -> z to rz
                kotlin.math.abs(rx)+kotlin.math.abs(ry) > 0f -> rx to ry
                else -> 0f to 0f
            }

            // LeverViewに反映
            leftLever.setAnalogVector(lx, ly)
            if (rightPressed.isEmpty()) rightLever.setAnalogVector(rxF, ryF)

            // GameViewに通知
            gameView.setLeverInput(
                left  = leftLever.direction.toLeverDir(),
                right = rightLever.direction.toLeverDir()
            )
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    private fun centeredAxis(event: MotionEvent, axis: Int, dead: Float = 0.2f): Float {
        val range = event.device?.getMotionRange(axis, event.source) ?: return 0f
        var v = event.getAxisValue(axis)
        if (kotlin.math.abs(v) < range.flat) v = 0f
        if (kotlin.math.abs(v) < dead) v = 0f
        return v.coerceIn(-1f, 1f)
    }

    // ---- 十字＆ABXY（右レバー） ----
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            // 左レバー＝DPAD
            KeyEvent.KEYCODE_DPAD_UP    -> leftLever.setAnalogVector(0f, -1f)
            KeyEvent.KEYCODE_DPAD_DOWN  -> leftLever.setAnalogVector(0f,  1f)
            KeyEvent.KEYCODE_DPAD_LEFT  -> leftLever.setAnalogVector(-1f, 0f)
            KeyEvent.KEYCODE_DPAD_RIGHT -> leftLever.setAnalogVector( 1f, 0f)

            // 右レバー＝ABXY
            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_2 -> { rightPressed.add(keyCode); rightLever.setAnalogVector(0f, -1f) }
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_3 -> { rightPressed.add(keyCode); rightLever.setAnalogVector(0f,  1f) }
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_1 -> { rightPressed.add(keyCode); rightLever.setAnalogVector(-1f, 0f) }
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_4 -> { rightPressed.add(keyCode); rightLever.setAnalogVector( 1f, 0f) }

            else -> return super.onKeyDown(keyCode, event)
        }
        gameView.setLeverInput(
            left  = leftLever.direction.toLeverDir(),
            right = rightLever.direction.toLeverDir()
        )
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> leftLever.setAnalogVector(0f, 0f)

            KeyEvent.KEYCODE_BUTTON_Y, KeyEvent.KEYCODE_BUTTON_2,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_3,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_1,
            KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_4 -> {
                rightPressed.remove(keyCode)
                if (rightPressed.isEmpty()) rightLever.setAnalogVector(0f, 0f)
            }
            else -> return super.onKeyUp(keyCode, event)
        }
        gameView.setLeverInput(
            left  = leftLever.direction.toLeverDir(),
            right = rightLever.direction.toLeverDir()
        )
        return true
    }
}

// LeverView.Direction → LeverDir 変換
private fun LeverView.Direction.toLeverDir(): LeverDir = when (this) {
    LeverView.Direction.UP    -> LeverDir.UP
    LeverView.Direction.DOWN  -> LeverDir.DOWN
    LeverView.Direction.LEFT  -> LeverDir.LEFT
    LeverView.Direction.RIGHT -> LeverDir.RIGHT
    else -> LeverDir.CENTER
}
