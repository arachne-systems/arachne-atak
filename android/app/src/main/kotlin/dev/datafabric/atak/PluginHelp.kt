package dev.arachne.atak

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.content.Context
import android.text.SpannableString
import android.text.Spanned
import android.text.style.LeadingMarginSpan
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray

/** Bundled offline documentation and the external project/support page. */
internal class PluginHelp(private val plugin: Context, private val host: Context, private val pane: android.view.View) {
    private var dialog: AlertDialog? = null

    fun close() { dialog?.dismiss(); dialog = null }

    fun changelog() = text("Changelog", "changelog.txt")

    fun manualPage(): android.view.View = runCatching {
        val body = plugin.assets.open("help/manual.txt").bufferedReader().use { it.readText() }
        val contents = JSONArray(plugin.assets.open("help/manual-contents.json").bufferedReader().use { it.readText() })
        val entries = (0 until contents.length()).map { contents.getJSONObject(it) }
        val chapters = entries.filter { it.getInt("level") == 1 }
        val headings = entries.filter { it.getInt("level") == 2 }.map { it.getString("title") }.toSet()
        ArachneComponents.stack(plugin, ArachneStyle.GAP).apply {
            addView(ArachneComponents.intro(plugin, "The bundled field manual is available offline. Open a chapter to read it."))
            chapters.forEachIndexed { index, chapter ->
                val title = chapter.getString("title")
                val start = chapter.getInt("text_offset").coerceIn(0, body.length)
                val end = chapters.getOrNull(index + 1)?.getInt("text_offset")?.coerceIn(start, body.length) ?: body.length
                val text = ArachneComponents.stack(plugin, ArachneStyle.GROUP).apply {
                    visibility = android.view.View.GONE
                    setPadding(ArachneStyle.dp(this, 8), ArachneStyle.dp(this, 8), ArachneStyle.dp(this, 8), ArachneStyle.dp(this, 16))
                }
                lateinit var toggle: android.widget.Button
                toggle = ArachneComponents.action(plugin, title, ArachneStyle.Action.QUIET) {
                    val open = text.visibility != android.view.View.VISIBLE
                    if (open && text.childCount == 0) {
                        body.substring(start, end).trim().removePrefix(title).trim().split("\n\n").forEach { paragraph ->
                            val heading = paragraph in headings
                            val illustration = paragraph.startsWith("Illustration: ")
                            val type = when {
                                heading -> ArachneStyle.Type.SECTION
                                illustration -> ArachneStyle.Type.SUPPORTING
                                else -> ArachneStyle.Type.BODY
                            }
                            val tone = if (illustration) ArachneStyle.Tone.SECONDARY else ArachneStyle.Tone.TEXT
                            text.addView(ArachneComponents.label(plugin, paragraph, type, tone).apply {
                                setTextIsSelectable(true)
                                if (heading) setPadding(0, ArachneStyle.dp(this, ArachneStyle.GROUP), 0, 0)
                                // Native hanging indents keep wrapped steps and bullets aligned with their text.
                                if (paragraph.startsWith("• ") || Regex("^\\d+\\. ").containsMatchIn(paragraph)) {
                                    val indent = ArachneStyle.dp(this, 24)
                                    this.text = SpannableString(paragraph).apply {
                                        setSpan(LeadingMarginSpan.Standard(0, indent),
                                            0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                                    }
                                }
                            })
                        }
                    }
                    text.visibility = if (open) android.view.View.VISIBLE else android.view.View.GONE
                    toggle.contentDescription = "${if (open) "Collapse" else "Expand"} $title"
                    ArachneComponents.actionIcon(toggle, if (open) ArachneComponents.Icon.CLOSE else ArachneComponents.Icon.PLUS)
                }.apply {
                    gravity = android.view.Gravity.START or android.view.Gravity.CENTER_VERTICAL
                    contentDescription = "Expand $title"
                    ArachneComponents.actionIcon(this, ArachneComponents.Icon.PLUS)
                }
                addView(ArachneComponents.stack(plugin, 0, toggle, text, ArachneComponents.rule(plugin)))
            }
        }
    }.getOrElse {
        ArachneComponents.empty(plugin, "Manual unavailable", "The bundled manual could not be opened. Reinstall the matching Arachne package or report the problem.")
    }

    fun project() {
        val url = "https://github.com/joshuafuller/arachne"
        try {
            host.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            close()
            val address = TextView(host).apply {
                text = "No browser is available. Open this address on another device:\n\n$url"
                ArachneStyle.text(this)
                val inset = ArachneStyle.dp(this, ArachneStyle.INSET)
                setPadding(inset, inset, inset, inset)
                setTextIsSelectable(true)
            }
            dialog = ArachneStyle.dialog(host, pane).setTitle("Project & support")
                .setView(address).setPositiveButton("Close", null).show()
        }
    }

    private fun text(title: String, asset: String) {
        close()
        val body = runCatching { plugin.assets.open("help/$asset").bufferedReader().use { it.readText() } }
            .getOrElse { "This document could not be opened. Reinstall the matching Arachne package or report the problem." }
        val view = TextView(host).apply {
            text = body
            ArachneStyle.text(this)
            val inset = ArachneStyle.dp(this, ArachneStyle.INSET)
            setPadding(inset, inset, inset, inset)
            setTextIsSelectable(true)
        }
        val scroll = ScrollView(host).apply { addView(view) }
        dialog = ArachneStyle.dialog(host, pane).setTitle(title)
            .setView(scroll).setPositiveButton("Close", null).show()
    }
}
