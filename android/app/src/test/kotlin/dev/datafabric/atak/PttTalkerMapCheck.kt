package dev.arachne.atak

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

private val workspace = "11".repeat(32)
private val otherWorkspace = "22".repeat(32)
private val alice = "33".repeat(32)
private val bob = "44".repeat(32)
private val channel = "55".repeat(16)
private val otherChannel = "66".repeat(16)
private val transmission = "77".repeat(16)
private val otherTransmission = "88".repeat(16)
private const val epoch = 1_000_000L
private fun fix(uid: String = "native-alice", observed: Long = epoch, expires: Long = epoch + 60_000) =
    PttMapPosition(uid, 38.0, -77.0, observed, expires)
private fun state() = PttTalkerState().apply {
    bind(workspace, mapOf(alice to "Alice", bob to "Bob"))
    position(workspace, alice, fix(), epoch)
    position(workspace, bob, fix("native-bob"), epoch)
}
private fun packet(kind: Int = 2, tx: String = transmission, net: String = channel): ByteArray =
    ByteArrayOutputStream().apply {
        DataOutputStream(this).use { out ->
            fun id(value: String) = value.chunked(2).forEach { out.writeByte(it.toInt(16)) }
            out.writeBytes("APTM"); out.writeByte(1); id(net)
            out.writeBytes("APTT"); out.writeByte(1); out.writeByte(kind); id(tx)
            when (kind) {
                1 -> { out.writeInt(48_000); out.writeByte(1); out.writeInt(24_000); out.writeShort(960); out.writeByte(5); out.writeShort(300) }
                2 -> { out.writeShort(0); out.writeLong(0); out.writeByte(1); out.writeShort(2); out.write(byteArrayOf(1, 2)) }
                3 -> { out.writeShort(1); out.write(ByteArray(32)) }
            }
        }
    }.toByteArray()

fun main() {
    var passed = 0
    val failures = mutableListOf<String>()
    fun scenario(name: String, run: () -> Unit) {
        try { run(); passed++; println("PASS $name") }
        catch (error: Throwable) { failures += name; println("FAIL $name: ${error.message}") }
    }
    scenario("authenticated_batch_uses_existing_scoped_position") {
        val state = state()
        state.media(workspace, alice, 1, packet(), 100)
        val talker = state.visible(100, epoch).single()
        check(talker.member == alice && talker.name == "Alice" && talker.position == fix())
        check(talker.channel == channel && talker.transmission == transmission)
    }
    scenario("simultaneous_channels_and_members_remain_separate") {
        val state = state()
        state.media(workspace, alice, 1, packet(), 100)
        state.media(workspace, bob, 1, packet(net = otherChannel), 100)
        check(state.visible(100, epoch).map { it.member }.toSet() == setOf(alice, bob))
    }
    scenario("start_and_floor_bytes_are_not_speech") {
        val state = state()
        state.media(workspace, alice, 1, packet(1), 100)
        state.media(workspace, alice, 2, "APTF floor grant".toByteArray(), 100)
        check(state.visible(100, epoch).isEmpty())
    }
    scenario("end_only_clears_its_author_and_transmission") {
        val state = state()
        state.media(workspace, alice, 1, packet(), 100)
        state.media(workspace, bob, 1, packet(3), 110)
        state.media(workspace, alice, 2, packet(3, otherTransmission), 120)
        check(state.visible(120, epoch).single().member == alice)
        state.media(workspace, alice, 3, packet(3), 130)
        check(state.visible(130, epoch).isEmpty())
        state.media(workspace, alice, 1, packet(), 140)
        check(state.visible(140, epoch).isEmpty())
    }
    scenario("missing_end_expires_after_1500_ms") {
        val state = state()
        state.media(workspace, alice, 1, packet(), 100)
        check(state.visible(1599, epoch).size == 1)
        check(state.visible(1600, epoch).isEmpty())
    }
    scenario("no_position_and_stale_position_create_no_marker") {
        val state = PttTalkerState()
        state.bind(workspace, mapOf(alice to "Alice"))
        state.media(workspace, alice, 1, packet(), 100)
        check(state.visible(100, epoch).isEmpty())
        state.position(workspace, alice, fix(expires = epoch + 1), epoch)
        check(state.visible(100, epoch).size == 1)
        check(state.visible(100, epoch + 1).isEmpty())
    }
    scenario("wrong_workspace_and_nonmember_cannot_choose_a_position") {
        val state = state()
        state.media(otherWorkspace, alice, 1, packet(), 100)
        state.media(workspace, "aa".repeat(32), 1, packet(), 100)
        check(state.visible(100, epoch).isEmpty())
        state.position(otherWorkspace, alice, fix("wrong"), epoch)
        state.media(workspace, alice, 1, packet(), 100)
        check(state.visible(100, epoch).single().position.uid == "native-alice")
    }
    scenario("workspace_stop_and_member_removal_clear_activity") {
        val state = state()
        state.media(workspace, alice, 1, packet(), 100)
        state.bind(workspace, mapOf(bob to "Bob"))
        check(state.visible(100, epoch).isEmpty())
        state.bind(otherWorkspace, mapOf(alice to "Alice"))
        state.media(otherWorkspace, alice, 1, packet(), 100)
        check(state.visible(100, epoch).isEmpty())
        state.position(otherWorkspace, alice, fix(), epoch)
        check(state.visible(100, epoch).size == 1)
        state.bind(null, emptyMap())
        check(state.visible(100, epoch).isEmpty())
    }
    scenario("invalid_and_old_positions_are_not_used") {
        val state = state()
        state.position(workspace, alice, fix("invalid").copy(latitude = Double.NaN), epoch)
        state.position(workspace, alice, fix("old", observed = epoch - 1), epoch)
        state.media(workspace, alice, 1, packet(), 100)
        check(state.visible(100, epoch).single().position.uid == "native-alice")
        check(state.visible(100, epoch + 60_001).isEmpty())
    }
    scenario("malformed_media_and_duplicate_sequences_do_not_refresh") {
        val state = state()
        for (bytes in listOf(packet().dropLast(1).toByteArray(), packet() + 0, ByteArray(5000), packet().also { it[25] = 2 })) {
            state.media(workspace, alice, 1, bytes, 100)
        }
        check(state.visible(100, epoch).isEmpty())
        state.media(workspace, alice, 1, packet(), 100)
        state.media(workspace, alice, 1, packet(), 1400)
        check(state.visible(1600, epoch).isEmpty())
    }
    println("PttTalkerMapCheck: $passed passed, ${failures.size} failed")
    check(failures.isEmpty()) { failures.joinToString() }
}
