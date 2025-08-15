package tk.horiuchi.crazyclimber.ui.view

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.util.Log
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
    private var reverseScrollTime = 2.0f      // ビル逆スクロール時間
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
        while (running) {
            val now = System.currentTimeMillis()
            if (now - last >= dtMs) {

                val dtSec = dtMs / 1000f
                // 入力＆ロジック：PLAYING のときだけ動かす
                if (phase == GamePhase.PLAYING) {
                    world.update(dtMs)
                    world.handleInput(lastLeft, lastRight)
                }

                // 被弾ワンショットを確認（不安定時のみ即落下）
                if (phase == GamePhase.PLAYING) {
                    val wasHit = world.consumeHitFlag() // ← World側で実装（1フレームだけtrue）
                    if (wasHit /*&& world.player.stability == Stability.UNSTABLE*/) {
                        // UNSTABLEはWorld側でpodの判定をしているのでここでは不要
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

                if (phase == GamePhase.PLAYING && dropT >= 1f) {
                    val p = world.player.pos
                    // World側で BOTH_DOWN 被弾時に floor を 1 下げている前提
                    if (p.floor < prevFloor) {
                        dropFromFloor = prevFloor
                        dropToFloor   = p.floor           // たぶん prevFloor-1
                        dropT = 0f                        // 降下アニメ開始
                    }
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

                    // ▼ 既存の shift/climb 進行に続けて
                    if (dropT < 1f) {
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

            // ========= 配色 =========
            val facadeColor  = Color.rgb(255, 248, 170) // 外壁クリーム
            val mullionColor = Color.rgb(150,   0,   0) // 赤い縦柱
            val winOpen      = Color.rgb(  6,   8,  12) // 閉：黒
            val winClosed    = Color.rgb( 58, 220, 255) // 開：明るい水色
            //val winHalf      = Color.rgb( 30,  80, 120) // 半開

            // ========= 背景クリア =========
            canvas.drawColor(facadeColor)

            // ========= カメラ：下から2段目を狙う（整数） =========
            val targetFromBottom = 1
            val maxTop = (Config.FLOORS - visibleRows).coerceAtLeast(0)
            val desiredTop = (world.player.pos.floor - targetFromBottom).coerceIn(0, maxTop)
            val animatingClimb = climbT < 1f
            val animatingDrop  = dropT  < 1f
            val cameraTopBase =
                if (animatingClimb)
                    (climbFromFloor - targetFromBottom).coerceIn(0, maxTop)
                else if (animatingDrop)
                    (dropFromFloor  - targetFromBottom).coerceIn(0, maxTop)
                else
                    desiredTop
            camTopFloor = cameraTopBase

            // ========= 補間量算出 =========
            //val s = easeOutCubic(shiftT)
            //val drawColF = shiftFromCol * (1f - s) + shiftToCol * s


            // ここまでに cameraTop, climbT, tileW/tileH/visibleRows はある前提

            // ★ 逆スクロールを行数と端数pxに分解
            val revRows = if (phase == GamePhase.FALL_SEQUENCE)
                (reverseScrollOffsetPx / tileH).toInt()
            else 0

            val revPx = if (phase == GamePhase.FALL_SEQUENCE)
                (reverseScrollOffsetPx - revRows * tileH)
            else 0f

            // ★ 有効カメラ：下に revRows 階ぶんズラす（＝建物が下へ流れて見える）
            val cameraTopEffective = (cameraTopBase + revRows).coerceIn(0, maxTop)
            val bossView = world.getBossView(cameraTopEffective, visibleRows)
            val centerCol = Config.COLS / 2

            fun easeOutCubic(t: Float) = 1f - (1f - t) * (1f - t) * (1f - t)
            val kUp = easeOutCubic(climbT)
            val climbOffsetPxBase = if (animatingClimb) kUp * tileH else 0f
            val kDown = easeOutCubic(dropT)
            val dropOffsetPx = if (animatingDrop) (-kDown * tileH) else 0f  // ★ 降下は“上方向”に1段ぶん移動

            // 最終オフセット（逆スクロール分の端数は従来どおり引く）
            val climbOffsetPx = climbOffsetPxBase + dropOffsetPx - revPx


            // ========= 逆スクロール適用ブロック（背景・障害物のみ） =========
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

            world.setVisibleRange(cameraTopEffective, visibleRows)

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
                    val top = (height - (r + 1) * tileH + climbOffsetPx)

                    // この行が“ボス露出許可”内か？
                    val bossRowVisible = bossView?.let {
                        floor in it.bottom .. (it.bottom + it.reveal - 1)
                    } ?: false
                    val bossLeft  = bossView?.leftCol ?: -999
                    val bossRight = if (bossView != null) bossLeft + bossView.width - 1 else -999

                    val mask = colVisibleMaskFor(floor)
                    for (col in 0 until cols) {
                        val left = col * tileW.toFloat()

                        // ★ボス領域露出中
                        if (bossRowVisible && col in bossLeft..bossRight) {
                            if (col == centerCol) {
                                // 中央列：常時OPEN（黒窓だけ）
                                p.style = Paint.Style.FILL
                                p.color = winOpen
                                canvas.drawRect(left + offsetX, top + offsetY, left + offsetX + winW, top + offsetY + winH, p)
                            } else {
                                // サイド2列：単色で埋めて“占有”
                                p.style = Paint.Style.FILL
                                p.color = Color.rgb(220, 220, 220) // 好きな色に
                                canvas.drawRect(left + offsetX, top + offsetY, left + offsetX + winW, top + offsetY + winH, p)
                            }
                            continue
                        }

                        // 以降は通常の窓描画（あなたの既存コードそのまま）
                        if (!mask[col]) { /* …既存の非表示列処理… */ }

                        // 1) 黒い窓枠
                        p.style = Paint.Style.FILL
                        p.color = winOpen
                        canvas.drawRect(left + offsetX, top + offsetY, left + offsetX + winW, top + offsetY + winH, p)

                        // 2) 水色パネル（閉進捗）
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
                val bv = world.getBossView(cameraTopEffective, visibleRows) ?: return@run
                val rows = bv.reveal                    // 0..4（今見えてよい段数）
                if (rows <= 0) return@run
                Log.d("bossTest", "reveal=${rows}")

                val rBottom  = (bv.bottom - cameraTopEffective)
                val bottomPx = height - (rBottom * tileH) + climbOffsetPx
                val topPx    = bottomPx - rows * tileH  // ← 同じボトム基準で上端決定

                val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

                // 中央列以外を “topPx..bottomPx” で一気に塗る
                val center = Config.COLS / 2
                for (c in 0 until Config.COLS) {
                    if (c == center) continue
                    val xL = c * tileW.toFloat()
                    canvas.drawRect(xL, topPx, xL + tileW, bottomPx, mask)
                }
            }

            // ボスの描画
            run {
                val bd  = world.getBossDraw() ?: return@run
                val bmp = kongFrames[bd.pose.coerceIn(0,1)] ?: return@run

                // 白塗りと同じ bottomPx を使う
                val rBottom  = bd.areaBottom - cameraTopEffective
                val bottomPx = height - (rBottom * tileH) + climbOffsetPx

                val reveal   = world.getBossView(cameraTopEffective, visibleRows)?.reveal ?: 0
                if (reveal <= 0) return@run
                val clipTop  = bottomPx - reveal * tileH

                val tileWf = tileW.toFloat()
                val sideAreaW = 2 * tileWf
                val overhangPx = 1f * tileWf         // ★ 通路側へ“1列”ぶん出す

                // 横方向のクリップ範囲（サイド2列＋通路1列）
                val clipLeft  = if (bd.side < 0) 0f                         else width - sideAreaW - overhangPx
                val clipRight = if (bd.side < 0) sideAreaW + overhangPx     else width.toFloat()

                // 拡大（赤枠目安）。縦も少し大きく
                val boxW = (sideAreaW + overhangPx) * 0.94f
                val boxH = (bd.areaHeight * tileH) * 1.20f

                val s = minOf(boxW / bmp.width, boxH / bmp.height)
                val w = bmp.width  * s
                val h = bmp.height * s

                // ★ 下端を bottomPx にピッタリ合わせる
                val cx = (clipLeft + clipRight) * 0.5f
                val dst = RectF(cx - w/2f, bottomPx - h, cx + w/2f, bottomPx)
                val src = Rect(0, 0, bmp.width, bmp.height)

                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    isFilterBitmap = false
                    alpha = 255 // 半透明化しない
                }

                // 下から徐々に露出（拡大しても露出高さは“reveal段分”で一定）
                canvas.save()
                canvas.clipRect(clipLeft, clipTop, clipRight, bottomPx)

                if (bd.side > 0) {
                    canvas.scale(-1f, 1f, dst.centerX(), dst.centerY()) // 右側は反転
                }
                canvas.drawBitmap(bmp, src, dst, p)
                canvas.restore()
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
                    val bmpPot  = Assets.getPotBitmap()
                    val bmpDrop = Assets.getBirdDropBitmap()

                    val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(120, 60, 30) }
                    for (pot in world.pots) {
                        val r = (pot.yFloor - cameraTopEffective)
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

            // ===== しらけどり描画 =====
            run {
                val sd = world.getShirakeDraw() ?: return@run
                val bmp = shirakeFrames[sd.flap.coerceIn(0,1)] ?: return@run

                val cols = Config.COLS
                val tileW = width / cols
                val tileH = tileW

                // 背景補間（climbOffsetPx）を合わせたいなら加算
                val r  = (sd.yFloor - camTopFloor)
                val cy = height - ((r + 0.5f) * tileH) + 0f /* + climbOffsetPx してもOK */
                val cx = sd.xFrac * width

                // サイズ決め（タイル基準で拡大）
                val baseW = tileW * 1.1f
                val baseH = tileH * 0.9f
                val boxW  = baseW * shirakeScale
                val boxH  = baseH * shirakeScale
                val s     = minOf(boxW / bmp.width, boxH / bmp.height)
                val w     = bmp.width * s
                val h     = bmp.height * s

                val left   = cx - w / 2f
                //val top    = (cy - h / 2f).coerceAtLeast(0f)
                val top    = cy - h / 2f
                val right  = cx + w / 2f
                val bottom = top + h

                shirakeSrc.set(0, 0, bmp.width, bmp.height)
                shirakeDst.set(left, top, right, bottom)

                // 向き：左向きなら左右反転して描画（画像を別に持たなくてもOK）
                if (sd.dir < 0) {
                    canvas.save()
                    // cx を中心に左右反転
                    canvas.scale(-1f, 1f, cx, cy)
                    canvas.drawBitmap(bmp, shirakeSrc, shirakeDst, shirakePaint)
                    canvas.restore()
                } else {
                    canvas.drawBitmap(bmp, shirakeSrc, shirakeDst, shirakePaint)
                }
            }

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
