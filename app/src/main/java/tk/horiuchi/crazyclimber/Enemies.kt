package tk.horiuchi.crazyclimber.core

import kotlin.math.max
import kotlin.random.Random

/** おじさん（窓の下辺からスライドIN→鉢落とし→スライドOUT） */
data class Ojisan(
    val col: Int,
    val floor: Int,
    var state: State = State.SLIDE_IN,
    var t: Float = 0f,                  // 0→1 アニメ進行
    val lifeMs: Long = 1400L,          // 全行程の目安
    var elapsedMs: Long = 0L
) {
    enum class State { SLIDE_IN, THROW, SLIDE_OUT, DONE }
}

/** 植木鉢：列と“床座標（float）”で落下。1回だけ横にバウンド可 */
data class Pot(
    var col: Int,
    var yFloor: Float,                  // 上から見て floor 値で落下（毎秒 fps 換算ではなく論理時間）
    var bounced: Boolean = false,       // 1回だけ横に逃がす
    val speedFloorsPerSec: Float = 6.0f // 落下速度：1秒に6フロア分くらい
)
