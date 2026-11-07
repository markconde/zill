package dev.mrkcnd.sillclient.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PointerIcon
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import dev.mrkcnd.sillclient.input.Hid
import dev.mrkcnd.sillclient.input.Mods
import dev.mrkcnd.sillclient.input.TextMirror
import dev.mrkcnd.sillclient.net.StreamSession
import dev.mrkcnd.sillclient.protocol.CursorShape
import dev.mrkcnd.sillclient.protocol.Gestures
import dev.mrkcnd.sillclient.protocol.MacPointer
import dev.mrkcnd.sillclient.protocol.InputEvent
import dev.mrkcnd.sillclient.protocol.ParameterSets
import dev.mrkcnd.sillclient.protocol.PointerAction
import dev.mrkcnd.sillclient.protocol.ScrollPhase
import dev.mrkcnd.sillclient.video.HevcDecoder
import kotlin.math.abs
import kotlin.math.hypot

/**
 * The Mac's picture and the touches on it. The SurfaceView is sized to the video's aspect inside
 * this view (letterboxed); positions go out as fractions of the picture.
 *
 * Touch: tap = click; two-finger tap = right click; one- or two-finger drag = scroll (with a coast
 * after a flick); long press then drag = click-and-drag; three-finger swipe = the Mac's trackpad
 * gesture (Mission Control, Spaces…); pinch = zoom the picture (1× to 5×, this device's alone), and
 * two fingers on a zoomed picture move it. A mouse or a hardware keyboard works as itself, its
 * pointer drawn in the Mac's cursor shape.
 *
 * The Mac's pointer shows as a sprite only while the Mac (or another device) moved it last, as in
 * Sill's own app (PointerPresence.swift): this device's own touches and keys hide it.
 *
 * Trackpad mode (`trackpad`): the picture is a laptop's trackpad instead, as Sill's portrait pad is
 * (TrackpadView.swift), relative where direct touch is absolute. One finger moves this device's own
 * pointer (1.25 pictures for a stroke across the picture), drawn as the Mac's cursor; a tap clicks
 * where it is, two fingers tap to right-click and scroll there, touch and hold then move drags. It
 * carries on from where the Mac's pointer is when the Mac moved it last. On a zoomed picture the
 * view follows the pointer. The wire is unchanged: absolute positions.
 */
@SuppressLint("ViewConstructor")
class StreamView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    var session: StreamSession? = null
        set(value) {
            field = value
            decoder?.let { value?.attach(sink) }
        }
    /** Modifiers latched on the key row: spent by the next key or typed character. */
    var latched: Long = 0
    var onLatchSpent: () -> Unit = {}
    var reverseScroll = false
    var gesturesAllowed = false

    /** Trackpad mode (see above). */
    var trackpad = false
        set(value) {
            if (field == value) return
            field = value
            macPointer?.position?.let { (x, y) -> curX = x; curY = y }
            placeSprite()
        }
    /**
     * The split layout's trackpad is out (StreamScreen): this device's own pointer shows, and strokes
     * from it (padTouch) move it as trackpad mode does, while touches on the picture stay direct.
     */
    var padOpen = false
        set(value) {
            if (field == value) return
            field = value
            if (value) macPointer?.position?.let { (x, y) -> curX = x; curY = y }
            placeSprite()
        }
    /** The stroke in progress came from the split layout's trackpad. */
    private var strokeFromPad = false
    private var padNow = false
    /** Touches move this device's pointer by how far they go, not to where they land. */
    private val relative get() = trackpad || strokeFromPad
    private val ownPointer get() = trackpad || padOpen

    /** A touch on the split layout's trackpad, in its own coordinates: handled as trackpad mode handles the picture. */
    fun padTouch(e: MotionEvent) {
        padNow = true
        try { onTouchEvent(e) } finally { padNow = false }
    }

    /** The split layout's click buttons: a click where this device's pointer is. */
    fun padClick(right: Boolean) {
        strokeFromPad = true
        click(0f, 0f, right)
    }

    /** This device's own pointer, a fraction of the picture: where trackpad mode moves and clicks. */
    private var curX = 0.5
    private var curY = 0.5

    private val surface = SurfaceView(context)
    private val sprite = ImageView(context).apply { visibility = GONE; isClickable = false; isFocusable = false }
    private var decoder: HevcDecoder? = null
    private var videoW = 0
    private var videoH = 0
    private val main = Handler(Looper.getMainLooper())
    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()

    private val sink = object : StreamSession.VideoSink {
        override fun onParameterSets(ps: ParameterSets) { decoder?.configure(ps) }
        override fun onFrame(payload: ByteArray, keyframe: Boolean) { decoder?.decode(payload, keyframe) }
        override val needsKeyframe: Boolean get() = decoder?.needsKeyframe ?: false
    }

    init {
        setBackgroundColor(0xFF000000.toInt())
        addView(surface, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
        addView(sprite, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        clipChildren = true
        surface.holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        contentDescription = "Mac screen"
    }

    // MARK: surface and decoder

    override fun surfaceCreated(holder: SurfaceHolder) {
        decoder = HevcDecoder(
            holder.surface,
            onVideoSize = { w, h -> main.post { setVideoSize(w, h) } },
            onFailure = { session?.requestKeyframe() },
            onFrameRendered = {},
        )
        session?.attach(sink)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        session?.attach(null)
        decoder?.release()
        decoder = null
    }

    private fun setVideoSize(w: Int, h: Int) {
        if (w == videoW && h == videoH) return
        videoW = w; videoH = h
        // A fixed buffer, so zooming moves and scales the surface without resizing what MediaCodec draws into.
        surface.holder.setFixedSize(w, h)
        resetZoom()   // another source, or the window changed size: the whole picture again
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val r = picRect()
        surface.layout(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
        // The sprite sits at 0,0 at its measured size; placeSprite moves it with x and y.
        sprite.layout(0, 0, sprite.measuredWidth, sprite.measuredHeight)
        placeSprite()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        resendViewport()
    }

    /**
     * The panel's size, 250 ms after it last changed: the keyboard and the bars animate the size
     * through many frames, and each viewport has the Mac re-fit the window it streams.
     */
    fun resendViewport() {
        main.removeCallbacks(viewportNow)
        main.postDelayed(viewportNow, 250)
    }

    /** Aa: null is Off (the Mac leaves the window alone), else device points per Mac point. */
    var textScale: Double? = null
        set(value) { if (field != value) { field = value; resendViewport() } }

    private val viewportNow = Runnable {
        if (width > 0 && height > 0) {
            val rate = display?.refreshRate?.toInt()?.coerceIn(30, 120)
            session?.viewport(width / density.toDouble(), height / density.toDouble(), textScale, rate)
        }
    }

    /** Where the picture sits in this view: aspect fit. */
    private fun videoRect(): RectF {
        val vw = width.toFloat(); val vh = height.toFloat()
        if (videoW <= 0 || videoH <= 0 || vw <= 0 || vh <= 0) return RectF(0f, 0f, vw, vh)
        val s = minOf(vw / videoW, vh / videoH)
        val w = videoW * s; val h = videoH * s
        return RectF((vw - w) / 2, (vh - h) / 2, (vw + w) / 2, (vh + h) / 2)
    }

    // MARK: zoom (StreamZoom.swift): the pane scaled about its middle, then moved by an offset

    private var zScale = 1f
    private var zOffX = 0f
    private var zOffY = 0f
    val isZoomed get() = zScale > 1f

    /** Where the picture is drawn now: the fitted rectangle, zoomed. Input maps through this. */
    private fun picRect(): RectF {
        val r = videoRect()
        if (!isZoomed) return r
        val cx = width / 2f; val cy = height / 2f
        return RectF(cx + zScale * (r.left - cx) + zOffX, cy + zScale * (r.top - cy) + zOffY,
            cx + zScale * (r.right - cx) + zOffX, cy + zScale * (r.bottom - cy) + zOffY)
    }

    fun resetZoom() {
        if (zScale == 1f && zOffX == 0f && zOffY == 0f) return
        zScale = 1f; zOffX = 0f; zOffY = 0f
        requestLayout()
    }

    /** One pinch step: scale by `factor` (1× to 5×), the point that was under `from` now under `to`. */
    private fun pinch(factor: Float, fromX: Float, fromY: Float, toX: Float, toY: Float) {
        if (!(factor > 0f) || !factor.isFinite() || width <= 0 || height <= 0) return
        val next = (zScale * factor).coerceIn(1f, MAX_ZOOM)
        if (next <= 1.001f) { resetZoom(); return }
        val cx = width / 2f; val cy = height / 2f
        val ratio = next / zScale
        val limX = width * (next - 1) / 2; val limY = height * (next - 1) / 2
        zOffX = ((toX - cx) - (fromX - cx - zOffX) * ratio).coerceIn(-limX, limX)
        zOffY = ((toY - cy) - (fromY - cy - zOffY) * ratio).coerceIn(-limY, limY)
        zScale = next
        requestLayout()
    }

    // MARK: the Mac's pointer

    /** Kind 26 as the session keeps it: null while this device moved the pointer last. */
    var macPointer: MacPointer? = null
        set(value) {
            field = value
            // The Mac moved its pointer: the pad carries on from there.
            value?.position?.let { (x, y) -> curX = x; curY = y }
            placeSprite()
        }
    var cursorShape: CursorShape? = null
        set(value) {
            if (field === value) return
            field = value
            cursorBitmap = value?.let { BitmapFactory.decodeByteArray(it.png, 0, it.png.size) }
            cursorIcon = null
            sprite.setImageBitmap(cursorBitmap)
            placeSprite()
        }
    private var cursorBitmap: Bitmap? = null
    private var cursorIcon: PointerIcon? = null

    private fun placeSprite() {
        val pos = if (ownPointer) curX to curY else macPointer?.position
        if (ownPointer && (cursorShape == null || cursorBitmap == null) && sprite.drawable !== fallbackArrow) sprite.setImageDrawable(fallbackArrow)
        val shape = cursorShape ?: if (ownPointer) FALLBACK_SHAPE else null
        val r = picRect()
        if (pos == null || shape == null || (cursorBitmap == null && !ownPointer) || r.width() <= 0) { sprite.visibility = GONE; return }
        // Mac points drawn 1:1 in device points (dp), whatever the zoom (HEVCDisplayView.setCursorShape).
        val w = (shape.width * density).toInt(); val h = (shape.height * density).toInt()
        val lp = sprite.layoutParams
        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; sprite.layoutParams = lp }
        sprite.scaleType = ImageView.ScaleType.FIT_XY
        sprite.x = r.left + pos.first.toFloat() * r.width() - shape.hotX * density
        sprite.y = r.top + pos.second.toFloat() * r.height() - shape.hotY * density
        sprite.visibility = VISIBLE
    }

    /** A mouse over the picture shows the Mac's cursor shape instead of Android's arrow. */
    override fun onResolvePointerIcon(event: MotionEvent, pointerIndex: Int): PointerIcon? {
        val bmp = cursorBitmap
        val shape = cursorShape
        if (bmp == null || shape == null) return super.onResolvePointerIcon(event, pointerIndex)
        cursorIcon?.let { return it }
        val w = (shape.width * density).toInt().coerceAtLeast(1); val h = (shape.height * density).toInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bmp, w, h, true)
        return PointerIcon.create(scaled, (shape.hotX * density).coerceIn(0f, w - 1f), (shape.hotY * density).coerceIn(0f, h - 1f))
            .also { cursorIcon = it }
    }

    private fun fraction(x: Float, y: Float): Pair<Double, Double> {
        val r = picRect()
        val fx = ((x - r.left) / r.width()).coerceIn(0f, 1f)
        val fy = ((y - r.top) / r.height()).coerceIn(0f, 1f)
        return fx.toDouble() to fy.toDouble()
    }

    private fun send(e: InputEvent) = session?.sendInput(e)

    /** Where an action at (x, y) on the view goes: there, or in trackpad mode where the pointer is. */
    private fun target(x: Float, y: Float): Pair<Double, Double> =
        if (relative) curX to curY else fraction(x, y).also { (fx, fy) -> curX = fx; curY = fy }

    private fun pointer(action: PointerAction, x: Float, y: Float) {
        val (fx, fy) = target(x, y)
        send(InputEvent.Pointer(action, fx, fy))
    }

    /**
     * Trackpad mode: a finger moved `dx`, `dy` px; the pointer follows, 1.25 pictures for a stroke
     * across the picture (TrackpadSurface's gain), and the view keeps it in sight while zoomed.
     * `button`: the left button is held (a drag).
     */
    private fun padMove(dx: Float, dy: Float) {
        val r = picRect()
        if (r.width() <= 0 || r.height() <= 0) return
        curX = (curX + dx / r.width() * PAD_GAIN).coerceIn(0.0, 1.0)
        curY = (curY + dy / r.height() * PAD_GAIN).coerceIn(0.0, 1.0)
        send(InputEvent.Pointer(PointerAction.MOVE, curX, curY))
        if (isZoomed) {
            val px = r.left + curX.toFloat() * r.width(); val py = r.top + curY.toFloat() * r.height()
            val m = 24 * density
            val shiftX = when { px < m -> m - px; px > width - m -> (width - m) - px; else -> 0f }
            val shiftY = when { py < m -> m - py; py > height - m -> (height - m) - py; else -> 0f }
            if (shiftX != 0f || shiftY != 0f) {
                val limX = width * (zScale - 1) / 2; val limY = height * (zScale - 1) / 2
                zOffX = (zOffX + shiftX).coerceIn(-limX, limX); zOffY = (zOffY + shiftY).coerceIn(-limY, limY)
                requestLayout()
            }
        }
        placeSprite()
    }

    /** Holds latched modifiers' keys around a click, as KeyChord does for a modified click. */
    private fun click(x: Float, y: Float, right: Boolean) {
        forgetText()
        val mods = latched
        session?.sendInputs(Hid.down(0, mods).dropLast(1))
        pointer(PointerAction.MOVE, x, y)
        pointer(if (right) PointerAction.RIGHT_DOWN else PointerAction.LEFT_DOWN, x, y)
        pointer(if (right) PointerAction.RIGHT_UP else PointerAction.LEFT_UP, x, y)
        session?.sendInputs(Hid.up(0, mods).drop(1))
        if (mods != 0L) spendLatch()
    }

    private fun spendLatch() { latched = 0; onLatchSpent() }

    // MARK: touch

    private enum class Mode { NONE, PENDING, SCROLL, DRAG, MULTI, PAD }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var lastX = 0f
    private var lastY = 0f
    private var maxPointers = 0
    private var multiMoved = false
    private var multiStartX = 0f
    private var multiStartY = 0f
    private var velocity: VelocityTracker? = null

    /** Who a two-finger stroke belongs to (StreamZoom.Stroke): undecided, the Mac's scroll, the picture's zoom. */
    private enum class Owner { UNDECIDED, MAC, PICTURE, GESTURE }
    private var owner = Owner.UNDECIDED
    private var pairFromSpacing = 0f
    private var pairFromX = 0f
    private var pairFromY = 0f
    private var pairLastSpacing = 0f
    private var pairLastX = 0f
    private var pairLastY = 0f
    private var zoomBefore = Triple(1f, 0f, 0f)

    private fun spacing(e: MotionEvent) = if (e.pointerCount >= 2) hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1)) else 0f
    private fun pairMid(e: MotionEvent) = ((e.getX(0) + e.getX(1)) / 2) to ((e.getY(0) + e.getY(1)) / 2)

    private fun formPair(e: MotionEvent) {
        val (mx, my) = pairMid(e)
        pairFromSpacing = spacing(e); pairFromX = mx; pairFromY = my
        pairLastSpacing = pairFromSpacing; pairLastX = mx; pairLastY = my
    }
    private val longPress = Runnable {
        if (mode == Mode.PENDING) {
            mode = Mode.DRAG
            forgetText()
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            pointer(PointerAction.MOVE, downX, downY)
            pointer(PointerAction.LEFT_DOWN, downX, downY)
        }
    }

    private fun centroid(e: MotionEvent): Pair<Float, Float> {
        var x = 0f; var y = 0f
        for (i in 0 until e.pointerCount) { x += e.getX(i); y += e.getY(i) }
        return x / e.pointerCount to y / e.pointerCount
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE) return mouse(e)
        velocity = velocity ?: VelocityTracker.obtain()
        velocity?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stopMomentum()
                requestFocus()
                strokeFromPad = padNow
                mode = Mode.PENDING
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                downTime = e.eventTime
                maxPointers = 1
                owner = Owner.UNDECIDED
                zoomBefore = Triple(zScale, zOffX, zOffY)
                main.postDelayed(longPress, 450)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                main.removeCallbacks(longPress)
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (mode == Mode.SCROLL) endScroll(momentum = false)
                if (mode == Mode.DRAG) { pointer(PointerAction.LEFT_UP, lastX, lastY) }
                if (mode != Mode.MULTI) {
                    multiMoved = false
                    val (cx, cy) = centroid(e)
                    multiStartX = cx; multiStartY = cy; lastX = cx; lastY = cy
                }
                mode = Mode.MULTI
                if (e.pointerCount == 2 && owner != Owner.GESTURE && owner != Owner.MAC) {
                    formPair(e)
                    // Two fingers on a zoomed picture move it at once.
                    if (isZoomed) owner = Owner.PICTURE
                } else if (e.pointerCount >= 3) {
                    // Three fingers are the Mac's gesture: the picture goes back to how the stroke found it.
                    if (owner == Owner.PICTURE) {
                        zScale = zoomBefore.first; zOffX = zoomBefore.second; zOffY = zoomBefore.third
                        requestLayout()
                    }
                    if (scrolling) endScroll(momentum = false)
                    owner = Owner.GESTURE
                    val (cx, cy) = centroid(e)
                    multiStartX = cx; multiStartY = cy; lastX = cx; lastY = cy
                }
            }
            MotionEvent.ACTION_MOVE -> when (mode) {
                Mode.PENDING -> if (hypot(e.x - downX, e.y - downY) > slop && relative) {
                    main.removeCallbacks(longPress)
                    mode = Mode.PAD
                    padMove(e.x - downX, e.y - downY)      // the whole way from touch-down: nothing lost to the slop
                    lastX = e.x; lastY = e.y
                } else if (hypot(e.x - downX, e.y - downY) > slop) {
                    main.removeCallbacks(longPress)
                    beginScroll(downX, downY)
                    scrollBy(e.x - lastX, e.y - lastY, e.x, e.y)
                    lastX = e.x; lastY = e.y
                }
                Mode.SCROLL -> { scrollBy(e.x - lastX, e.y - lastY, e.x, e.y); lastX = e.x; lastY = e.y }
                Mode.PAD -> { padMove(e.x - lastX, e.y - lastY); lastX = e.x; lastY = e.y }
                Mode.DRAG -> {
                    if (relative) padMove(e.x - lastX, e.y - lastY) else pointer(PointerAction.MOVE, e.x, e.y)
                    lastX = e.x; lastY = e.y
                }
                Mode.MULTI -> {
                    val (cx, cy) = centroid(e)
                    if (e.pointerCount == 2) twoFingerMove(e, cx, cy)
                    else if (!multiMoved && hypot(cx - multiStartX, cy - multiStartY) > slop) multiMoved = true
                    lastX = cx; lastY = cy
                }
                Mode.NONE -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> if (mode == Mode.MULTI && maxPointers >= 3 && e.pointerCount == maxPointers) {
                // The first finger of a three- or four-finger swipe lifts: judge the swipe now.
                threeFingerSwipe(lastX - multiStartX, lastY - multiStartY, maxPointers)
                maxPointers = 0
            }
            MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress)
                when (mode) {
                    Mode.PENDING -> if (e.eventTime - downTime < 450) click(downX, downY, right = false)
                    Mode.SCROLL -> endScroll(momentum = true)
                    Mode.DRAG -> pointer(PointerAction.LEFT_UP, e.x, e.y)
                    Mode.PAD -> Unit
                    Mode.MULTI -> {
                        // A picture left within 2 % of the whole is the whole picture again.
                        if (owner == Owner.PICTURE && zScale < 1.02f) resetZoom()
                        if (scrolling) endScroll(momentum = true)
                        else if (!multiMoved && maxPointers == 2 && e.eventTime - downTime < 500) click(multiStartX, multiStartY, right = true)
                    }
                    Mode.NONE -> Unit
                }
                mode = Mode.NONE
                velocity?.recycle(); velocity = null
            }
            MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(longPress)
                if (mode == Mode.DRAG) pointer(PointerAction.LEFT_UP, lastX, lastY)
                if (scrolling) endScroll(momentum = false)
                mode = Mode.NONE
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }

    /**
     * Two fingers move: a pinch (their spacing changes by 12 dp, more than their midpoint moves) is
     * the picture's and zooms it; otherwise, once they travel, the Mac's scroll, as before the zoom.
     */
    private fun twoFingerMove(e: MotionEvent, cx: Float, cy: Float) {
        val sp = spacing(e)
        val (mx, my) = pairMid(e)
        if (owner == Owner.UNDECIDED) {
            val change = abs(sp - pairFromSpacing)
            val travel = hypot(mx - pairFromX, my - pairFromY)
            if (change >= 12 * density && change > travel) {
                owner = Owner.PICTURE      // caught up from where the pair landed
            } else if (hypot(cx - multiStartX, cy - multiStartY) > slop) {
                owner = Owner.MAC
                multiMoved = true
                beginScroll(cx, cy, keepMode = true)
            }
        }
        when (owner) {
            Owner.PICTURE -> {
                if (pairLastSpacing >= 1f && sp >= 1f) pinch(sp / pairLastSpacing, pairLastX, pairLastY, mx, my)
                if (hypot(mx - pairFromX, my - pairFromY) > slop || abs(sp - pairFromSpacing) > slop) multiMoved = true
                pairLastSpacing = sp; pairLastX = mx; pairLastY = my
            }
            Owner.MAC -> if (scrolling) scrollBy(cx - lastX, cy - lastY, cx, cy)
            else -> Unit
        }
    }

    private fun threeFingerSwipe(dx: Float, dy: Float, fingers: Int) {
        if (!gesturesAllowed) return
        val min = 60 * density
        if (abs(dx) < min && abs(dy) < min) return
        val name = if (abs(dx) > abs(dy)) {
            if (dx > 0) Gestures.SWIPE_RIGHT else Gestures.SWIPE_LEFT
        } else {
            if (dy > 0) Gestures.SWIPE_DOWN else Gestures.SWIPE_UP
        }
        session?.gesture(name, fingers.coerceIn(3, 4))
    }

    // MARK: scrolling, with a coast after a flick

    private var scrolling = false
    private var scrollAtX = 0.0
    private var scrollAtY = 0.0
    private val factor get() = if (reverseScroll) -1.0 else 1.0

    private fun beginScroll(x: Float, y: Float, keepMode: Boolean = false) {
        if (!keepMode) mode = Mode.SCROLL
        scrolling = true
        val (fx, fy) = target(x, y)
        scrollAtX = fx; scrollAtY = fy
        send(InputEvent.ScrollGesture(ScrollPhase.BEGAN, fx, fy))
    }

    private fun scrollBy(dxPx: Float, dyPx: Float, x: Float, y: Float) {
        val r = picRect()
        if (r.width() <= 0 || r.height() <= 0) return
        if (dxPx == 0f && dyPx == 0f) return
        val (fx, fy) = target(x, y)
        send(InputEvent.Scroll(fx, fy, dxPx / r.width() * factor, dyPx / r.height() * factor))
    }

    private fun endScroll(momentum: Boolean) {
        if (!scrolling) return
        scrolling = false
        send(InputEvent.ScrollGesture(ScrollPhase.ENDED, scrollAtX, scrollAtY))
        if (!momentum) return
        val v = velocity ?: return
        v.computeCurrentVelocity(1000)
        val vx = v.xVelocity; val vy = v.yVelocity
        if (hypot(vx, vy) > 300 * density) startMomentum(vx, vy)
    }

    private var coastVx = 0f
    private var coastVy = 0f
    private var coasting = false
    private val coastStep = object : Runnable {
        override fun run() {
            if (!coasting) return
            coastVx *= 0.95f; coastVy *= 0.95f
            if (hypot(coastVx, coastVy) < 40 * density) { stopMomentum(); return }
            val r = picRect()
            val dt = 1 / 60f
            send(InputEvent.Scroll(scrollAtX, scrollAtY, coastVx * dt / r.width() * factor, coastVy * dt / r.height() * factor))
            main.postDelayed(this, 16)
        }
    }

    private fun startMomentum(vx: Float, vy: Float) {
        coastVx = vx; coastVy = vy; coasting = true
        send(InputEvent.ScrollGesture(ScrollPhase.MOMENTUM_BEGAN, scrollAtX, scrollAtY))
        main.post(coastStep)
    }

    private fun stopMomentum() {
        if (!coasting) return
        coasting = false
        main.removeCallbacks(coastStep)
        send(InputEvent.ScrollGesture(ScrollPhase.MOMENTUM_ENDED, scrollAtX, scrollAtY))
    }

    // MARK: a mouse or a trackpad attached to the device

    private var mouseButtons = 0

    private fun mouse(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_MOVE -> pointer(PointerAction.MOVE, e.x, e.y)
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> {
                val now = e.buttonState
                val changed = now xor mouseButtons
                if (changed and MotionEvent.BUTTON_PRIMARY != 0) {
                    pointer(if (now and MotionEvent.BUTTON_PRIMARY != 0) PointerAction.LEFT_DOWN else PointerAction.LEFT_UP, e.x, e.y)
                }
                if (changed and MotionEvent.BUTTON_SECONDARY != 0) {
                    pointer(if (now and MotionEvent.BUTTON_SECONDARY != 0) PointerAction.RIGHT_DOWN else PointerAction.RIGHT_UP, e.x, e.y)
                }
                mouseButtons = now
            }
            MotionEvent.ACTION_SCROLL -> {
                val r = picRect()
                val (fx, fy) = fraction(e.x, e.y)
                val dy = e.getAxisValue(MotionEvent.AXIS_VSCROLL) * 40 * density
                val dx = -e.getAxisValue(MotionEvent.AXIS_HSCROLL) * 40 * density
                if (r.width() > 0) send(InputEvent.Scroll(fx, fy, (dx / r.width()).toDouble(), (dy / r.height()).toDouble()))
            }
        }
        return true
    }

    override fun onGenericMotionEvent(e: MotionEvent): Boolean {
        if (e.isFromSource(InputDevice.SOURCE_CLASS_POINTER)) return mouse(e)
        return super.onGenericMotionEvent(e)
    }

    // MARK: keys and text

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = hardwareKey(event, true) || super.onKeyDown(keyCode, event)
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = hardwareKey(event, false) || super.onKeyUp(keyCode, event)

    private fun hardwareKey(e: KeyEvent, down: Boolean): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK) return false
        val usage = Hid.forKeyCode(e.keyCode) ?: return false
        if (e.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD || e.device?.isVirtual == true) {
            // A soft keyboard's key event (Enter, Delete): a whole press.
            if (down) typeKey(usage)
            return true
        }
        if (down && e.repeatCount > 0) return true // the Mac repeats a held key itself
        if (down) forgetText()
        send(InputEvent.Key(usage, down, Mods.fromMeta(e.metaState)))
        return true
    }

    /** One key pressed with what is latched (the key row, Return, Delete). It may move the Mac's caret. */
    fun typeKey(usage: Int) {
        forgetText()
        session?.sendInputs(Hid.press(usage, latched))
        if (latched != 0L) spendLatch()
    }

    // MARK: the soft keyboard (TextComposition.swift)
    //
    // The keyboard gets an ordinary text field, so every language and input method works (a password
    // field, as before, has Samsung Keyboard type English only): it edits `mirror`, a copy of what it
    // typed since the Mac's caret last moved, and each change goes to the Mac as backspaces and text
    // (TextMirror). What is still being composed (Pinyin, kana, a Korean syllable underlined) stays here
    // until it is chosen. A click, a key from the row or a hardware keyboard, or a Return or a Tab typed
    // may move the Mac's caret: the copy starts again then (`forgetText`).

    private val mirror = android.text.SpannableStringBuilder().also { android.text.Selection.setSelection(it, 0) }
    /** What the Mac has of `mirror`: its text without the part being composed, as last sent. */
    private var sent = ""
    private val imm get() = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

    /** The Mac's caret may have moved: what the keyboard typed before is no longer the keyboard's to revise. */
    fun forgetText() {
        if (mirror.isEmpty() && sent.isEmpty()) return
        mirror.clear()
        android.text.Selection.setSelection(mirror, 0)
        sent = ""
        imm.restartInput(this)
    }

    /** `mirror` without its composing span: what the Mac should have now. */
    private fun committed(): String {
        val a = BaseInputConnection.getComposingSpanStart(mirror)
        val b = BaseInputConnection.getComposingSpanEnd(mirror)
        if (a < 0 || b < 0 || a >= b) return mirror.toString()
        return mirror.substring(0, a) + mirror.substring(b)
    }

    /** After the keyboard changed `mirror`: tell the Mac. */
    private fun syncText() {
        val now = committed()
        if (now == sent) return
        val shortcut = latched and (Mods.COMMAND or Mods.CONTROL or Mods.OPTION)
        if (shortcut != 0L && now.length > sent.length && now.startsWith(sent)) {
            // ⌘, ⌃ or ⌥ latched: the characters are a shortcut's keys, not text on the Mac.
            for (c in now.substring(sent.length)) {
                val (usage, shift) = Hid.forChar(c) ?: continue
                session?.sendInputs(Hid.press(usage, latched or (if (shift) Mods.SHIFT else 0)))
            }
            spendLatch()
            main.post { forgetText() }
            sent = now
            return
        }
        for (step in TextMirror.diff(sent, now)) when (step) {
            is TextMirror.Step.Backspaces -> repeat(step.count.coerceAtMost(256)) { session?.sendInputs(Hid.press(Hid.BACKSPACE, 0)) }
            is TextMirror.Step.Text -> send(InputEvent.Text(step.text))
            is TextMirror.Step.Key -> session?.sendInputs(Hid.press(step.usage, 0))
        }
        if (latched != 0L && now.length > sent.length) spendLatch()
        val added = if (now.startsWith(sent)) now.substring(sent.length) else now
        sent = now
        // A Return or a Tab: what went before may be past the Mac's caret now (a chat sent it).
        if (TextMirror.endsRevision(added)) main.post { forgetText() }
    }

    fun showKeyboard() {
        requestFocus()
        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Plain text without suggestions: every language and input method, accents held on a key,
        // composition (Pinyin, kana, Hangul) and dictation; no autocorrect to retype on the Mac.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_ENTER_ACTION
        outAttrs.initialSelStart = android.text.Selection.getSelectionStart(mirror).coerceAtLeast(0)
        outAttrs.initialSelEnd = android.text.Selection.getSelectionEnd(mirror).coerceAtLeast(0)
        return object : BaseInputConnection(this, true) {
            private var batch = 0
            override fun getEditable(): android.text.Editable = mirror
            private fun after(ok: Boolean): Boolean { if (batch == 0) syncText(); return ok }

            override fun beginBatchEdit(): Boolean { batch++; return true }
            override fun endBatchEdit(): Boolean { batch = (batch - 1).coerceAtLeast(0); if (batch == 0) syncText(); return batch > 0 }

            override fun commitText(text: CharSequence, newCursorPosition: Int) = after(super.commitText(text, newCursorPosition))
            override fun setComposingText(text: CharSequence, newCursorPosition: Int) = after(super.setComposingText(text, newCursorPosition))
            override fun setComposingRegion(start: Int, end: Int) = after(super.setComposingRegion(start, end))
            override fun finishComposingText() = after(super.finishComposingText())
            override fun setSelection(start: Int, end: Int) = after(super.setSelection(start, end))

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                // What the copy doesn't have (typed before the keyboard opened, or before a Return)
                // is deleted on the Mac all the same: a backspace on an empty field.
                val cursor = android.text.Selection.getSelectionStart(mirror).coerceAtLeast(0)
                val extraBefore = (beforeLength - cursor).coerceIn(0, 64)
                val extraAfter = (afterLength - (mirror.length - cursor)).coerceIn(0, 64)
                val ok = super.deleteSurroundingText(beforeLength, afterLength)
                after(ok)
                repeat(extraBefore) { session?.sendInputs(Hid.press(Hid.BACKSPACE, 0)) }
                repeat(extraAfter) { session?.sendInputs(Hid.press(Hid.DELETE_FORWARD, 0)) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action != KeyEvent.ACTION_DOWN) return true
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DEL -> {
                        // The keyboard's backspace: out of the copy when it has the character, so
                        // the two agree; else straight to the Mac.
                        val end = android.text.Selection.getSelectionEnd(mirror)
                        val start = android.text.Selection.getSelectionStart(mirror)
                        if (BaseInputConnection.getComposingSpanStart(mirror) < 0 && end > 0) {
                            if (start != end) mirror.delete(minOf(start, end), maxOf(start, end))
                            else mirror.delete(Character.offsetByCodePoints(mirror, end, -1), end)
                            syncText()
                        } else session?.sendInputs(Hid.press(Hid.BACKSPACE, latched))
                    }
                    KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> typeKey(Hid.RETURN)
                    else -> {
                        val ch = event.unicodeChar
                        if (ch > 0 && !Character.isISOControl(ch)) commitText(String(Character.toChars(ch)), 1)
                        else Hid.forKeyCode(event.keyCode)?.let { typeKey(it) }
                    }
                }
                return true
            }

            override fun performEditorAction(actionCode: Int): Boolean { typeKey(Hid.RETURN); return true }
        }
    }

    /** Trackpad mode's pointer before the Mac has sent its cursor's shape: a plain arrow. */
    private val fallbackArrow = object : android.graphics.drawable.Drawable() {
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.BLACK }
        private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE; style = android.graphics.Paint.Style.STROKE; strokeJoin = android.graphics.Paint.Join.ROUND
        }
        override fun draw(c: android.graphics.Canvas) {
            val w = bounds.width() / FALLBACK_SHAPE.width; val h = bounds.height() / FALLBACK_SHAPE.height
            val path = android.graphics.Path().apply {
                moveTo(1f * w, 1f * h); lineTo(1f * w, 17f * h); lineTo(5f * w, 13f * h); lineTo(8f * w, 20f * h)
                lineTo(11f * w, 19f * h); lineTo(8f * w, 12f * h); lineTo(13f * w, 12f * h); close()
            }
            edge.strokeWidth = 1.5f * w
            c.drawPath(path, fill); c.drawPath(path, edge)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    companion object {
        /** Sill's zoom range is 1× to 5× (StreamZoom.maxScale). */
        const val MAX_ZOOM = 5f
        /** Pictures the pointer travels for a stroke across the picture (TrackpadSurface's 1.25 frames a swipe). */
        const val PAD_GAIN = 1.25
        /** The fallback arrow's size and hot spot, in Mac points. */
        val FALLBACK_SHAPE = CursorShape(1f, 1f, 14f, 21f, ByteArray(0))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMomentum()
        main.removeCallbacksAndMessages(null)
    }
}
