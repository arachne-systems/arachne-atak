package dev.arachne.atak

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.arachne.atak.ArachneStyle.Type
import dev.arachne.atak.ArachneStyle.Tone

/** Small Android View components; no ATAK lifecycle or workspace behavior lives here. */
internal object ArachneComponents {
    enum class Icon(val resource: Int) {
        BACK(R.drawable.arachne_ic_back), MENU(R.drawable.arachne_ic_menu), MORE(R.drawable.arachne_ic_more),
        CHEVRON(R.drawable.arachne_ic_chevron), PLUS(R.drawable.arachne_ic_plus), CLOSE(R.drawable.arachne_ic_close),
        SEARCH(R.drawable.arachne_ic_search), PEOPLE(R.drawable.arachne_ic_people), LINK(R.drawable.arachne_ic_link),
        SCAN(R.drawable.arachne_ic_scan), WIFI(R.drawable.arachne_ic_wifi), SETTINGS(R.drawable.arachne_ic_settings),
        CHART(R.drawable.arachne_ic_chart), HELP(R.drawable.arachne_ic_help), ACTIVITY(R.drawable.arachne_ic_activity),
        DOWNLOAD(R.drawable.arachne_ic_download), COPY(R.drawable.arachne_ic_copy), BOOK(R.drawable.arachne_ic_book),
        MAP(R.drawable.arachne_ic_map), PAUSE(R.drawable.arachne_ic_pause), PLAY(R.drawable.arachne_ic_play),
        MAIL(R.drawable.arachne_ic_mail), CLOCK(R.drawable.arachne_ic_clock), EXIT(R.drawable.arachne_ic_exit)
    }

    fun stack(context: Context, gap: Int = ArachneStyle.GROUP, vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        dividerDrawable = ArachneStyle.separator(this, gap)
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        views.forEach { addView(it, LinearLayout.LayoutParams(-1, it.layoutParams?.height ?: -2)) }
    }

    /** Lists and details keep navigation fixed. Forms also keep actions fixed,
     * except when a short keyboard viewport requires all form content to scroll. */
    fun page(context: Context, header: View, scroll: android.widget.ScrollView, footer: View? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = ArachneStyle.background(this)
        addView(header, LinearLayout.LayoutParams(-1, -2))
        addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        if (footer != null) {
            val content = requireNotNull(scroll.getChildAt(0) as? LinearLayout) { "Form content must be a vertical stack" }
            addView(footer, LinearLayout.LayoutParams(-1, -2))
            addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
                val parent = if (bottom - top < ArachneStyle.dp(this, 180)) content else this
                if (header.parent !== parent) {
                    (header.parent as android.view.ViewGroup).removeView(header)
                    parent.addView(header, 0, LinearLayout.LayoutParams(-1, -2))
                }
                if (footer.parent !== parent) {
                    (footer.parent as android.view.ViewGroup).removeView(footer)
                    parent.addView(footer, LinearLayout.LayoutParams(-1, -2))
                }
            }
        }
    }

    fun actions(context: Context, vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        dividerDrawable = ArachneStyle.separator(this, ArachneStyle.GAP, horizontal = true)
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        views.forEach { addView(it, LinearLayout.LayoutParams(0, -1, 1f)) }
    }

    fun toolbar(context: Context, leading: View, vararg actions: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        dividerDrawable = ArachneStyle.separator(this, ArachneStyle.GAP, horizontal = true)
        showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        addView(leading, LinearLayout.LayoutParams(0, -2, 1f))
        actions.forEach { addView(it, LinearLayout.LayoutParams(-2, -2)) }
    }

    fun stats(context: Context, vararg cards: View) = object : android.widget.GridLayout(context) {
        init { cards.forEach { addView(it) } }
        private var arrangedColumns = 0
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val widthDp = View.MeasureSpec.getSize(widthMeasureSpec) / resources.displayMetrics.density
            val columns = minOf(cards.size, if (widthDp >= 516) 3 else 2).coerceAtLeast(1)
            if (columns != arrangedColumns) {
                // Clear old explicit specs before decreasing the column count.
                cards.forEach { it.layoutParams = android.widget.GridLayout.LayoutParams() }
                columnCount = columns
                arrangedColumns = columns
                cards.forEachIndexed { index, card ->
                    card.layoutParams = android.widget.GridLayout.LayoutParams(
                        android.widget.GridLayout.spec(index / columns), android.widget.GridLayout.spec(index % columns, 1f)).apply {
                        width = 0; height = -2
                        setGravity(Gravity.FILL)
                        if (index % columns != columns - 1) marginEnd = ArachneStyle.dp(card, 8)
                        if (index / columns < (cards.size - 1) / columns) bottomMargin = ArachneStyle.dp(card, 8)
                    }
                }
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        }
    }

    fun section(context: Context, title: String, vararg views: View) = stack(context, ArachneStyle.GAP,
        label(context, title, Type.LABEL).apply {
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, *views)

    fun details(context: Context, title: String, vararg views: View): LinearLayout {
        val body = stack(context, ArachneStyle.GAP, *views).apply { visibility = View.GONE }
        lateinit var toggle: Button
        toggle = action(context, "Show $title", ArachneStyle.Action.QUIET) {
            val expanded = body.visibility != View.VISIBLE
            body.visibility = if (expanded) View.VISIBLE else View.GONE
            toggle.text = (if (expanded) "Hide " else "Show ") + title
        }
        return stack(context, ArachneStyle.GAP, toggle, body)
    }

    fun note(context: Context, value: String) = label(context, value, Type.SUPPORTING, Tone.SECONDARY)
    fun intro(context: Context, value: String) = label(context, value, Type.INTRO, Tone.SECONDARY)

    fun notice(context: Context, value: String, tone: Tone = Tone.SECONDARY) = label(context, value).apply {
        ArachneStyle.notice(this, tone)
    }

    fun rule(context: Context) = View(context).apply {
        background = ArachneStyle.background(this, Tone.LINE)
        layoutParams = LinearLayout.LayoutParams(-1, ArachneStyle.dp(this, 1))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun link(context: Context, title: String, description: String, symbol: Icon? = null, click: () -> Unit) =
        ArachneRow(context, title, description, "", ArachneSignal.State.NEUTRAL, "$title. $description", click,
            showAvatar = false, leadingIcon = symbol)

    fun keyValue(context: Context, title: String, value: String) = keyValue(context, title, label(context, value, Type.SUPPORTING))

    fun keyValue(context: Context, title: String, value: TextView, titleTone: Tone = Tone.SECONDARY) = stack(context, 0,
        LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            val gap = ArachneStyle.dp(this, ArachneStyle.GROUP)
            setPadding(0, gap, 0, gap)
            addView(label(context, title, Type.SUPPORTING, titleTone), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = gap })
            addView(value.apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        }, rule(context))

    fun identity(context: Context, title: String, value: String) = stack(context, 6,
        label(context, title, Type.FIELD, Tone.SECONDARY), label(context, value, Type.SUPPORTING).apply {
            setTextIsSelectable(true)
            background = ArachneStyle.surface(this)
            val inset = ArachneStyle.dp(this, ArachneStyle.GROUP)
            setPadding(inset, inset, inset, inset)
        })

    fun toggle(context: Context, title: String, description: String, control: android.widget.CompoundButton) =
        toolbar(context, stack(context, 4, label(context, title, Type.LABEL), note(context, description)), control).apply {
            minimumHeight = ArachneStyle.dp(this, 64)
            setPadding(0, ArachneStyle.dp(this, 8), 0, ArachneStyle.dp(this, 8))
            ArachneStyle.choice(control)
        }

    fun empty(context: Context, title: String, description: String) = stack(context, ArachneStyle.GAP,
        label(context, title, Type.LABEL).apply { gravity = Gravity.CENTER },
        intro(context, description).apply { gravity = Gravity.CENTER }).apply {
            val inset = ArachneStyle.dp(this, ArachneStyle.GROUP)
            setPadding(inset, ArachneStyle.dp(this, 24), inset, ArachneStyle.dp(this, 24))
            background = ArachneStyle.engravedSurface(this, empty = true)
        }

    fun elapsed(milliseconds: Long): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${(seconds % 60).toString().padStart(2, '0')}s"
            seconds < 86400 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
            else -> "${seconds / 86400}d ${(seconds % 86400) / 3600}h"
        }
    }

    fun label(context: Context, value: String, type: Type = Type.BODY, tone: Tone = Tone.TEXT) = TextView(context).apply {
        text = value
        ArachneStyle.text(this, type, tone)
        if (android.os.Build.VERSION.SDK_INT >= 28 && type in setOf(Type.TITLE, Type.SECTION)) isAccessibilityHeading = true
    }

    fun action(context: Context, label: String, kind: ArachneStyle.Action = ArachneStyle.Action.SECONDARY,
               click: () -> Unit) = Button(context).apply {
        text = label
        ArachneStyle.button(this, kind)
        setOnClickListener { click() }
    }

    fun symbol(context: Context, symbol: Icon, tone: Tone = Tone.SECONDARY) = ImageView(context).apply {
        setImageDrawable(ArachneStyle.drawable(symbol.resource))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = ArachneStyle.dp(this, 7)
        setPadding(inset, inset, inset, inset)
        ArachneStyle.tile(this)
        ArachneStyle.icon(this, tone)
    }

    fun actionIcon(button: Button, symbol: Icon) {
        button.setTag(R.id.arachne_action_icon, symbol.resource)
        ArachneStyle.actionIcon(button, symbol.resource)
    }

    fun icon(context: Context, symbol: Icon, label: String, click: () -> Unit) = ImageButton(context).apply {
        contentDescription = label
        setImageDrawable(ArachneStyle.drawable(symbol.resource))
        scaleType = ImageView.ScaleType.FIT_CENTER
        val inset = ArachneStyle.dp(this, (ArachneStyle.TOUCH - ArachneStyle.ICON) / 2)
        setPadding(inset, inset, inset, inset)
        minimumWidth = ArachneStyle.dp(this, ArachneStyle.TOUCH)
        minimumHeight = ArachneStyle.dp(this, ArachneStyle.TOUCH)
        setOnClickListener { click() }
        ArachneStyle.icon(this)
    }
}

internal class ArachneHeader(context: Context, onBack: () -> Unit, onMenu: () -> Unit) : LinearLayout(context) {
    val title = ArachneComponents.label(context, "Workspaces", Type.TITLE).apply {
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val subtitle = ArachneComponents.label(context, "", Type.CAPTION, Tone.SECONDARY).apply {
        visibility = GONE; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    val back = ArachneComponents.icon(context, ArachneComponents.Icon.BACK, "Back", onBack)
    val menu = ArachneComponents.icon(context, ArachneComponents.Icon.MENU, "App menu", onMenu)
    val workspaceMenu = ArachneComponents.icon(context, ArachneComponents.Icon.MORE, "Workspace actions", {}).apply { visibility = GONE }
    private val mark = ImageView(context).apply {
        setImageDrawable(ArachneStyle.drawable(R.drawable.fabric_icon))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        ArachneStyle.brandIcon(this)
    }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ArachneStyle.dp(this, ArachneStyle.HEADER)
        setPadding(ArachneStyle.dp(this, 10), ArachneStyle.dp(this, 2), ArachneStyle.dp(this, 10), ArachneStyle.dp(this, 2))
        background = ArachneStyle.engravedSurface(this)
        addView(mark, LayoutParams(ArachneStyle.dp(this, ArachneStyle.MARK), ArachneStyle.dp(this, ArachneStyle.MARK)).apply {
            leftMargin = ArachneStyle.dp(this@ArachneHeader, 5); rightMargin = ArachneStyle.dp(this@ArachneHeader, 11)
        })
        addView(back, LayoutParams(ArachneStyle.dp(this, ArachneStyle.TOUCH), ArachneStyle.dp(this, ArachneStyle.TOUCH)))
        addView(ArachneComponents.stack(context, 3, subtitle, title), LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = ArachneStyle.dp(this@ArachneHeader, 6)
            marginEnd = ArachneStyle.dp(this@ArachneHeader, 6)
        })
        addView(workspaceMenu, LayoutParams(ArachneStyle.dp(this, ArachneStyle.TOUCH), ArachneStyle.dp(this, ArachneStyle.TOUCH)))
        addView(menu, LayoutParams(ArachneStyle.dp(this, ArachneStyle.TOUCH), ArachneStyle.dp(this, ArachneStyle.TOUCH)))
        showBack(false)
    }
    fun showBack(show: Boolean) {
        back.visibility = if (show) View.VISIBLE else View.GONE
        mark.visibility = if (show) View.GONE else View.VISIBLE
    }
    fun refreshLayout() {
        val widthDp = width / resources.displayMetrics.density
        val inset = ArachneStyle.dp(this, if (widthDp in 1f..400f) 5 else 10)
        setPadding(inset, ArachneStyle.dp(this, 2), inset, ArachneStyle.dp(this, 2))
        ArachneStyle.text(title, if (widthDp in 1f..360f) Type.SECTION else Type.TITLE)
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw) refreshLayout()
    }
}

/** A semantic signal always includes a readable label, even when color or motion is unavailable. */
internal class ArachneSignal(context: Context, label: String, state: State) : TextView(context) {
    enum class State(val tone: Tone) { ACTIVE(Tone.SUCCESS), WAITING(Tone.WARNING), FAILED(Tone.DANGER), ACTIVITY(Tone.ACTIVITY), NEUTRAL(Tone.SECONDARY) }
    var state = state
        private set
    init { text = label; refresh() }
    fun update(label: String, next: State) {
        val changed = next != state
        text = label; state = next; refresh()
        if (changed) ArachneMotion.changed(this)
    }
    fun refresh() {
        ArachneStyle.text(this, Type.SUPPORTING, state.tone)
        val size = ArachneStyle.dp(this, 8)
        val marker = object : Drawable() {
            private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            override fun draw(canvas: Canvas) {
                paint.color = ArachneStyle.color(this@ArachneSignal.state.tone)
                paint.style = if (this@ArachneSignal.state == State.NEUTRAL) Paint.Style.STROKE else Paint.Style.FILL
                paint.strokeWidth = size / 6f
                val x = bounds.exactCenterX(); val y = bounds.exactCenterY(); val radius = size * .36f
                when (this@ArachneSignal.state) {
                    State.FAILED -> canvas.drawRect(x - radius, y - radius, x + radius, y + radius, paint)
                    State.WAITING -> {
                        val saved = canvas.save(); canvas.rotate(45f, x, y)
                        canvas.drawRect(x - radius, y - radius, x + radius, y + radius, paint); canvas.restoreToCount(saved)
                    }
                    else -> canvas.drawCircle(x, y, radius, paint)
                }
            }
            override fun setAlpha(alpha: Int) { paint.alpha = alpha; invalidateSelf() }
            override fun setColorFilter(filter: android.graphics.ColorFilter?) { paint.colorFilter = filter; invalidateSelf() }
            @Deprecated("Drawable opacity is unused on modern Android")
            override fun getOpacity() = PixelFormat.TRANSLUCENT
        }.apply { setBounds(0, 0, size, size) }
        compoundDrawablePadding = ArachneStyle.dp(this, 6)
        setCompoundDrawablesRelative(marker, null, null, null)
    }
    override fun onDetachedFromWindow() { ArachneMotion.finish(this); super.onDetachedFromWindow() }
}

internal class ArachneAvatar(context: Context, name: String) : TextView(context) {
    init {
        gravity = Gravity.CENTER
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        update(name)
    }
    fun update(name: String) {
        text = name.trim().removeSuffix(" (you)").split(Regex("\\s+")).filter { it.isNotBlank() }.take(2)
            .joinToString("") { it.substring(0, it.offsetByCodePoints(0, 1)).uppercase() }.ifBlank { "?" }
        refresh()
    }
    fun refresh() {
        ArachneStyle.text(this, Type.FIELD, Tone.ACCENT)
        ArachneStyle.tile(this)
    }
}

internal class ArachneTrafficChart(context: Context) : View(context) {
    private var samples = emptyList<TrafficSample>()
    private var rates = emptyList<Triple<Long, Float, Float>>()
    private var ceiling = 1f
    internal var scaleLabels = listOf("1 B/s", "0.5 B/s", "0 B/s")
        private set
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val axisPaint = Paint(ArachneComponents.note(context, "").paint)
    fun update(value: List<TrafficSample>) {
        samples = value
        rates = value.zipWithNext().map { (a, b) ->
            val seconds = (b.elapsedMs - a.elapsedMs).coerceAtLeast(1) / 1000f
            Triple(b.elapsedMs, (b.received - a.received) / seconds, (b.sent - a.sent) / seconds)
        }
        val peak = rates.maxOfOrNull { maxOf(it.second, it.third) }?.coerceAtLeast(1f) ?: 1f
        val (unit, name) = when {
            peak >= 1024 * 1024 -> 1024f * 1024 to "MiB/s"
            peak >= 1024 -> 1024f to "KiB/s"
            else -> 1f to "B/s"
        }
        ceiling = kotlin.math.ceil(peak / unit) * unit
        scaleLabels = listOf(ceiling, ceiling / 2, 0f).map {
            String.format(java.util.Locale.US, "%.1f", it / unit).removeSuffix(".0") + " $name"
        }
        contentDescription = "Transport traffic over the last ten minutes. Scale: 0 to ${scaleLabels.first()}, bytes per second. " +
            "Receive is cyan and solid; Send is amber and dashed. " +
            if (value.size < 2) "Collecting samples." else "${value.size} measured samples."
        invalidate()
    }
    init {
        minimumHeight = ArachneStyle.dp(this, 104)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = ArachneStyle.dp(this, 3).toFloat()
        axisPaint.color = ArachneStyle.color(Tone.SECONDARY)
        axisPaint.textAlign = Paint.Align.RIGHT
        val font = axisPaint.fontMetrics
        val halfLabel = (font.descent - font.ascent) / 2
        val top = inset + halfLabel
        val bottom = height - inset - halfLabel
        val labelRight = inset + scaleLabels.maxOf(axisPaint::measureText)
        val left = labelRight + ArachneStyle.dp(this, 8)
        val right = width - inset
        paint.color = ArachneStyle.lineColor; paint.strokeWidth = 1f; paint.pathEffect = null
        for (line in 0..2) {
            val y = top + (bottom - top) * line / 2
            canvas.drawText(scaleLabels[line], labelRight, y - (font.ascent + font.descent) / 2, axisPaint)
            if (right > left) canvas.drawLine(left, y, right, y, paint)
        }
        if (samples.size < 2 || right <= left || bottom <= top) return
        val end = samples.last().elapsedMs
        for (received in listOf(true, false)) {
            paint.color = ArachneStyle.color(if (received) Tone.RECEIVE else Tone.SEND)
            paint.strokeWidth = ArachneStyle.dp(this, if (received) 3 else 2).toFloat()
            paint.style = android.graphics.Paint.Style.STROKE
            paint.pathEffect = if (received) null else android.graphics.DashPathEffect(
                floatArrayOf(ArachneStyle.dp(this, 10).toFloat(), ArachneStyle.dp(this, 6).toFloat()), 0f)
            val path = android.graphics.Path()
            rates.forEachIndexed { index, sample ->
                val x = left + (right - left) * (1f - (end - sample.first) / 600_000f).coerceIn(0f, 1f)
                val y = bottom - (bottom - top) * (if (received) sample.second else sample.third) / ceiling
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
                if (rates.size == 1) canvas.drawPoint(x, y, paint)
            }
            canvas.drawPath(path, paint)
        }
        paint.pathEffect = null; paint.style = android.graphics.Paint.Style.FILL
    }
}

internal class ArachneStat(context: Context, title: String) : LinearLayout(context) {
    val value = ArachneComponents.label(context, "—", Type.STAT)
    init {
        orientation = VERTICAL
        dividerDrawable = ArachneStyle.separator(this, 6)
        showDividers = SHOW_DIVIDER_MIDDLE
        background = ArachneStyle.surface(this)
        val inset = ArachneStyle.dp(this, ArachneStyle.GROUP)
        setPadding(inset, inset, inset, inset)
        addView(ArachneComponents.note(context, title))
        addView(value)
    }
}

internal class ArachneRow(context: Context, name: String, detail: String, status: String,
                          state: ArachneSignal.State, openLabel: String, onOpen: () -> Unit,
                          onMore: (() -> Unit)? = null, showAvatar: Boolean = true,
                          leadingIcon: ArachneComponents.Icon? = null, trailingControl: View? = null,
                          private val separateStatus: Boolean = false) : LinearLayout(context) {
    private val inlineStatus = onMore == null
    private var detailText = detail
    private val avatar = ArachneAvatar(context, name)
    val signal = ArachneSignal(context, status, state)
    val more = ArachneComponents.icon(context,
        if (inlineStatus) ArachneComponents.Icon.CHEVRON else ArachneComponents.Icon.MORE,
        if (inlineStatus) openLabel else "Actions for $name", { (onMore ?: onOpen)() })
    private val nameView = ArachneComponents.label(context, name, Type.LABEL).apply {
        maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val detailView = ArachneComponents.label(context, detail, Type.SUPPORTING, Tone.SECONDARY).apply {
        setPadding(0, ArachneStyle.dp(this, 3), 0, 0)
    }
    val body = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ArachneStyle.dp(this, ArachneStyle.TOUCH)
        setPadding(0, ArachneStyle.dp(this, 6), ArachneStyle.dp(this, 8), ArachneStyle.dp(this, 6))
        addView(nameView)
        addView(detailView)
        if (separateStatus) addView(signal, LayoutParams(-2, -2).apply { topMargin = ArachneStyle.dp(signal, 3) })
        contentDescription = openLabel
        isFocusable = true
        setOnClickListener { onOpen() }
        background = ArachneStyle.feedback(this, ArachneStyle.pageColor, true)
    }
    private val line = Paint()
    init {
        orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ArachneStyle.dp(this, if (showAvatar) ArachneStyle.ROW else 60)
        setPadding(0, ArachneStyle.dp(this, 8), 0, ArachneStyle.dp(this, 8))
        addView(avatar, LayoutParams(ArachneStyle.dp(this, 34), ArachneStyle.dp(this, 34)).apply {
            marginEnd = ArachneStyle.dp(this@ArachneRow, 10)
        })
        avatar.visibility = if (showAvatar && leadingIcon == null) VISIBLE else GONE
        if (leadingIcon != null) addView(ArachneComponents.symbol(context, leadingIcon), 1,
            LayoutParams(ArachneStyle.dp(this, 34), ArachneStyle.dp(this, 34)).apply {
                marginEnd = ArachneStyle.dp(this@ArachneRow, 10)
            })
        addView(body, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        if (!inlineStatus && !separateStatus) {
            signal.maxWidth = ArachneStyle.dp(this, 80)
            addView(signal, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                marginEnd = ArachneStyle.dp(this@ArachneRow, 4)
            })
        }
        addView(trailingControl ?: more, LayoutParams(ArachneStyle.dp(this, ArachneStyle.TOUCH), ArachneStyle.dp(this, ArachneStyle.TOUCH)))
        setWillNotDraw(false)
        updateStatus(status, state)
    }
    fun update(name: String, detail: String, status: String, state: ArachneSignal.State, openLabel: String) {
        if (nameView.text.toString() != name) { nameView.text = name; avatar.update(name) }
        detailText = detail
        updateStatus(status, state)
        body.contentDescription = openLabel
        more.contentDescription = if (inlineStatus) openLabel else "Actions for $name"
    }
    fun updateStatus(label: String, state: ArachneSignal.State) {
        val changed = signal.state != state
        signal.update(label, state)
        detailView.text = if (inlineStatus && !separateStatus && label.isNotBlank()) {
            val marker = when (state) {
                ArachneSignal.State.WAITING -> "◆"
                ArachneSignal.State.FAILED -> "■"
                ArachneSignal.State.NEUTRAL -> "○"
                else -> "●"
            }
            val status = "$marker $label"
            val caption = listOf(detailText, status).filter { it.isNotBlank() }.joinToString(" · ")
            android.text.SpannableString(caption).apply {
                setSpan(android.text.style.ForegroundColorSpan(ArachneStyle.color(state.tone)),
                    caption.length - status.length, caption.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else detailText
        if (changed && inlineStatus) ArachneMotion.changed(detailView)
    }
    fun refresh() {
        body.background = ArachneStyle.feedback(body, ArachneStyle.pageColor, true)
        updateStatus(signal.text.toString(), signal.state)
    }
    fun refreshLayout(widthPx: Int = width) {
        val compact = widthPx / resources.displayMetrics.density in 1f..336f
        for (index in 0 until childCount) {
            val child = getChildAt(index)
            if (child === avatar || child.getTag(R.id.arachne_tile_surface) == true) {
                val size = ArachneStyle.dp(child, if (compact) 30 else 34)
                val margin = ArachneStyle.dp(child, if (compact) 8 else 10)
                val params = child.layoutParams as LayoutParams
                if (params.width != size || params.height != size || params.marginEnd != margin) {
                    child.layoutParams = params.apply { width = size; height = size; marginEnd = margin }
                }
            }
        }
        ArachneStyle.text(nameView, if (compact) Type.ACTION else Type.LABEL)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Size children before LinearLayout measures them, including first paint.
        refreshLayout(View.MeasureSpec.getSize(widthMeasureSpec))
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        line.color = ArachneStyle.lineColor; line.strokeWidth = ArachneStyle.dp(this, 1).toFloat()
        canvas.drawLine(0f, height - line.strokeWidth / 2, width.toFloat(), height - line.strokeWidth / 2, line)
    }
}

/** Bounded, interruptible polish. State changes never depend on an animation finishing. */
internal object ArachneMotion {
    const val STATE_MS = 140L
    fun enabled() = !ArachneStyle.reducedMotion && ValueAnimator.areAnimatorsEnabled()
    fun changed(view: View) {
        finish(view)
        if (!enabled() || !view.isAttachedToWindow || !view.isShown) return
        view.alpha = .72f
        view.animate().alpha(1f).setDuration(STATE_MS).start()
    }
    fun finish(view: View) { view.animate().cancel(); view.alpha = 1f }
    fun finishTree(view: View) {
        finish(view)
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) finishTree(view.getChildAt(i))
    }
}
