package com.locallayer.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions

class LayerAccessibilityService : AccessibilityService() {

    companion object {
        var sourceLang = "en"
        var targetLang = "fr"
    }

    private lateinit var windowManager: WindowManager
    private var activeOverlays = mutableListOf<View>()
    private var cachedOverlays = mutableMapOf<String, View>()
    private var translator: Translator? = null
    private var lastPackage = ""
    private var debounceRunnable: Runnable? = null
    private val handler = Handler(Looper.getMainLooper())

    override fun onServiceConnected() {
        super.onServiceConnected()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (sourceLang == targetLang) return

        val pkg = event.packageName?.toString() ?: return
        if (pkg.contains("locallayer")) return

        debounceRunnable?.let { handler.removeCallbacks(it) }
        debounceRunnable = Runnable {
            processScreen(pkg)
        }
        handler.postDelayed(debounceRunnable!!, 200)
    }

    private fun processScreen(pkg: String) {
        lastPackage = pkg

        removeAllOverlays()
        cachedOverlays.clear()

        val rootNode = rootInActiveWindow ?: return
        val textBlocks = mutableListOf<Pair<String, Rect>>()
        collectTextBlocks(rootNode, textBlocks)

        for ((text, bounds) in textBlocks) {
            translateAndShowOverlay(text, bounds)
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

    private fun translateAndShowOverlay(text: String, bounds: Rect) {
        val key = "$text:${bounds.left},${bounds.top}"
        if (cachedOverlays.containsKey(key)) return

        getOrCreateTranslator().translate(text)
            .addOnSuccessListener { translated ->
                showOverlay(translated, bounds, key)
            }
            .addOnFailureListener {
                showOverlay(text, bounds, key)
            }
    }

    private fun showOverlay(text: String, bounds: Rect, cacheKey: String) {
        if (cachedOverlays.containsKey(cacheKey)) return

        val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
        }

        val params = WindowManager.LayoutParams(
            bounds.width(),
            bounds.height(),
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
            cachedOverlays[cacheKey] = overlay
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
        removeAllOverlays()
        cachedOverlays.clear()
        translator?.close()
        super.onDestroy()
    }
}
