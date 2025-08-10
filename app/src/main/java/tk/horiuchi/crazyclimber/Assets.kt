package tk.horiuchi.crazyclimber.ui.assets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import tk.horiuchi.crazyclimber.core.PlayerPose

object Assets {
    private val playerBitmaps = mutableMapOf<PlayerPose, Bitmap?>()
    private var inited = false

    // 画像ファイル名（拡張子なし・res/drawable/ に置く）
    private val poseNames = mapOf(
        PlayerPose.BOTH_UP   to "player_both_up",
        PlayerPose.BOTH_DOWN to "player_both_down",
        PlayerPose.LUP_RDOWN to "player_lup_rdown",
        PlayerPose.LDOWN_RUP to "player_ldown_rup"
    )

    fun init(context: Context) {
        if (inited) return
        poseNames.forEach { (pose, name) ->
            playerBitmaps[pose] = loadIfExists(context, name)
        }
        inited = true
    }

    fun getPlayerBitmap(pose: PlayerPose): Bitmap? = playerBitmaps[pose]

    fun release() {
        playerBitmaps.values.forEach { it?.recycle() }
        playerBitmaps.clear()
        inited = false
    }

    private fun loadIfExists(ctx: Context, nameNoExt: String): Bitmap? {
        // 画像が無い場合は 0 が返る → null を返してフォールバックさせる
        val resId = ctx.resources.getIdentifier(nameNoExt, "drawable", ctx.packageName)
        if (resId == 0) return null
        return try {
            BitmapFactory.decodeResource(ctx.resources, resId)
        } catch (_: Exception) {
            null
        }
    }
}
