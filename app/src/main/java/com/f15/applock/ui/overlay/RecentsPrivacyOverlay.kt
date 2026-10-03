package com.f15.applock.ui.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * System window overlay that displays an elegant, frosted privacy card over protected
 * application preview thumbnails in the Recent Apps (Task Switcher / Overview) screen.
 *
 * Architecture:
 * - Uses [WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY].
 * - Enforces [WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE] so all native Recents gestures,
 *   swipes, task switching, and task dismissal pass through seamlessly to the OS.
 * - Enforces [WindowManager.LayoutParams.FLAG_SECURE] to guarantee the privacy overlay itself
 *   cannot be screen-captured.
 * - Dynamic positioning: Masks the active recent task card or specific card bounds.
 */
object RecentsPrivacyOverlay {

    private const val TAG = "RecentsPrivacyOverlay"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isOverlayAttached: Boolean = false

    private var rootOverlayView: FrameLayout? = null
    private var privacyCardContainer: FrameLayout? = null

    /**
     * Displays the privacy mask over the Recent Apps screen.
     *
     * @param context Application or AccessibilityService context.
     * @param targetCardBounds Optional exact bounding rectangle of the protected app's task card.
     *                         If null, calculates the center task card frame for the display.
     */
    fun show(context: Context, targetCardBounds: Rect? = null) {
        mainHandler.post {
            try {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return@post

                if (isOverlayAttached && rootOverlayView != null) {
                    updateCardPosition(context, targetCardBounds)
                    return@post
                }

                val displayMetrics = context.resources.displayMetrics
                val screenW = displayMetrics.widthPixels
                val screenH = displayMetrics.heightPixels

                val windowType = if (context is android.accessibilityservice.AccessibilityService) {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                } else if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O && android.provider.Settings.canDrawOverlays(context)) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                }

                val layoutParams = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    windowType,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_SECURE,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                val root = FrameLayout(context).apply {
                    setBackgroundColor(Color.TRANSPARENT)
                }

                val card = createPrivacyCardView(context)
                privacyCardContainer = card

                val cardParams = calculateCardLayoutParams(context, targetCardBounds, screenW, screenH)
                root.addView(card, cardParams)

                wm.addView(root, layoutParams)
                rootOverlayView = root
                isOverlayAttached = true
                Log.d(TAG, "[RecentsPrivacy] Privacy overlay attached successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to attach Recents privacy overlay", e)
            }
        }
    }

    /**
     * Updates the position of the privacy card when the user scrolls the Recents carousel.
     */
    fun updatePosition(context: Context, targetCardBounds: Rect?) {
        if (!isOverlayAttached) return
        mainHandler.post {
            updateCardPosition(context, targetCardBounds)
        }
    }

    private fun updateCardPosition(context: Context, targetCardBounds: Rect?) {
        val card = privacyCardContainer ?: return
        val displayMetrics = context.resources.displayMetrics
        val cardParams = calculateCardLayoutParams(
            context,
            targetCardBounds,
            displayMetrics.widthPixels,
            displayMetrics.heightPixels
        )
        card.layoutParams = cardParams
    }

    /**
     * Hides and detaches the privacy overlay from WindowManager.
     */
    fun hide(context: Context? = null) {
        mainHandler.post {
            try {
                if (isOverlayAttached && rootOverlayView != null) {
                    val ctx = context ?: rootOverlayView?.context
                    val wm = ctx?.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
                    wm?.removeViewImmediate(rootOverlayView)
                    Log.d(TAG, "[RecentsPrivacy] Privacy overlay detached")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to detach Recents privacy overlay", e)
            } finally {
                rootOverlayView = null
                privacyCardContainer = null
                isOverlayAttached = false
            }
        }
    }

    /**
     * Creates an ultra-premium privacy mask card matching the AERA design language.
     */
    private fun createPrivacyCardView(context: Context): FrameLayout {
        val density = context.resources.displayMetrics.density

        // Card container with rounded corners, dark obsidian fill, and cyan accent border
        val cardBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 28f * density
            setColor(Color.parseColor("#EE0B0F19")) // 93% opacity dark obsidian
            setStroke((1.5f * density).toInt(), Color.parseColor("#4D00E5FF")) // 30% alpha cyan
        }

        val card = FrameLayout(context).apply {
            background = cardBg
            elevation = 16f * density
        }

        // Inner vertical content container
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt(),
                (24 * density).toInt()
            )
        }

        // Icon container: glowing cyan circle with lock badge
        val iconBg = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.parseColor("#1A00E5FF")) // 10% alpha cyan glow
            setStroke((1.5f * density).toInt(), Color.parseColor("#8000E5FF")) // 50% alpha cyan border
        }

        val iconSize = (64 * density).toInt()
        val iconContainer = FrameLayout(context).apply {
            background = iconBg
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * density).toInt()
            }
        }

        // Standard Android lock icon
        val iconView = ImageView(context).apply {
            setImageResource(android.R.drawable.ic_lock_lock)
            setColorFilter(Color.parseColor("#00E5FF"))
            layoutParams = FrameLayout.LayoutParams(
                (32 * density).toInt(),
                (32 * density).toInt()
            ).apply {
                gravity = Gravity.CENTER
            }
        }
        iconContainer.addView(iconView)
        contentLayout.addView(iconContainer)

        // Title: "Protected Application"
        val titleView = TextView(context).apply {
            text = "Protected Application"
            textSize = 17f
            setTextColor(Color.parseColor("#F0F6FC"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (6 * density).toInt()
            }
        }
        contentLayout.addView(titleView)

        // Subtitle: "Content hidden for your privacy"
        val subtitleView = TextView(context).apply {
            text = "Content hidden for your privacy"
            textSize = 13f
            setTextColor(Color.parseColor("#8B949E"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * density).toInt()
            }
        }
        contentLayout.addView(subtitleView)

        // AERA badge pill
        val badgeBg = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 12f * density
            setColor(Color.parseColor("#161B22"))
            setStroke((1f * density).toInt(), Color.parseColor("#30363D"))
        }

        val badgeView = TextView(context).apply {
            text = "AERA SECURITY SHIELD"
            textSize = 10f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            background = badgeBg
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        contentLayout.addView(badgeView)

        val contentParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        card.addView(contentLayout, contentParams)

        return card
    }

    /**
     * Calculates the layout parameters for the card view based on either detected card bounds
     * or the display's center task frame.
     */
    private fun calculateCardLayoutParams(
        context: Context,
        bounds: Rect?,
        screenW: Int,
        screenH: Int
    ): FrameLayout.LayoutParams {
        val density = context.resources.displayMetrics.density

        if (bounds != null && bounds.width() > 100 && bounds.height() > 100) {
            return FrameLayout.LayoutParams(bounds.width(), bounds.height()).apply {
                leftMargin = bounds.left
                topMargin = bounds.top
            }
        }

        // Fallback: Samsung One UI standard Recents center card geometry
        // The active recent task occupies ~84% width and ~58% height, vertically centered
        val cardW = (screenW * 0.84f).toInt()
        val cardH = (screenH * 0.58f).toInt()
        val cardLeft = (screenW - cardW) / 2
        val cardTop = (screenH - cardH) / 2 - (15 * density).toInt()

        return FrameLayout.LayoutParams(cardW, cardH).apply {
            leftMargin = cardLeft.coerceAtLeast(0)
            topMargin = cardTop.coerceAtLeast(0)
        }
    }
}
