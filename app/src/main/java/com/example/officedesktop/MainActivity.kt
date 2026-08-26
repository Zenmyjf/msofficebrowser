package com.example.officedesktop

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.hypot

/**
 * A "desktop in your pocket" browser shell for Office Online (Excel, Word, etc).
 *
 * - Locked to landscape, immersive fullscreen.
 * - Loads pages with a desktop Chrome user-agent so Office serves the full
 *   desktop web app instead of the cut-down mobile layout.
 * - Provides a virtual mouse cursor: drag anywhere on the screen like a
 *   laptop trackpad to move the cursor, tap to left-click, long-press to
 *   right-click, double-tap to double-click, two-finger drag to scroll.
 *   Clicks are dispatched as real MouseEvents inside the page (not touch
 *   events), which is what lets hover menus / ribbons in Office behave
 *   like they do on a real desktop.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var cursor: ImageView
    private lateinit var touchPad: View
    private lateinit var rootContainer: FrameLayout

    // Current virtual cursor position, in screen pixels relative to rootContainer.
    private var cursorX = 0f
    private var cursorY = 0f

    private var mouseModeEnabled = true

    // Gesture bookkeeping
    private var downTime = 0L
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false
    private var longPressFired = false
    private var lastTapUpTime = 0L

    // Two-finger scroll bookkeeping
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val handler = Handler(Looper.getMainLooper())
    private var longPressRunnable: Runnable? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    companion object {
        // Change this to any Office Online URL you like:
        // Excel: https://excel.new
        // Word:  https://word.new
        // PowerPoint: https://powerpoint.new
        // Office hub: https://www.office.com
        private const val START_URL = "https://excel.new"

        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        private const val TAP_DISTANCE_THRESHOLD_PX = 18f
        private const val TAP_TIME_THRESHOLD_MS = 250L
        private const val LONG_PRESS_MS = 480L
        private const val DOUBLE_TAP_MS = 300L

        // >1 makes the cursor travel a bit faster than your finger, so you
        // can reach the far side of a landscape screen without huge swipes.
        private const val CURSOR_SENSITIVITY = 1.35f

        private const val FILE_CHOOSER_REQUEST_CODE = 51426
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

        setupWebView()
        setupTrackpad()
        setupToolbar()

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
                injectMouseHelperJs()
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                // Keep everything (including Microsoft login redirects) inside the WebView.
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

    /**
     * Injects a small JS helper into the page that turns (x, y, type) requests
     * from Kotlin into real MouseEvent / WheelEvent dispatches on the element
     * under that point. Re-injected on every page load since navigation wipes
     * the page's JS context.
     */
    private fun injectMouseHelperJs() {
        val js = """
            (function() {
              window.__officeVM = function(type, x, y, extra) {
                var el = document.elementFromPoint(x, y);
                if (!el) return;
                if (type === 'wheel') {
                  var wheelEv = new WheelEvent('wheel', {
                    view: window, bubbles: true, cancelable: true,
                    clientX: x, clientY: y,
                    deltaX: extra.dx, deltaY: extra.dy, deltaMode: 0
                  });
                  el.dispatchEvent(wheelEv);
                  return;
                }
                var opts = {
                  view: window, bubbles: true, cancelable: true,
                  clientX: x, clientY: y,
                  button: extra && extra.button ? extra.button : 0,
                  buttons: (type === 'mousedown') ? 1 : 0
                };
                var ev = new MouseEvent(type, opts);
                el.dispatchEvent(ev);
                if (type === 'mousedown' && typeof el.focus === 'function') {
                  try { el.focus(); } catch (e) {}
                }
              };
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    private fun dispatchMouse(type: String, screenX: Float, screenY: Float, button: Int = 0) {
        val (cssX, cssY) = toCssCoordinates(screenX, screenY)
        val js = "window.__officeVM && window.__officeVM('$type', $cssX, $cssY, {button:$button});"
        webView.evaluateJavascript(js, null)
    }

    private fun dispatchWheel(screenX: Float, screenY: Float, dx: Float, dy: Float) {
        val (cssX, cssY) = toCssCoordinates(screenX, screenY)
        val js = "window.__officeVM && window.__officeVM('wheel', $cssX, $cssY, {dx:$dx, dy:$dy});"
        webView.evaluateJavascript(js, null)
    }

    private fun toCssCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> {
        val density = resources.displayMetrics.density
        val scale = webView.scale.takeIf { it > 0f } ?: 1f
        return Pair(screenX / density / scale, screenY / density / scale)
    }

    // ---------------------------------------------------------------------
    // Virtual trackpad / mouse
    // ---------------------------------------------------------------------

    private fun updateCursorView() {
        cursor.x = cursorX - cursor.width / 2f
        cursor.y = cursorY - cursor.height / 2f
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTrackpad() {
        touchPad.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downTime = System.currentTimeMillis()
                    startX = event.x
                    startY = event.y
                    lastX = event.x
                    lastY = event.y
                    isDragging = false
                    longPressFired = false
                    scheduleLongPress()
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    cancelLongPress()
                    if (event.pointerCount == 2) {
                        lastFocusX = (event.getX(0) + event.getX(1)) / 2f
                        lastFocusY = (event.getY(0) + event.getY(1)) / 2f
                    }
                }

                MotionEvent.ACTION_MOVE -> {
                    if (event.pointerCount >= 2) {
                        // Two fingers: scroll the page instead of moving the cursor.
                        val fx = (event.getX(0) + event.getX(1)) / 2f
                        val fy = (event.getY(0) + event.getY(1)) / 2f
                        val dx = fx - lastFocusX
                        val dy = fy - lastFocusY
                        lastFocusX = fx
                        lastFocusY = fy
                        dispatchWheel(cursorX, cursorY, -dx, -dy)
                    } else {
                        val dx = (event.x - lastX) * CURSOR_SENSITIVITY
                        val dy = (event.y - lastY) * CURSOR_SENSITIVITY
                        lastX = event.x
                        lastY = event.y

                        val totalDist = hypot((event.x - startX).toDouble(), (event.y - startY).toDouble())
                        if (totalDist > TAP_DISTANCE_THRESHOLD_PX) {
                            if (!isDragging) cancelLongPress()
                            isDragging = true
                        }

                        cursorX = (cursorX + dx).coerceIn(0f, rootContainer.width.toFloat())
                        cursorY = (cursorY + dy).coerceIn(0f, rootContainer.height.toFloat())
                        updateCursorView()

                        if (mouseModeEnabled) dispatchMouse("mousemove", cursorX, cursorY)
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
                    cancelLongPress()
                    val elapsed = System.currentTimeMillis() - downTime
                    if (mouseModeEnabled && !isDragging && !longPressFired &&
                        elapsed < TAP_TIME_THRESHOLD_MS
                    ) {
                        performClick()
                    }
                }

                MotionEvent.ACTION_CANCEL -> {
                    cancelLongPress()
                }
            }
            true
        }
    }

    private fun scheduleLongPress() {
        cancelLongPress()
        val runnable = Runnable {
            if (!isDragging) {
                longPressFired = true
                dispatchMouse("mousedown", cursorX, cursorY, 2)
                dispatchMouse("contextmenu", cursorX, cursorY, 2)
                dispatchMouse("mouseup", cursorX, cursorY, 2)
            }
        }
        longPressRunnable = runnable
        handler.postDelayed(runnable, LONG_PRESS_MS)
    }

    private fun cancelLongPress() {
        longPressRunnable?.let { handler.removeCallbacks(it) }
        longPressRunnable = null
    }

    private fun performClick() {
        val now = System.currentTimeMillis()
        val isDoubleTap = (now - lastTapUpTime) < DOUBLE_TAP_MS
        lastTapUpTime = now

        dispatchMouse("mousedown", cursorX, cursorY, 0)
        dispatchMouse("mouseup", cursorX, cursorY, 0)
        dispatchMouse("click", cursorX, cursorY, 0)
        if (isDoubleTap) dispatchMouse("dblclick", cursorX, cursorY, 0)
    }

    // ---------------------------------------------------------------------
    // Toolbar
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
