package dev.arachne.atak

import org.w3c.dom.Document
import org.w3c.dom.Element
import java.time.Instant

internal data class CotFeedProjection(val uid: String, val identity: String, val observedAt: Long,
    val expiresAt: Long, val latitude: Double, val longitude: Double)

/** The caller parses bounded CoT and supplies the authenticated workspace/member and selected topic. */
internal fun projectCotFeed(document: Document, workspace: ByteArray, member: ByteArray,
    topic: String, now: Long): CotFeedProjection {
    val root = document.documentElement
    require(root.getAttribute("type") in setOf("a-f-G-U-C", "a-n-A-C"))
    val identity = root.getAttribute("uid").also {
        require(it.isNotBlank() && it.length <= 128 && it.none { char -> char.isISOControl() })
    }
    val observedAt = Instant.parse(root.getAttribute("time")).toEpochMilli()
    require(Instant.parse(root.getAttribute("start")).toEpochMilli() == observedAt)
    val expiresAt = Instant.parse(root.getAttribute("stale")).toEpochMilli()
    require(observedAt in Math.subtractExact(now, 60_000)..Math.addExact(now, 10_000))
    require(expiresAt > now && expiresAt <= Math.addExact(observedAt, 60_000))
    val points = root.getElementsByTagName("point")
    require(points.length == 1)
    val point = points.item(0) as Element
    val latitude = point.getAttribute("lat").toDouble().also { require(it.isFinite() && it in -90.0..90.0) }
    val longitude = point.getAttribute("lon").toDouble().also { require(it.isFinite() && it in -180.0..180.0) }
    val uid = CotIdentity.receivedObject(workspace, member, "$topic/$identity")
    root.setAttribute("uid", uid)
    return CotFeedProjection(uid, identity, observedAt, expiresAt, latitude, longitude)
}
