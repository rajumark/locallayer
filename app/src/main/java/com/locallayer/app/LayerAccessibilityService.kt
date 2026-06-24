package com.locallayer.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

enum class OverlayMode { LIVE, VOLUME_KEY }

class LayerAccessibilityService : AccessibilityService() {

    companion object {
        var sourceLang = "en"
        var targetLang = "fr"
        var mode = OverlayMode.LIVE
        val translationCache = mutableMapOf<String, String>()
    }

    private lateinit var windowManager: WindowManager
    private var activeOverlays = mutableListOf<View>()
    private var pendingKeys = mutableSetOf<String>()
    private var translator: Translator? = null
    private var lastPackage = ""
    private var debounceRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())
    private var hideOverlaysRunnable: Runnable? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val info = serviceInfo
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        serviceInfo = info
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (mode == OverlayMode.VOLUME_KEY && event.action == KeyEvent.ACTION_DOWN) {
            if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                showOneshotOverlay()
                return true
            }
        }
        return super.onKeyEvent(event)
    }

    private fun showOneshotOverlay() {
        hideOverlaysRunnable?.let { handler.removeCallbacks(it) }

        val rootNode = rootInActiveWindow ?: return
        pendingKeys.clear()
        val textBlocks = mutableListOf<Pair<String, Rect>>()
        collectTextBlocks(rootNode, textBlocks)

        for ((text, bounds) in textBlocks) {
            showTranslatedOverlay(text, bounds)
        }

        hideOverlaysRunnable = Runnable {
            removeAllOverlays()
            pendingKeys.clear()
        }
        handler.postDelayed(hideOverlaysRunnable!!, 3000)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (mode != OverlayMode.LIVE) return
        if (sourceLang == targetLang) return

        hideOverlaysRunnable?.let { handler.removeCallbacks(it) }
        hideOverlaysRunnable = null

        val pkg = event.packageName?.toString() ?: return
        if (pkg.contains("locallayer")) return

        debounceRunnable?.let { handler.removeCallbacks(it) }
        debounceRunnable = Runnable {
            processScreen(pkg)
        }
        handler.postDelayed(debounceRunnable!!, 150)
    }

    private fun processScreen(pkg: String) {
        lastPackage = pkg
        removeAllOverlays()
        pendingKeys.clear()

        val rootNode = rootInActiveWindow ?: return
        val textBlocks = mutableListOf<Pair<String, Rect>>()
        collectTextBlocks(rootNode, textBlocks)

        for ((text, bounds) in textBlocks) {
            showTranslatedOverlay(text, bounds)
        }
    }

    private fun collectTextBlocks(node: AccessibilityNodeInfo?, result: MutableList<Pair<String, Rect>>) {
        if (node == null) return
        if (!node.text.isNullOrEmpty() && node.isVisibleToUser && node.text.length > 1) {
            val originalText = node.text.toString().trim()
            if (originalText.isNotBlank()) {
                val bounds = Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.width() > 0 && bounds.height() > 0) {
                    result.add(originalText to bounds)
                }
            }
        }
        for (i in 0 until node.childCount) {
            collectTextBlocks(node.getChild(i), result)
        }
    }

    private fun showTranslatedOverlay(text: String, bounds: Rect) {
        val cacheKey = "$sourceLang:$targetLang:$text"
        val blockKey = "$text:${bounds.left},${bounds.top}"

        if (blockKey in pendingKeys) return
        pendingKeys.add(blockKey)

        val cached = translationCache[cacheKey]
        if (cached != null) {
            drawOverlay(cached, bounds, blockKey)
            return
        }

        getOrCreateTranslator().translate(text)
            .addOnSuccessListener { translated ->
                translationCache[cacheKey] = translated
                drawOverlay(translated, bounds, blockKey)
            }
            .addOnFailureListener {
                drawOverlay(text, bounds, blockKey)
            }
    }

    private fun drawOverlay(text: String, bounds: Rect, blockKey: String) {
        if (blockKey !in pendingKeys) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bounds.left
            y = bounds.top
        }

        val overlay = TextView(this).apply {
            setText(text)
            setBackgroundColor(Color.argb(200, 0, 0, 0))
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
        }

        try {
            windowManager.addView(overlay, params)
            activeOverlays.add(overlay)
        } catch (_: Exception) {}
    }

    private fun removeAllOverlays() {
        val iterator = activeOverlays.iterator()
        while (iterator.hasNext()) {
            try {
                windowManager.removeView(iterator.next())
            } catch (_: Exception) {}
            iterator.remove()
        }
    }

    private fun getOrCreateTranslator(): Translator {
        val existing = translator
        if (existing != null) return existing

        val options = TranslatorOptions.Builder()
            .setSourceLanguage(sourceLang)
            .setTargetLanguage(targetLang)
            .build()

        return Translation.getClient(options).also { translator = it }
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        debounceRunnable?.let { handler.removeCallbacks(it) }
        hideOverlaysRunnable?.let { handler.removeCallbacks(it) }
        removeAllOverlays()
        pendingKeys.clear()
        translator?.close()
        super.onDestroy()
    }
}
