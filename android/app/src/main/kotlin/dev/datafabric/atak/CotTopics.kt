package dev.arachne.atak

/** Initial ATAK category mapping, not a limit on the fabric topic namespace. */
internal object CotTopics {
    val standard = setOf("atak/pli", "atak/chat", "atak/features", "atak/drawings")
    val nativeDefaults = setOf("atak/native/v1/pli", "atak/native/v1/chat", "atak/native/v1/features", "atak/native/v1/drawings", "atak/native/v1/events")
    val nativeCurrent = nativeDefaults - "atak/native/v1/chat"
    val nativeLabels = linkedMapOf("atak/native/v1/pli" to "Location", "atak/native/v1/chat" to "Chat",
        "atak/native/v1/features" to "Points and tracks", "atak/native/v1/drawings" to "Drawings",
        "atak/native/v1/events" to "Other ATAK data")

    fun native(type: String, isSelf: Boolean): String? = when {
        type in setOf("t-x-c-t", "t-x-c-t-r", "t-x-takp-v", "t-x-takp-q", "t-x-takp-r") -> null
        type.startsWith("b-t-f") -> "atak/native/v1/chat"
        isSelf && type.startsWith("a-") -> "atak/native/v1/pli"
        type.startsWith("a-") -> "atak/native/v1/features"
        type.startsWith("u-d-") -> "atak/native/v1/drawings"
        else -> "atak/native/v1/events"
    }

    fun outgoing(type: String, isSelf: Boolean, chatRoom: String?): String? = when {
        type == "b-t-f" -> if (chatRoom == "All Chat Rooms") "atak/chat" else null
        type.startsWith("a-") -> if (isSelf) "atak/pli" else "atak/features"
        type.startsWith("u-d-") -> "atak/drawings"
        else -> null // Add explicit mappings as additional native features are verified.
    }

    fun accepts(topic: String, type: String, chatRoom: String?): Boolean = when (topic) {
        "atak/pli", "atak/features" -> type.startsWith("a-")
        "atak/chat" -> type == "b-t-f" && chatRoom == "All Chat Rooms"
        "atak/drawings" -> type.startsWith("u-d-")
        else -> false
    }
}
