package dev.arachne.atak

import android.os.Bundle
import android.os.Looper
import com.atakmap.comms.CommsMapComponent
import org.json.JSONArray
import org.json.JSONObject

/** Read actual native connection metadata. Does not create or modify a stream. */
internal object NativeBindingCheck {
    @JvmStatic fun snapshot(): String {
        check(BuildConfig.DEBUG && Looper.myLooper() == Looper.getMainLooper())
        val streams = CommsMapComponent.getInstance().allPortsBundle.getParcelableArray("streams").orEmpty()
            .map { it as Bundle }.filter { it.getString("description").orEmpty().startsWith("Arachne") }
            .map { JSONObject().put("connection", it.getString("connectString"))
                .put("description", it.getString("description")).put("enabled", it.getBoolean("enabled")) }
        val servers = com.atakmap.comms.TAKServerListener.getInstance().servers.orEmpty()
            .map { it.connectString }
        return JSONObject().put("streams", JSONArray(streams)).put("server_destinations", JSONArray(servers))
            .put("local_bindings_hidden_from_server_picker", streams.none { it.getString("connection") in servers }).toString()
    }
}
