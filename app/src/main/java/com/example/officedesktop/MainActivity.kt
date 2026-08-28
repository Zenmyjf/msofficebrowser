package com.example.officedesktop

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.hypot

/**
 * A "desktop in your pocket" browser shell for Office Online (Excel, Word, etc).
 *
 * Clicks/right-clicks/scrolling are dispatched as REAL Android touch
 * MotionEvents straight into the WebView (webView.dispatchTouchEvent), rather
 * than faking mouse events in JavaScript - JS in the outer page cannot reach
 * into Office's embedded content frames, but native touch input (routed by
 * the browser engine itself) crosses that boundary correctly.
 *
 * The cursor ALWAYS moves the same way (relative to your finger, starting
 * from wherever it currently is) - it never snaps/teleports, in any mode.
 *
 * Range selection ("SEL") works the same way it does on a real desktop:
 * click the first cell, then Shift+click the last cell. Tapping the SEL
 * button "arms" a virtual Shift key for your very next tap only, then
 * disarms itself automatically - no dragging involved, so the cursor
 * behaves identically whether SEL is armed or not.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var cursor: ImageView
    private lateinit var touchPad: View
    private lateinit var rootContainer: FrameLayout
    private lateinit var toolbar: View
    private lateinit var toolbarButtons: View
    private lateinit var dragSelectButton: TextView

    private var cursorX = 0f
    private var cursorY = 0f
    private var mouseModeEnabled = true

    // True while the SEL button is armed - the next completed tap will be
    // dispatched with a virtual Shift key held (extends the selection).
    private var shiftClickArmed = false
    // Captured at the start of each touch gesture, so a gesture that began
    // while armed still completes as a shift-click even if disarmed mid-way.
    private var gestureIsShiftClick = false

    // Trackpad gesture bookkeeping (moving the visual cursor)
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false

    // The touch we're holding "down" on the WebView, representing a
    // potential click / long-press-right-click.
    private var syntheticTouchActive = false
    private var syntheticDownTime = 0L

    // Two-finger-drag => native touch scroll.
    private var scrollTouchActive = false
    private var scrollDownTime = 0L
    private var scrollPointerX = 0f
    private var scrollPointerY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    // Toolbar drag bookkeeping
    private var toolbarDownRawX = 0f
    private var toolbarDownRawY = 0f
    private var toolbarStartTransX = 0f
    private var toolbarStartTransY = 0f

    // Page zoom (acts like a "resolution" control - shrinks/grows the whole desktop page)
    private var pageZoomPercent = 70

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        private const val START_URL = "https://excel.new"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val TAP_DISTANCE_THRESHOLD_PX = 18f
        private const val CURSOR_SENSITIVITY = 1.35f
        private const val FILE_CHOOSER_REQUEST_CODE = 51426

        private const val ZOOM_MIN = 30
        private const val ZOOM_MAX = 150
        private const val ZOOM_STEP = 5
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        enableImmersiveMode()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        rootContainer = findViewById(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        cursor = findViewById(R.id.cursor)
        touchPad = findViewById(R.id.touchPad)
        toolbar = findViewById(R.id.toolbar)
        toolbarButtons = findViewById(R.id.toolbarButtons)
        dragSelectButton = findViewById(R.id.btnDragSelect)

        setupWebView()
        setupTrackpad()
        setupToolbar()
        setupToolbarDrag()

        webView.loadUrl(START_URL)

        rootContainer.post {
            cursorX = rootContainer.width / 2f
            cursorY = rootContainer.height / 2f
            updateCursorView()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enableImmersiveMode()
    }

    private fun enableImmersiveMode() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    // ---------------------------------------------------------------------
    // WebView setup
    // ---------------------------------------------------------------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            userAgentString = DESKTOP_USER_AGENT
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            setGeolocationEnabled(false)
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                applyPageZoom()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                return false
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                val intent = params?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: Exception) {
                    filePathCallback = null
                    return false
                }
                return true
            }
        }

        webView.setDownloadListener { url, _, _, _, _ ->
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "Couldn't open download link", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            val results: Array<Uri>? = if (resultCode == RESULT_OK && data?.data != null) {
                arrayOf(data.data!!)
            } else null
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    /** Shrinks/grows the whole page like a resolution/zoom control, so more of a
     *  desktop-sized UI (ribbon, panes, etc.) fits on a phone screen. */
    private fun applyPageZoom() {
        val js = "document.documentElement.style.zoom = '${pageZoomPercent}%';"
        webView.evaluateJavascript(js, null)
    }

    private fun changeZoom(delta: Int) {
        pageZoomPercent = (pageZoomPercent + delta).coerceIn(ZOOM_MIN, ZOOM_MAX)
        applyPageZoom()
        Toast.makeText(this, "Zoom: $pageZoomPercent%", Toast.LENGTH_SHORT).show()
    }

    private fun disarmShiftClick() {
        shiftClickArmed = false
        dragSelectButton.setTextColor(0xFFFFFFFF.toInt())
    }

    // ---------------------------------------------------------------------
    // Virtual trackpad / mouse - dispatches REAL touch events to the WebView
    // ---------------------------------------------------------------------

    private fun updateCursorView() {
        // The arrow icon's tip sits near the top-left of its bounding box,
        // so aligning the view's top-left with the cursor position puts the
        // TIP (not the center) at the coordinate that actually gets clicked.
        cursor.x = cursorX
        cursor.y = cursorY
    }

    private fun sendSynthetic(action: Int, x: Float, y: Float, downTime: Long, metaState: Int = 0) {
        val eventTime = SystemClock.uptimeMillis()
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, metaState)
        webView.dispatchTouchEvent(ev)
        ev.recycle()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTrackpad() {
        touchPad.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Lock in whether this whole gesture is a shift-click, so
                    // toggling SEL mid-gesture can't change it partway through.
                    gestureIsShiftClick = shiftClickArmed

                    startX = event.x
                    startY = event.y
                    lastX = event.x
                    lastY = event.y
                    isDragging = false

                    // Hold a live touch at the cursor's current spot - the cursor
                    // only ever moves relative to finger movement, never jumping
                    // to the touch point. Quick lift -> tap/click. Held still ->
                    // Chromium's native long-press fires a real right-click for us.
                    syntheticDownTime = SystemClock.uptimeMillis()
                    syntheticTouchActive = true
                    sendSynthetic(
                        MotionEvent.ACTION_DOWN, cursorX, cursorY, syntheticDownTime,
                        if (gestureIsShiftClick) KeyEvent.META_SHIFT_ON else 0
                    )
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    if (syntheticTouchActive) {
                        sendSynthetic(MotionEvent.ACTION_CANCEL, cursorX, cursorY, syntheticDownTime)
                        syntheticTouchActive = false
                    }
                    if (event.pointerCount == 2) {
                        lastFocusX = (event.getX(0) + event.getX(1)) / 2f
                        lastFocusY = (event.getY(0) + event.getY(1)) / 2f
                        scrollDownTime = SystemClock.uptimeMillis()
                        scrollPointerX = cursorX
                        scrollPointerY = cursorY
                        scrollTouchActive = true
                        sendSynthetic(MotionEvent.ACTION_DOWN, scrollPointerX, scrollPointerY, scrollDownTime)
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        val fx = (event.getX(0) + event.getX(1)) / 2f
                        val fy = (event.getY(0) + event.getY(1)) / 2f
                        val dx = fx - lastFocusX
                        val dy = fy - lastFocusY
                        lastFocusX = fx
                        lastFocusY = fy
                        if (scrollTouchActive) {
                            scrollPointerX = (scrollPointerX + dx).coerceIn(0f, rootContainer.width.toFloat())
                            scrollPointerY = (scrollPointerY + dy).coerceIn(0f, rootContainer.height.toFloat())
                            sendSynthetic(MotionEvent.ACTION_MOVE, scrollPointerX, scrollPointerY, scrollDownTime)
                        }
                    } else {
                        // Cursor ALWAYS moves relative to your finger, starting from
                        // wherever it currently is - identical behavior regardless
                        // of SEL state. It never jumps to the touch point.
                        val dx = (event.x - lastX) * CURSOR_SENSITIVITY
                        val dy = (event.y - lastY) * CURSOR_SENSITIVITY
                        lastX = event.x
                        lastY = event.y

                        val totalDist = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())
                        if (totalDist > TAP_DISTANCE_THRESHOLD_PX) {
                            isDragging = true
                            if (syntheticTouchActive) {
                                sendSynthetic(MotionEvent.ACTION_CANCEL, cursorX, cursorY, syntheticDownTime)
                                syntheticTouchActive = false
                            }
                        }

                        cursorX = (cursorX + dx).coerceIn(0f, rootContainer.width.toFloat())
                        cursorY = (cursorY + dy).coerceIn(0f, rootContainer.height.toFloat())
                        updateCursorView()
                    }
                }

                MotionEvent.ACTION_POINTER_UP -> {
                    if (event.pointerCount == 2) {
                        val remainingIndex = 1 - event.actionIndex
                        lastFocusX = event.getX(remainingIndex)
                        lastFocusY = event.getY(remainingIndex)
                    }
                }

                MotionEvent.ACTION_UP -> {
                    if (scrollTouchActive) {
                        sendSynthetic(MotionEvent.ACTION_UP, scrollPointerX, scrollPointerY, scrollDownTime)
                        scrollTouchActive = false
                    }
                    if (syntheticTouchActive) {
                        sendSynthetic(
                            MotionEvent.ACTION_UP, cursorX, cursorY, syntheticDownTime,
                            if (gestureIsShiftClick) KeyEvent.META_SHIFT_ON else 0
                        )
                        syntheticTouchActive = false
                        // A real click/tap completed - if it was armed as a
                        // shift-click, that job is done, so disarm automatically.
                        if (gestureIsShiftClick) disarmShiftClick()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    if (syntheticTouchActive) {
                        sendSynthetic(MotionEvent.ACTION_CANCEL, cursorX, cursorY, syntheticDownTime)
                        syntheticTouchActive = false
                    }
                    if (scrollTouchActive) {
                        sendSynthetic(MotionEvent.ACTION_CANCEL, scrollPointerX, scrollPointerY, scrollDownTime)
                        scrollTouchActive = false
                    }
                }
            }
            true
        }
    }

    // ---------------------------------------------------------------------
    // Toolbar: buttons, drag-to-move, collapse
    // ---------------------------------------------------------------------

    private fun setupToolbar() {
        findViewById<ImageButton>(R.id.btnBack).setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        findViewById<ImageButton>(R.id.btnReload).setOnClickListener {
            webView.reload()
        }
        findViewById<ImageButton>(R.id.btnMouseToggle).setOnClickListener {
            mouseModeEnabled = !mouseModeEnabled
            touchPad.visibility = if (mouseModeEnabled) View.VISIBLE else View.GONE
            cursor.visibility = if (mouseModeEnabled) View.VISIBLE else View.GONE
            Toast.makeText(
                this,
                if (mouseModeEnabled) "Virtual mouse ON" else "Direct touch mode (native scroll/zoom)",
                Toast.LENGTH_SHORT
            ).show()
        }
        findViewById<ImageButton>(R.id.btnKeyboard).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0)
        }
        findViewById<TextView>(R.id.btnZoomOut).setOnClickListener { changeZoom(-ZOOM_STEP) }
        findViewById<TextView>(R.id.btnZoomIn).setOnClickListener { changeZoom(ZOOM_STEP) }

        dragSelectButton.setOnClickListener {
            shiftClickArmed = !shiftClickArmed
            dragSelectButton.setTextColor(
                if (shiftClickArmed) 0xFF4CD964.toInt() else 0xFFFFFFFF.toInt()
            )
            Toast.makeText(
                this,
                if (shiftClickArmed)
                    "Range-select armed: tap the end cell to select the range"
                else
                    "Range-select cancelled",
                Toast.LENGTH_LONG
            ).show()
        }

        findViewById<TextView>(R.id.btnCollapseToggle).setOnClickListener { view ->
            val isCurrentlyVisible = toolbarButtons.visibility == View.VISIBLE
            toolbarButtons.visibility = if (isCurrentlyVisible) View.GONE else View.VISIBLE
            (view as TextView).text = if (isCurrentlyVisible) ">" else "<"
        }
    }

    /** Lets you drag the whole floating toolbar anywhere on screen, so it never
     *  permanently blocks part of the page underneath it. */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupToolbarDrag() {
        val handleView = findViewById<TextView>(R.id.btnDragHandle)
        handleView.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    toolbarDownRawX = event.rawX
                    toolbarDownRawY = event.rawY
                    toolbarStartTransX = toolbar.translationX
                    toolbarStartTransY = toolbar.translationY
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - toolbarDownRawX
                    val dy = event.rawY - toolbarDownRawY
                    toolbar.translationX = toolbarStartTransX + dx
                    toolbar.translationY = toolbarStartTransY + dy
                }
            }
            true
        }
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
