package dev.arachne.atak

import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

fun main() {
    val workspace = ByteArray(32) { 1 }
    val member = ByteArray(32) { 2 }
    val topic = "feeds/${member.joinToString("") { it.toUByte().toString(16).padStart(2, '0') }}/${"34".repeat(16)}"
    val now = 1_000_000L
    fun document(type: String = "a-f-G-U-C", time: Long = now, start: Long = time, stale: Long = time + 60_000,
        lat: String = "30.4", lon: String = "-84.2", uid: String = "source-observation") =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
            ("<event uid='$uid' type='$type' time='${Instant.ofEpochMilli(time)}' " +
                "start='${Instant.ofEpochMilli(start)}' stale='${Instant.ofEpochMilli(stale)}'>" +
                "<point lat='$lat' lon='$lon'/><detail><contact callsign='Unverified feed'/></detail></event>").byteInputStream())
    var passed = 0
    val failures = mutableListOf<String>()
    fun scenario(name: String, action: () -> Unit) {
        try { action(); passed++; println("PASS $name") }
        catch (error: Throwable) { failures += name; println("FAIL $name: ${error.message}") }
    }
    scenario("aprs_and_adsb_rescope_source_identity_and_preserve_coordinates") {
        for (type in listOf("a-f-G-U-C", "a-n-A-C")) {
            val doc = document(type)
            val projected = projectCotFeed(doc, workspace, member, topic, now)
            check(projected.uid == CotIdentity.receivedObject(workspace, member, "$topic/source-observation"))
            check(doc.documentElement.getAttribute("uid") == projected.uid)
            check(projected.identity == "source-observation" && projected.latitude == 30.4 && projected.longitude == -84.2)
            check(projected.observedAt == now && projected.expiresAt == now + 60_000)
        }
    }
    scenario("workspace_author_and_feed_topic_each_scope_the_uid") {
        val expected = projectCotFeed(document(), workspace, member, topic, now).uid
        check(projectCotFeed(document(), ByteArray(32) { 3 }, member, topic, now).uid != expected)
        check(projectCotFeed(document(), workspace, ByteArray(32) { 4 }, topic, now).uid != expected)
        check(projectCotFeed(document(), workspace, member, "$topic-other", now).uid != expected)
    }
    scenario("expired_future_and_overlong_reports_are_rejected") {
        for (doc in listOf(document(stale = now), document(time = now + 10_001), document(stale = now + 60_001), document(start = now - 1))) {
            check(runCatching { projectCotFeed(doc, workspace, member, topic, now) }.isFailure)
        }
    }
    scenario("nonfinite_or_out_of_range_coordinates_are_rejected") {
        for (doc in listOf(document(lat = "NaN"), document(lat = "91"), document(lon = "Infinity"), document(lon = "181"))) {
            check(runCatching { projectCotFeed(doc, workspace, member, topic, now) }.isFailure)
        }
    }
    scenario("foreign_cot_types_and_invalid_identities_are_rejected") {
        for (doc in listOf(document(type = "t-x-d-d"), document(type = "b-t-f"), document(uid = ""), document(uid = "x".repeat(129)))) {
            check(runCatching { projectCotFeed(doc, workspace, member, topic, now) }.isFailure)
        }
    }
    scenario("multiple_points_cannot_choose_an_ambiguous_location") {
        val doc = document()
        doc.documentElement.appendChild(doc.createElement("point").apply { setAttribute("lat", "1"); setAttribute("lon", "2") })
        check(runCatching { projectCotFeed(doc, workspace, member, topic, now) }.isFailure)
    }
    println("CotFeedProjectionCheck: $passed passed, ${failures.size} failed")
    check(failures.isEmpty()) { failures.joinToString() }
}
