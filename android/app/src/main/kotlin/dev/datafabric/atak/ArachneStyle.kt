package dev.arachne.atak

import android.app.AlertDialog
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.content.res.Resources
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView

/** Semantic styles for Arachne-owned views. Color literals live only in arachne_theme.xml. */
internal object ArachneStyle {
    enum class Appearance { DARK, GRAY }
    enum class Tone(val dark: Int, val gray: Int) {
        PAGE(R.color.arachne_dark_page, R.color.arachne_gray_page),
        SURFACE(R.color.arachne_dark_surface, R.color.arachne_gray_surface),
        TEXT(R.color.arachne_dark_text, R.color.arachne_gray_text),
        SECONDARY(R.color.arachne_dark_secondary, R.color.arachne_gray_secondary),
        LINE(R.color.arachne_dark_line, R.color.arachne_gray_line),
        EDGE(R.color.arachne_dark_edge, R.color.arachne_gray_edge),
        ACCENT(R.color.arachne_dark_accent, R.color.arachne_gray_accent),
        ON_ACCENT(R.color.arachne_dark_on_accent, R.color.arachne_gray_on_accent),
        SOFT(R.color.arachne_dark_soft, R.color.arachne_gray_soft),
        SUCCESS(R.color.arachne_dark_success, R.color.arachne_gray_success),
        WARNING(R.color.arachne_dark_warning, R.color.arachne_gray_warning),
        DANGER(R.color.arachne_dark_danger, R.color.arachne_gray_danger),
        ACTIVITY(R.color.arachne_dark_activity, R.color.arachne_gray_activity),
        RECEIVE(R.color.arachne_dark_receive, R.color.arachne_gray_receive),
        SEND(R.color.arachne_dark_send, R.color.arachne_gray_send),
        FOCUS(R.color.arachne_dark_focus, R.color.arachne_gray_focus)
    }
    enum class Type(val sp: Float, val semibold: Boolean = false) {
        TITLE(18f, true), SECTION(17f, true), BODY(14f), LABEL(14f, true), ACTION(13f, true),
        INTRO(13f), SUPPORTING(12f), FIELD(12f, true), CAPTION(10f, true), STAT(23f, true)
    }
    enum class Action { PRIMARY, SECONDARY, QUIET, DESTRUCTIVE }
    const val TOUCH = 48
    const val INSET = 16
    const val GAP = 8
    const val GROUP = 12
    const val SECTION_GAP = 20
    const val ROW = 72
    const val HEADER = 52
    const val MARK = 27
    const val ICON = 20
    const val EXTRA_APPEARANCE = "dev.arachne.atak.APPEARANCE"
    const val EXTRA_LARGER_TEXT = "dev.arachne.atak.LARGER_TEXT"
    private const val PREFERENCES = "arachne-display"
    private lateinit var resources: Resources
    private lateinit var regular: Typeface
    private lateinit var semibold: Typeface
    private lateinit var preferences: SharedPreferences
    private val dialogs = mutableSetOf<AlertDialog>()
    var appearance = Appearance.DARK
        private set
    var reducedMotion = false
        private set
    var largerText = false
        private set

    fun initialize(plugin: Context, host: Context) {
        resources = plugin.resources
        regular = resources.getFont(R.font.inter_regular)
        semibold = resources.getFont(R.font.inter_semibold)
        preferences = host.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        appearance = if (preferences.getString("appearance", null) == Appearance.GRAY.name) Appearance.GRAY else Appearance.DARK
        reducedMotion = preferences.getBoolean("reduced-motion", false)
        largerText = preferences.getBoolean("larger-text", false)
    }

    /** Standalone scanner/link activities own their window and package resources. */
    fun initializeActivity(activity: android.app.Activity) {
        initialize(activity, activity)
        Appearance.entries.find { it.name == activity.intent.getStringExtra(EXTRA_APPEARANCE) }?.let(::chooseAppearance)
        if (activity.intent.hasExtra(EXTRA_LARGER_TEXT)) chooseLargerText(activity.intent.getBooleanExtra(EXTRA_LARGER_TEXT, false))
        activity.window.setBackgroundDrawable(ColorDrawable(pageColor))
        activity.window.statusBarColor = pageColor
        activity.window.navigationBarColor = pageColor
        val lightBars = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        activity.window.decorView.systemUiVisibility = if (appearance == Appearance.GRAY)
            activity.window.decorView.systemUiVisibility or lightBars
        else activity.window.decorView.systemUiVisibility and lightBars.inv()
    }

    fun chooseAppearance(selected: Appearance) {
        appearance = selected
        preferences.edit().putString("appearance", selected.name).apply()
    }

    fun chooseReducedMotion(selected: Boolean) {
        reducedMotion = selected
        preferences.edit().putBoolean("reduced-motion", selected).apply()
    }

    fun chooseLargerText(selected: Boolean) {
        largerText = selected
        preferences.edit().putBoolean("larger-text", selected).apply()
    }

    fun color(tone: Tone) = resources.getColor(if (appearance == Appearance.GRAY) tone.gray else tone.dark, null)
    fun drawable(resource: Int) = resources.getDrawable(resource, null).mutate()
    val pageColor get() = color(Tone.PAGE)
    val surfaceColor get() = color(Tone.SURFACE)
    val textColor get() = color(Tone.TEXT)
    val secondaryColor get() = color(Tone.SECONDARY)
    val accentColor get() = color(Tone.ACCENT)
    val lineColor get() = color(Tone.LINE)

    fun dp(view: View, value: Int) = (value * view.resources.displayMetrics.density + .5f).toInt()
    fun pageInset(widthDp: Int) = when { widthDp <= 360 -> 12; widthDp >= 560 -> 22; else -> INSET }

    fun background(view: View, tone: Tone = Tone.PAGE) = ColorDrawable(color(tone)).also {
        view.setTag(R.id.arachne_surface_role, tone)
    }

    fun surface(view: View) = GradientDrawable(GradientDrawable.Orientation.TL_BR,
        intArrayOf(highlight(surfaceColor), surfaceColor)).apply {
        cornerRadius = dp(view, 5).toFloat()
        setStroke(dp(view, 1), lineColor)
        view.setTag(R.id.arachne_surface_role, true)
    }

    /** Static vector engraving shares the header's existing area and scroll owner. */
    fun engravedSurface(view: View, empty: Boolean = false): android.graphics.drawable.Drawable {
        view.setTag(R.id.arachne_engraved_surface, empty)
        val pattern = drawable(R.drawable.arachne_weave).apply {
            setTint(textColor)
            alpha = if (appearance == Appearance.GRAY) 23 else 19
        }
        val base = if (empty) face(view, pageColor) else ColorDrawable(surfaceColor)
        return object : android.graphics.drawable.LayerDrawable(arrayOf(base, pattern)) {
            // The engraving must not become the View's suggested minimum size.
            override fun getMinimumWidth() = base.minimumWidth
            override fun getMinimumHeight() = base.minimumHeight
            override fun draw(canvas: android.graphics.Canvas) {
                val saved = canvas.save()
                canvas.clipRect(bounds)
                super.draw(canvas)
                canvas.restoreToCount(saved)
            }
        }.apply {
            setLayerSize(1, dp(view, 320), dp(view, 180))
            setLayerGravity(1, android.view.Gravity.RIGHT or android.view.Gravity.CENTER_VERTICAL)
        }
    }

    fun tile(view: View) {
        view.setTag(R.id.arachne_tile_surface, true)
        view.background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(surfaceColor, color(Tone.SOFT))).apply {
            cornerRadius = dp(view, 6).toFloat()
            setStroke(dp(view, 1), lineColor)
        }
    }

    fun actionIcon(view: TextView, resource: Int) {
        val drawable = drawable(resource).apply {
            setTintList(view.textColors)
            setBounds(0, 0, dp(view, ICON), dp(view, ICON))
        }
        view.compoundDrawablePadding = dp(view, if (view is Button && view.text.isBlank()) 0 else 7)
        view.setCompoundDrawablesRelative(drawable, null, null, null)
    }

    fun text(view: TextView, type: Type = Type.BODY, tone: Tone = Tone.TEXT) {
        view.setTag(R.id.arachne_text_role, type)
        view.setTag(R.id.arachne_text_tone, tone)
        view.textSize = type.sp * if (largerText) 1.2f else 1f
        view.setTextColor(color(tone))
        view.typeface = if (type.semibold) semibold else regular
        view.includeFontPadding = false
        view.fontFeatureSettings = "tnum"
        view.setLineSpacing(0f, when (type) {
            Type.TITLE, Type.STAT -> 1.15f
            Type.ACTION, Type.LABEL, Type.FIELD -> 1.25f
            else -> 1.4f
        })
    }

    private fun highlight(fill: Int): Int {
        if (android.graphics.Color.alpha(fill) == 0) return fill
        fun channel(base: Int, ink: Int) = (base + (ink - base) * .035f).toInt()
        return android.graphics.Color.argb(android.graphics.Color.alpha(fill),
            channel(android.graphics.Color.red(fill), android.graphics.Color.red(textColor)),
            channel(android.graphics.Color.green(fill), android.graphics.Color.green(textColor)),
            channel(android.graphics.Color.blue(fill), android.graphics.Color.blue(textColor)))
    }

    private fun face(view: View, fill: Int, stroke: Int = color(Tone.LINE), radius: Int = 5) =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(highlight(fill), fill)).apply {
        cornerRadius = dp(view, radius).toFloat()
        setStroke(dp(view, 1), stroke)
    }

    fun feedback(view: View, fill: Int = surfaceColor, quiet: Boolean = false): android.graphics.drawable.Drawable {
        val faces = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), face(view, fill, color(Tone.FOCUS)))
            addState(intArrayOf(), if (quiet) ColorDrawable(fill) else face(view, fill))
        }
        if (!ArachneMotion.enabled()) return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), face(view, color(Tone.SOFT)))
            addState(intArrayOf(), faces)
        }
        return RippleDrawable(ColorStateList.valueOf(color(Tone.SOFT)), faces, null)
    }

    fun button(view: Button, kind: Action = Action.SECONDARY) {
        view.setTag(R.id.arachne_button_kind, kind)
        text(view, Type.ACTION)
        view.isAllCaps = false
        view.minHeight = dp(view, TOUCH)
        view.minimumHeight = dp(view, TOUCH)
        view.minWidth = 0
        view.minimumWidth = 0
        val foreground = when (kind) {
            Action.PRIMARY -> color(Tone.ON_ACCENT)
            Action.DESTRUCTIVE -> color(Tone.DANGER)
            Action.QUIET -> accentColor
            Action.SECONDARY -> textColor
        }
        view.setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(secondaryColor, foreground)))
        view.backgroundTintList = null
        view.background = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), face(view, surfaceColor))
            addState(intArrayOf(), feedback(view, when (kind) {
                Action.PRIMARY -> accentColor
                Action.QUIET -> android.graphics.Color.TRANSPARENT
                else -> surfaceColor
            }, kind == Action.QUIET))
        }
        view.setPadding(dp(view, 14), dp(view, 10), dp(view, 14), dp(view, 10))
        view.stateListAnimator = null
        view.elevation = 0f
        (view.getTag(R.id.arachne_action_icon) as? Int)?.let { actionIcon(view, it) }
    }

    fun input(view: EditText) {
        text(view)
        view.backgroundTintList = null
        view.setHintTextColor(secondaryColor)
        view.minHeight = dp(view, TOUCH)
        view.setPadding(dp(view, 12), dp(view, 10), dp(view, 12), dp(view, 10))
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), face(view, surfaceColor, color(Tone.FOCUS)))
            addState(intArrayOf(), face(view, surfaceColor, color(Tone.EDGE)))
        }
        view.imeOptions = view.imeOptions or android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            android.view.inputmethod.EditorInfo.IME_FLAG_NO_FULLSCREEN
        (view.getTag(R.id.arachne_action_icon) as? Int)?.let { actionIcon(view, it) }
    }

    fun choice(view: CompoundButton) {
        text(view)
        view.minHeight = dp(view, TOUCH)
        view.minimumWidth = dp(view, TOUCH)
        view.buttonTintList = ColorStateList.valueOf(accentColor)
        val hasLabel = view.text.isNotBlank()
        view.setPadding(dp(view, if (hasLabel) 4 else 10), dp(view, 8), dp(view, if (hasLabel) 8 else 0), dp(view, 8))
        val original = view.getTag(R.id.arachne_choice_mark) as? android.graphics.drawable.Drawable ?: view.buttonDrawable
        if (original != null) {
            view.setTag(R.id.arachne_choice_mark, original)
            view.buttonDrawable = android.graphics.drawable.InsetDrawable(original, 0, 0, dp(view, if (hasLabel) 8 else 0), 0)
        }
        if (view is android.widget.Switch) {
            view.thumbTintList = ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(secondaryColor, accentColor))
            view.trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(color(Tone.ACCENT), color(Tone.EDGE)))
            view.switchMinWidth = dp(view, 34)
        }
    }

    fun icon(view: ImageView, tone: Tone = Tone.SECONDARY) {
        view.setTag(R.id.arachne_icon_tone, tone)
        view.imageTintList = ColorStateList.valueOf(color(tone))
        if (view.isClickable) {
            view.backgroundTintList = null
            view.background = feedback(view, android.graphics.Color.TRANSPARENT, true)
        }
    }

    fun brandIcon(view: ImageView) {
        view.setTag(R.id.arachne_icon_tone, null)
        view.imageTintList = null
    }

    /** Retain roles through theme changes instead of flattening status/secondary text. */
    fun applyTree(view: View) {
        when (val role = view.getTag(R.id.arachne_surface_role)) {
            is Tone -> view.background = background(view, role)
            true -> view.background = surface(view)
        }
        if (view.getTag(R.id.arachne_tile_surface) == true) tile(view)
        (view.getTag(R.id.arachne_engraved_surface) as? Boolean)?.let { view.background = engravedSurface(view, it) }
        when (view) {
            is EditText -> input(view)
            is CompoundButton -> choice(view)
            is Button -> button(view, view.getTag(R.id.arachne_button_kind) as? Action ?: Action.SECONDARY)
            is android.widget.CheckedTextView -> {
                text(view)
                view.checkMarkTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(accentColor, secondaryColor))
                view.compoundDrawableTintList = view.checkMarkTintList
            }
            is TextView -> text(view, view.getTag(R.id.arachne_text_role) as? Type ?: Type.BODY,
                view.getTag(R.id.arachne_text_tone) as? Tone ?: Tone.TEXT)
            is ImageView -> (view.getTag(R.id.arachne_icon_tone) as? Tone)?.let { icon(view, it) }
        }
        if (!ArachneMotion.enabled()) ArachneMotion.finish(view)
        if (view is ArachneSignal) view.refresh()
        if (view is ArachneRow) view.refresh()
        if (view is ArachneAvatar) view.refresh()
        if (view is TextView && view.getTag(R.id.arachne_notice_tone) is Tone)
            notice(view, view.getTag(R.id.arachne_notice_tone) as Tone)
        if (view is ViewGroup) for (index in 0 until view.childCount) applyTree(view.getChildAt(index))
        if (view is ArachneHeader) view.refreshLayout()
        if (view is ArachneRow) view.refreshLayout()
        view.invalidate()
    }

    // Android framework resource IDs belong to both APKs. Plugin colors/fonts are
    // resolved above and applied as values; no plugin style is resolved by ATAK.
    fun dialogTheme() = if (appearance == Appearance.GRAY) android.R.style.Theme_Material_Light_Dialog_Alert
        else android.R.style.Theme_Material_Dialog_Alert

    fun dialog(host: Context, pane: View? = null): AlertDialog.Builder = object : AlertDialog.Builder(host, dialogTheme()) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialog.create() // Inflate before attachment, so the first frame is themed.
            dialog.window?.setBackgroundDrawable(ColorDrawable(surfaceColor))
            val content = dialog.findViewById<View>(android.R.id.content)
            content?.setBackgroundColor(surfaceColor)
            content?.let(::applyTree)
            dialog.listView?.apply {
                setBackgroundColor(surfaceColor)
                selector = feedback(this, surfaceColor, true)
                // ListView attaches/recycles rows without ordinary addView calls.
                setOnScrollListener(object : android.widget.AbsListView.OnScrollListener {
                    override fun onScrollStateChanged(view: android.widget.AbsListView, state: Int) {}
                    override fun onScroll(view: android.widget.AbsListView, first: Int, count: Int, total: Int) {
                        for (index in 0 until view.childCount) applyTree(view.getChildAt(index))
                    }
                })
            }
            if (pane != null && pane.width > 0 && pane.height > 0)
                dialog.window?.let { fitWindow(it, pane, fillHeight = false) }
            dialogs.add(dialog)
            dialog.setOnDismissListener { dialogs.remove(dialog) }
        }
    }

    /** Arachne windows follow the delivered ATAK pane, not physical screen ratios. */
    fun fitWindow(window: android.view.Window, pane: View, fillHeight: Boolean = true) {
        val location = IntArray(2)
        pane.getLocationOnScreen(location)
        val visible = android.graphics.Rect()
        pane.getWindowVisibleDisplayFrame(visible)
        window.setGravity(android.view.Gravity.TOP or android.view.Gravity.LEFT)
        val height = if (fillHeight) pane.height else {
            window.decorView.measure(View.MeasureSpec.makeMeasureSpec(pane.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(pane.height, View.MeasureSpec.AT_MOST))
            window.decorView.measuredHeight.coerceAtMost(pane.height)
        }
        window.attributes = window.attributes.apply {
            x = location[0] - visible.left
            y = location[1] - visible.top
            width = pane.width
            this.height = height
        }
        // A fixed pane height otherwise survives adjustResize on the host's
        // floating window. Read the host frame: the dialog's own frame can stay
        // cached at the shortened height after the IME closes.
        val decor = window.decorView
        val resize = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            pane.getWindowVisibleDisplayFrame(visible)
            if (visible.isEmpty) return@OnGlobalLayoutListener
            val available = height.coerceAtMost(visible.height().coerceAtLeast(1))
            val top = location[1].coerceIn(visible.top, visible.bottom - available) - visible.top
            if (window.attributes.height != available || window.attributes.y != top) {
                window.attributes = window.attributes.apply { this.height = available; y = top }
            }
        }
        decor.setOnApplyWindowInsetsListener { view, insets ->
            // Insets can change without a layout when the shortened window
            // already fits, including when a nested choice closes the keyboard.
            view.post { if (view.isAttachedToWindow) resize.onGlobalLayout() }
            view.onApplyWindowInsets(insets)
        }
        decor.viewTreeObserver.addOnGlobalLayoutListener(resize)
        pane.viewTreeObserver.addOnGlobalLayoutListener(resize)
        decor.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) {}
            override fun onViewDetachedFromWindow(view: View) {
                view.viewTreeObserver.removeOnGlobalLayoutListener(resize)
                pane.viewTreeObserver.removeOnGlobalLayoutListener(resize)
                view.setOnApplyWindowInsetsListener(null)
                view.removeOnAttachStateChangeListener(this)
            }
        })
    }

    fun closeDialogs() { dialogs.toList().forEach { it.dismiss() }; dialogs.clear() }

    fun tab(view: Button, selected: Boolean) {
        button(view, Action.QUIET)
        view.isSingleLine = true
        text(view, Type.FIELD, if (selected) Tone.TEXT else Tone.SECONDARY)
        view.isSelected = selected
        view.setPadding(dp(view, 4), dp(view, 8), dp(view, 4), dp(view, 8))
        view.background = if (selected) android.graphics.drawable.LayerDrawable(arrayOf(
            GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(surfaceColor, color(Tone.SOFT))),
            ColorDrawable(accentColor)
        )).apply {
            setLayerSize(1, dp(view, 24), dp(view, 2))
            setLayerGravity(1, android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL)
        } else feedback(view, surfaceColor, true)
    }

    fun separator(view: View, space: Int, horizontal: Boolean = false) = android.graphics.drawable.ShapeDrawable().apply {
        paint.color = android.graphics.Color.TRANSPARENT
        if (horizontal) intrinsicWidth = dp(view, space) else intrinsicHeight = dp(view, space)
    }

    fun notice(view: TextView, tone: Tone = Tone.SECONDARY) {
        view.setTag(R.id.arachne_notice_tone, tone)
        text(view, Type.SUPPORTING, tone)
        view.background = android.graphics.drawable.LayerDrawable(arrayOf(ColorDrawable(surfaceColor), ColorDrawable(color(tone)))).apply {
            setLayerSize(1, dp(view, 2), -1)
            setLayerGravity(1, android.view.Gravity.START or android.view.Gravity.FILL_VERTICAL)
        }
        view.setPadding(dp(view, 12), dp(view, 10), dp(view, 12), dp(view, 10))
    }
}
