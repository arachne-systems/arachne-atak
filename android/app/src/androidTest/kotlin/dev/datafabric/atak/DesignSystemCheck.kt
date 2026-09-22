package dev.arachne.atak

import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/** Real Android resources, fonts, layouts, callbacks and preferences; no fabric state is touched. */
internal object DesignSystemCheck {
    fun checkToolbarIcon(context: Context): String {
        val appIcon = context.packageManager.getApplicationInfo(context.packageName, 0).icon
        check(context.resources.getResourceEntryName(appIcon) == "arachne_toolbar_icon") {
            "ATAK's plugin entry must use the transparent toolbar icon"
        }
        val toolbarIcon = requireNotNull(context.getDrawable(R.drawable.arachne_toolbar_icon))
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            toolbarIcon.setBounds(0, 0, bitmap.width, bitmap.height)
            toolbarIcon.draw(Canvas(bitmap))
            check(listOf(0 to 0, 63 to 0, 0 to 63, 63 to 63).all { (x, y) ->
                Color.alpha(bitmap.getPixel(x, y)) == 0
            }) { "ATAK toolbar icons need transparent corners; opaque brand art is tinted into a white square" }
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            check(pixels.any { Color.alpha(it) != 0 }) { "Toolbar icon artwork is empty" }
        } finally {
            bitmap.recycle()
        }
        return "PASS: ATAK plugin toolbar icon has visible artwork and transparent corners"
    }

    fun run(context: Context, instrumentation: Instrumentation): JSONObject {
        val preferencesName = "design-system-test-${UUID.randomUUID()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(preferencesName, mode)
        }
        val result = JSONObject()
        val failure = AtomicReference<Throwable>()
        instrumentation.runOnMainSync {
            try {
                ArachneStyle.initialize(context, isolated)
                check(ArachneStyle.appearance == ArachneStyle.Appearance.DARK)
                checkToolbarIcon(context)
                check(ArachneComponents.elapsed(-1) == "0s")
                check(ArachneComponents.elapsed(59_999) == "59s")
                check(ArachneComponents.elapsed(60_000) == "1m 00s")
                check(ArachneComponents.elapsed(3_661_000) == "1h 1m")
                check(ArachneComponents.elapsed(86_400_000) == "1d 0h")
                val counters = JSONObject().put("session", JSONArray(List(32) { 1 })).put("received_bytes", 1024L).put("sent_bytes", 512L)
                    .put("receive_queue", 2).put("admission_queue", 0).put("admission_queue_bytes", 0)
                    .put("admission_in_flight", 0).put("approval_pending", 0).put("pending_objects", 3)
                    .put("repair_jobs", 1).put("paths_limited", false)
                    .put("paths", JSONArray().put(JSONObject().put("member", JSONArray(List(32) { 2 })).put("route", "direct").put("rtt_ms", 12)))
                val first = WorkspaceMetrics.read(counters, null, 1000, 4)
                check(first.pending == 9 && first.paths.single().rttMs == 12L)
                val next = WorkspaceMetrics.read(counters.put("received_bytes", 2048L), first, 3000, 0)
                check(next.samples.size == 2 && next.latest.received - first.latest.received == 1024L)
                check(WorkspaceMetrics.read(counters, next, 604_000, 0).samples.size == 1) { "Old graph points retained" }
                check(WorkspaceMetrics.read(counters.put("session", JSONArray(List(32) { 3 })), next, 5000, 0).samples.size == 1) { "Mixed transport sessions" }
                counters.put("session", JSONArray(List(32) { 1 })).put("received_bytes", 0L)
                check(WorkspaceMetrics.read(counters, next, 5000, 0).samples.size == 1) { "Counter reset was graphed as negative traffic" }
                check(runCatching { WorkspaceMetrics.read(counters.put("pending_objects", -1), null, 1000, 0) }.isFailure)
                val activity = WorkspaceActivityView.read(JSONObject().put("activity", JSONObject().put("state", "joining").put("reason", JSONObject.NULL)))
                check(activity.state == "joining" && activity.reason == null)
                check(WorkspaceActivityView.read(JSONObject().put("activity", JSONObject().put("state", "active")), activity).state == "active")
                check(runCatching { WorkspaceActivityView.read(JSONObject().put("activity", JSONObject().put("state", "unknown"))) }.isFailure)
                val chart = ArachneTrafficChart(context)
                for ((amount, expected) in listOf(0L to listOf("1 B/s", "0.5 B/s", "0 B/s"),
                    16L * 1024 to listOf("16 KiB/s", "8 KiB/s", "0 KiB/s"),
                    2L * 1024 * 1024 to listOf("2 MiB/s", "1 MiB/s", "0 MiB/s"))) {
                    chart.update(listOf(TrafficSample(0, 0, 0), TrafficSample(1000, amount, amount / 2)))
                    check(chart.scaleLabels == expected) { "Traffic axis does not match byte rates" }
                    check(chart.contentDescription.contains(expected.first()) && chart.contentDescription.contains("bytes per second"))
                }
                val configurations = JSONArray()
                var clicks = 0
                for (appearance in ArachneStyle.Appearance.entries) {
                    ArachneStyle.chooseAppearance(appearance)
                    ArachneStyle.initialize(context, isolated)
                    check(ArachneStyle.appearance == appearance) { "Appearance did not survive reinitialization" }
                    fun contrast(a: Int, b: Int): Float {
                        val first = Color.luminance(a); val second = Color.luminance(b)
                        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
                    }
                    for (tone in listOf(ArachneStyle.Tone.TEXT, ArachneStyle.Tone.SECONDARY, ArachneStyle.Tone.SUCCESS,
                        ArachneStyle.Tone.WARNING, ArachneStyle.Tone.DANGER, ArachneStyle.Tone.ACTIVITY,
                        ArachneStyle.Tone.RECEIVE, ArachneStyle.Tone.SEND)) {
                        for (surface in listOf(ArachneStyle.Tone.PAGE, ArachneStyle.Tone.SURFACE))
                            check(contrast(ArachneStyle.color(tone), ArachneStyle.color(surface)) >= 4.5f) { "$appearance: $tone on $surface" }
                    }
                    check(contrast(ArachneStyle.color(ArachneStyle.Tone.ON_ACCENT), ArachneStyle.accentColor) >= 4.5f)
                    check(ArachneStyle.pageColor != Color.WHITE && ArachneStyle.surfaceColor != Color.WHITE)
                    val qr = android.widget.ImageView(isolated).apply {
                        setImageBitmap(Bitmap.createBitmap(intArrayOf(Color.BLACK, Color.WHITE), 2, 1, Bitmap.Config.ARGB_8888))
                    }
                    val dialog = ArachneStyle.dialog(isolated).setTitle("Confirmation").setView(qr).create()
                    check((dialog.window!!.decorView.background as android.graphics.drawable.ColorDrawable).color == ArachneStyle.surfaceColor)
                    check(qr.imageTintList == null) { "The dialog theme must preserve QR bitmap colors" }
                    dialog.dismiss()
                    for ((width, height) in listOf(320 to 240, 360 to 280, 480 to 460)) for (scale in listOf(1f, 1.3f)) {
                        val configured = isolated.createConfigurationContext(Configuration(isolated.resources.configuration).apply { fontScale = scale })
                        val header = ArachneHeader(configured, { clicks++ }, { clicks++ })
                        val content = LinearLayout(configured).apply {
                            orientation = LinearLayout.VERTICAL
                            val inset = ArachneStyle.dp(this, ArachneStyle.INSET)
                            setPadding(inset, 0, inset, 0)
                        }
                        val row = ArachneRow(configured, "Joint Operations with a long workspace name", "3 members · Sharing on", "Active",
                            ArachneSignal.State.ACTIVE, "Open Joint Operations", { clicks++ }, { clicks++ })
                        content.addView(row)
                        content.addView(ArachneRow(configured, "Incident Response", "Membership retained", "Paused", ArachneSignal.State.NEUTRAL, "Open Incident Response", {}, {}))
                        content.addView(ArachneRow(configured, "Air Support", "Join request saved", "Waiting", ArachneSignal.State.WAITING, "Open Air Support", {}, {}))
                        val field = android.widget.EditText(configured).apply { hint = "Your name"; ArachneStyle.input(this) }
                        content.addView(field)
                        content.addView(ArachneTrafficChart(configured).apply { update(next.samples) })
                        val measurement = ArachneComponents.label(configured, "Awaiting measurements", ArachneStyle.Type.SUPPORTING)
                        val measurementRow = ArachneComponents.keyValue(configured, "Fastest transport RTT", measurement)
                        content.addView(measurementRow)
                        val button = ArachneComponents.action(configured, "Create workspace", ArachneStyle.Action.PRIMARY) { clicks++ }
                        content.addView(button)
                        val scroll = ScrollView(configured).apply { addView(content) }
                        val root = ArachneComponents.page(configured, header, scroll)
                        val pixelWidth = ArachneStyle.dp(root, width); val pixelHeight = ArachneStyle.dp(root, height)
                        root.measure(View.MeasureSpec.makeMeasureSpec(pixelWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pixelHeight, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, pixelWidth, pixelHeight)
                        check(header.height == ArachneStyle.dp(root, ArachneStyle.HEADER)) { "Header consumes unexpected pane height" }
                        scroll.scrollTo(0, content.height)
                        check(scroll.scrollY > 0 && header.top == 0 && header.parent === root) { "Page navigation scrolled with content" }
                        scroll.scrollTo(0, 0)
                        for (target in listOf(header.menu, row.body, row.more, field, button)) {
                            check(target.measuredHeight >= ArachneStyle.dp(target, ArachneStyle.TOUCH)) { "Touch target clipped" }
                            check(target.measuredWidth > 0 && target.measuredWidth <= pixelWidth) { "Horizontal overflow" }
                        }
                        check(row.more.right <= row.width)
                        val measurementBody = measurement.parent as LinearLayout
                        check(measurementBody.paddingTop >= ArachneStyle.dp(root, 8) && measurementBody.paddingBottom >= ArachneStyle.dp(root, 8))
                        check(measurementBody.getChildAt(0).right <= measurement.left && measurement.right <= measurementBody.width) { "Metric label and value overlap" }
                        check(measurement.layout.getEllipsisCount(measurement.lineCount - 1) == 0) { "Metric value clipped" }
                        check(row.body.isFocusable)
                        val firstBounds = android.graphics.Rect(row.body.left, row.body.top, row.body.right, row.body.bottom)
                        ArachneStyle.applyTree(root)
                        root.measure(View.MeasureSpec.makeMeasureSpec(pixelWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pixelHeight, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, pixelWidth, pixelHeight)
                        check(firstBounds == android.graphics.Rect(row.body.left, row.body.top, row.body.right, row.body.bottom)) {
                            "Theme refresh changed the first measured row layout"
                        }
                        val sameBody = row.body
                        row.update("Renamed workspace", "Sharing off", "Paused", ArachneSignal.State.NEUTRAL, "Open renamed workspace")
                        check(row.body === sameBody) { "A data update replaced the row" }
                        row.body.performClick(); row.more.performClick(); header.menu.performClick(); button.performClick()
                        ArachneStyle.applyTree(root)
                        check(row.signal.currentTextColor == ArachneStyle.secondaryColor) { "Theme pass lost the signal role" }
                        check(header.title.typeface == isolated.resources.getFont(R.font.inter_semibold))
                        val image = Bitmap.createBitmap(pixelWidth, pixelHeight, Bitmap.Config.ARGB_8888)
                        root.draw(Canvas(image))
                        val name = "${appearance.name.lowercase()}-${width}x$height-$scale.png"
                        val directory = File(context.getExternalFilesDir(null), "design-system-check").apply { mkdirs() }
                        File(directory, name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        image.recycle()
                        configurations.put(JSONObject().put("appearance", appearance.name).put("pane_dp", "$width x $height")
                            .put("font_scale", scale).put("header_px", header.height).put("row_px", row.height).put("image", name))
                        header.title.text = "Joint Operations with a long workspace name for a regional response team"
                        header.subtitle.text = header.title.text
                        header.subtitle.visibility = View.VISIBLE
                        root.measure(View.MeasureSpec.makeMeasureSpec(pixelWidth, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(pixelHeight, View.MeasureSpec.EXACTLY))
                        root.layout(0, 0, pixelWidth, pixelHeight)
                        check(header.title.lineCount <= 2 && header.subtitle.lineCount == 1 && scroll.height >= ArachneStyle.dp(root, 48)) {
                            "Long workspace name consumed the fixed-header page viewport"
                        }
                    }
                }
                check(clicks == 48) { "Not all component actions ran" }
                val detail = ArachneComponents.details(isolated, "identity", ArachneComponents.note(isolated, "Full identity"))
                val toggle = detail.getChildAt(0) as android.widget.Button
                val disclosed = detail.getChildAt(1)
                check(disclosed.visibility == View.GONE)
                toggle.performClick()
                check(disclosed.visibility == View.VISIBLE && toggle.text == "Hide identity")
                ArachneStyle.applyTree(detail)
                toggle.performClick()
                check(disclosed.visibility == View.GONE && toggle.text == "Show identity")
                val heading = ArachneComponents.section(isolated, "Settings", detail).getChildAt(0)
                if (android.os.Build.VERSION.SDK_INT >= 28) check(heading.isAccessibilityHeading)
                val formHeader = ArachneComponents.label(isolated, "Create workspace", ArachneStyle.Type.TITLE)
                val formContent = ArachneComponents.stack(isolated, 0, ArachneComponents.note(isolated, "Form fields"))
                val formScroll = ScrollView(isolated).apply { addView(formContent) }
                val formFooter = ArachneComponents.actions(isolated, ArachneComponents.action(isolated, "Cancel") {}, ArachneComponents.action(isolated, "Save") {})
                val form = ArachneComponents.page(isolated, formHeader, formScroll, formFooter)
                for (height in listOf(320, 120, 320)) {
                    val w = ArachneStyle.dp(form, 360); val h = ArachneStyle.dp(form, height)
                    repeat(2) {
                        form.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
                        form.layout(0, 0, w, h)
                    }
                    val owner = if (height < 180) formContent else form
                    check(formHeader.parent === owner && formFooter.parent === owner) { "Form navigation/actions did not adapt to keyboard space" }
                    check(formScroll.height > 0) { "Form content lost its viewport" }
                }
                ArachneStyle.chooseReducedMotion(true)
                ArachneStyle.initialize(context, isolated)
                check(ArachneStyle.reducedMotion && !ArachneMotion.enabled())
                val signal = ArachneSignal(isolated, "Active", ArachneSignal.State.ACTIVE)
                signal.update("Paused", ArachneSignal.State.NEUTRAL)
                check(signal.text.toString() == "Paused" && signal.alpha == 1f)
                check(ArachneStyle.feedback(signal) !is RippleDrawable) { "Reduced motion still has an animated ripple" }
                result.put("passed", true).put("configurations", configurations).put("actions", clicks)
                    .put("checks", "default dark; persisted gray; text contrast; real Inter; scoped dialog colors; touch targets; stable row identity; reduced motion; fixed page navigation; compact form fallback/restoration; accessible disclosure")
                    .put("ceiling", "Unattached Android layout and callback checks; keyboard focus and navigation require the ATAK host check")
            } catch (error: Throwable) { failure.set(error) }
            finally {
                context.deleteSharedPreferences(preferencesName)
                ArachneStyle.initialize(context, context)
            }
        }
        failure.get()?.let { throw it }
        return result
    }
}
