package dev.arachne.atak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Toast
import com.atakmap.android.maps.MapView
import dev.arachne.atak.ArachneComponents as Ui

internal object LocalTakPortSettings {
    fun show(context: Context, pane: android.view.View) {
        val host = MapView.getMapView().context
        val saved = LocalTakPorts.choices(host)
        fun field(label: String, value: Int) = EditText(context).apply {
            contentDescription = label; hint = "Automatic"; isSingleLine = true
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(5))
            if (value != 0) setText(value.toString())
            ArachneStyle.input(this)
        }
        val stream = field("Arachne stream port", saved.stream)
        val https = field("Arachne package HTTPS port", saved.https)
        val message = Ui.note(context, "")
        val content = Ui.stack(context, 16,
            Ui.note(context, "This device only. Automatic chooses free ports and leaves ATAK mesh and TAK Server settings unchanged."),
            Ui.section(context, "Stream port", stream),
            Ui.section(context, "Package HTTPS port", https),
            Ui.note(context, "Leave blank for Automatic. Custom ports: 1024–65535, checked before saving and at startup. Changes apply after restarting ATAK."),
            Ui.action(context, "Use automatic ports", ArachneStyle.Action.SECONDARY) { stream.setText(""); https.setText("") },
            Ui.section(context, "Current listeners", Ui.note(context, LocalTakPorts.status().joinToString("\n").ifEmpty { "No local listeners running." })),
            Ui.note(context, "Authenticated local connections only; not exposed to Wi-Fi or the internet."), message)
        val inset = ArachneStyle.dp(content, ArachneStyle.GROUP)
        content.setPadding(inset, inset, inset, inset)
        val dialog = ArachneStyle.dialog(host, pane).setTitle("ATAK connection ports")
            .setView(ScrollView(context).apply { addView(content) })
            .setNegativeButton("Cancel", null).setPositiveButton("Check & save", null).show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            fun value(field: EditText): Int = field.text.toString().trim().let { if (it.isEmpty()) 0 else it.toIntOrNull() ?: -1 }
            val next = runCatching { LocalTakPorts.Choices(value(stream), value(https)) }
            if (next.isFailure) { message.text = next.exceptionOrNull()?.message; return@setOnClickListener }
            val save = dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            save.isEnabled = false
            dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = false
            dialog.setCancelable(false)
            message.text = "Checking listener ports…"
            Thread({
                val result = runCatching { LocalTakPorts.save(host, next.getOrThrow()) }
                Handler(Looper.getMainLooper()).post {
                    save.isEnabled = true
                    dialog.getButton(android.app.AlertDialog.BUTTON_NEGATIVE).isEnabled = true
                    dialog.setCancelable(true)
                    result.onSuccess {
                        Toast.makeText(host, "Port settings saved. They apply on the next ATAK start.", Toast.LENGTH_LONG).show()
                        dialog.dismiss()
                    }.onFailure { message.text = it.message ?: "Port settings could not be saved." }
                }
            }, "arachne-port-preflight").start()
        }
    }
}
