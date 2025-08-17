package tk.horiuchi.crazyclimber.audio

import android.content.Context
import android.media.*
import android.os.Handler
import android.os.Looper
import androidx.annotation.RawRes
import tk.horiuchi.crazyclimber.R
import java.util.EnumMap
import kotlin.math.max
import kotlin.math.min

object SoundManager {

    // ====== SFX 定義 ======
    enum class Sfx(@RawRes val resId: Int?) {
        STEP_LEFT     (R.raw.se_step_left),
        STEP_RIGHT    (R.raw.se_step_right),
        HIT           (R.raw.se_hit),
        SHIRAKE_CALL  (R.raw.se_shirake_call),
        BOSS_PUNCH    (R.raw.se_boss_punch),
        ITE           (R.raw.se_ite),
        GAMBARE       (R.raw.se_gambare),
        AREEE         (R.raw.se_areee),
        YOISHO        (R.raw.se_yoisho),
        HELI_LOOP     (R.raw.se_heli),
        PLAY_START    (R.raw.bgm_playstart),
        STAGE_CLEAR   (R.raw.bgm_stageclear),
        FALL          (R.raw.bgm_fall),
        SHIRAKE       (R.raw.bgm_shirake),
        BOSS          (R.raw.bgm_boss),
    }

    // ====== 内部状態 ======
    private var soundPool: SoundPool? = null
    private val soundId   = EnumMap<Sfx, Int>(Sfx::class.java)     // SFX -> soundId
    private val loopStream= EnumMap<Sfx, Int>(Sfx::class.java)     // ループ再生中の streamId

    // resId のロード完了管理と逆引き
    private val resToSoundId = mutableMapOf<Int, Int>()            // resId -> soundId
    private val loadedRes    = mutableSetOf<Int>()                 // ロード完了した resId

    // 未ロード時の再生依頼をキュー
    private val pendingPlay  = mutableMapOf<Sfx, Int>()            // ワンショット回数
    private data class LoopParams(val volume: Float, val rate: Float, val priority: Int)
    private val pendingLoop  = mutableMapOf<Sfx, LoopParams>()     // ループ開始保留

    // Enum から自動生成（null res は除外）
    private val sfxToRes: Map<Sfx, Int> =
        Sfx.entries.mapNotNull { s -> s.resId?.let { s to it } }.toMap()

    // ====== 公開 API ======
    fun init(appContext: Context) {
        if (soundPool != null) return

        soundPool = SoundPool.Builder()
            .setMaxStreams(16)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build().also { sp ->
                sp.setOnLoadCompleteListener { _, loadedSoundId, status ->
                    if (status != 0) return@setOnLoadCompleteListener

                    // どの res が完了したか逆引き
                    val res = resToSoundId.entries.firstOrNull { it.value == loadedSoundId }?.key
                        ?: return@setOnLoadCompleteListener
                    loadedRes += res

                    // 対応する SFX を特定
                    val sfx = sfxToRes.entries.firstOrNull { it.value == res }?.key ?: return@setOnLoadCompleteListener

                    // ワンショット保留を消化
                    val times = pendingPlay.remove(sfx) ?: 0
                    repeat(times) { playNow(sfx, 1f, 1f, 1) }

                    // ループ保留があれば開始
                    pendingLoop.remove(sfx)?.let { lp ->
                        playLoop(sfx, lp.volume, lp.rate, lp.priority)
                    }
                }

                // 一括ロード（soundId をすぐ保持しておく）
                sfxToRes.forEach { (sfx, resId) ->
                    val sid = sp.load(appContext, resId, 1)
                    resToSoundId[resId] = sid
                    soundId[sfx] = sid
                }
            }
    }

    fun release() {
        // ループ停止
        soundPool?.let { sp -> loopStream.values.forEach { sp.stop(it) } }
        loopStream.clear()

        soundPool?.release(); soundPool = null

        soundId.clear()
        resToSoundId.clear()
        loadedRes.clear()
        pendingPlay.clear()
        pendingLoop.clear()

        // BGM を使うならここで release
        bgm?.release(); bgm = null
        bgmDesiredPlaying = false
    }

    fun onResume() {
        soundPool?.autoResume()
        if (bgmDesiredPlaying && bgm?.isPlaying == false) bgm?.start()
    }
    fun onPause() {
        soundPool?.autoPause()
        if (bgm?.isPlaying == true) { bgmWasPlayingBeforePause = true; bgm?.pause() }
        else                         { bgmWasPlayingBeforePause = false }
    }

    // ---- マスター/個別ボリューム & ミュート ----
    fun setMasterVolume(v: Float) { master = v.clamp(); applyVolumes() }
    fun setSfxVolume(v: Float)    { sfxVol = v.clamp(); applyVolumes() }
    fun setBgmVolume(v: Float)    { bgmVol = v.clamp(); applyVolumes() }
    fun setMuted(mute: Boolean)   { muted = mute; applyVolumes() }

    // ---- 効果音（ワンショット）----
    fun play(sfx: Sfx, volume: Float = 1f, rate: Float = 1f, priority: Int = 1) {
        val res = sfx.resId ?: return
        if (loadedRes.contains(res)) {
            playNow(sfx, volume, rate, priority)
        } else {
            // ロード完了後に自動再生
            pendingPlay[sfx] = (pendingPlay[sfx] ?: 0) + 1
        }
    }

    // 実再生（ロード済み前提）
    private fun playNow(sfx: Sfx, volume: Float, rate: Float, priority: Int) {
        val sp = soundPool ?: return
        val id = soundId[sfx] ?: return
        val v  = if (muted) 0f else (master * sfxVol * volume).clamp()
        sp.play(id, v, v, priority, /*loop*/0, rate.coerceIn(0.5f, 2.0f))
    }

    // ---- 効果音（ループ：例）ヘリのローター ----
    fun playLoop(sfx: Sfx, volume: Float = 1f, rate: Float = 1f, priority: Int = 1) {
        val sp = soundPool ?: return
        val res = sfx.resId ?: return
        if (!loadedRes.contains(res)) {
            // ロード完了後にループを開始する
            pendingLoop[sfx] = LoopParams(volume, rate, priority)
            return
        }
        // 既に流れてたら一旦止める
        loopStream.remove(sfx)?.let { sp.stop(it) }

        val id = soundId[sfx] ?: return
        val v  = if (muted) 0f else (master * sfxVol * volume).clamp()
        val stream = sp.play(id, v, v, priority, /*loop*/-1, rate.coerceIn(0.5f, 2.0f))
        loopStream[sfx] = stream
    }

    fun stopLoop(sfx: Sfx) {
        val sp = soundPool ?: return
        pendingLoop.remove(sfx) // 保留も消す
        loopStream.remove(sfx)?.let { sp.stop(it) }
    }

    // ===== BGM（必要なら） =====
    private var bgm: MediaPlayer? = null
    private var bgmDesiredPlaying = false
    private var bgmWasPlayingBeforePause = false

    // ===== ボリューム適用 =====
    private var master = 1f
    private var sfxVol = 1f
    private var bgmVol = 1f
    private var muted  = false

    private val fadeHandler = Handler(Looper.getMainLooper())
    private var fadeRunnable: Runnable? = null

    private fun applyVolumes() {
        // ループ中の SFX にのみ即時反映（単発は play() 時に適用）
        val vSfx = if (muted) 0f else (master * sfxVol).clamp()
        soundPool?.let { sp -> loopStream.forEach { (_, stream) -> sp.setVolume(stream, vSfx, vSfx) } }

        val vBgm = if (muted) 0f else (master * bgmVol).clamp()
        bgm?.setVolume(vBgm, vBgm)
    }

    // ===== ユーティリティ =====
    private fun Float.clamp(minV: Float = 0f, maxV: Float = 1f) = max(minV, min(maxV, this))
}
