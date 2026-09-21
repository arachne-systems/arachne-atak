package dev.arachne.atak

import android.content.Context

internal data class WorkspaceTopic(val topic: String, val publish: Boolean, val receive: Boolean)

/** Local application preferences, not membership permissions. */
internal class WorkspaceTopics(context: Context, workspace: ByteArray, private val defaults: Set<String>) {
    private val preferences = context.getSharedPreferences("fabric-topic-choices", Context.MODE_PRIVATE)
    private val prefix = workspace.joinToString("") { "%02x".format(it.toInt() and 255) }
    private var choices = defaults.sorted().map { topic ->
        WorkspaceTopic(topic, preferences.getBoolean("$prefix/publish/$topic", true),
            preferences.getBoolean("$prefix/receive/$topic", true))
    }
    init { require(workspace.size == 32 && defaults.size <= 64 && defaults.all(WorkspaceData::validTopic)) }
    fun views() = choices.toList()
    fun interests() = choices.filter { it.receive }.map { it.topic }.toSet()
    fun canPublish(topic: String) = choices.firstOrNull { it.topic == topic }?.publish ?: true
    fun select(topics: Set<String>, publish: Boolean?, receive: Boolean?) {
        require(topics.isNotEmpty() && defaults.containsAll(topics) && (publish != null || receive != null))
        val next = choices.map { if (it.topic in topics) it.copy(publish = publish ?: it.publish, receive = receive ?: it.receive) else it }
        val editor = preferences.edit()
        for (choice in next) editor.putBoolean("$prefix/publish/${choice.topic}", choice.publish)
            .putBoolean("$prefix/receive/${choice.topic}", choice.receive)
        check(editor.commit()) { "Could not save topic choices" }
        choices = next
    }
}
