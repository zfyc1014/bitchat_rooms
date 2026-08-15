package com.bitchat.android.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the pure helpers backing in-channel P2P private mode. These cover the
 * member-dedup and media-routing decisions without requiring a full view-model harness.
 */
class ChannelPrivateModeTest {

    @Test
    fun `participants union channel members and connected peers without duplicates`() {
        val participants = channelPrivateModeParticipants(
            channelMembers = setOf("alice", "bob", "self"),
            connectedPeers = listOf("bob", "carol", "self"),
            myPeerID = "self"
        )

        assertEquals(listOf("alice", "bob", "carol"), participants)
    }

    @Test
    fun `participants exclude self even when only connected`() {
        val participants = channelPrivateModeParticipants(
            channelMembers = null,
            connectedPeers = listOf("self", "alice"),
            myPeerID = "self"
        )

        assertEquals(listOf("alice"), participants)
    }

    @Test
    fun `participants return empty when no candidates`() {
        val participants = channelPrivateModeParticipants(
            channelMembers = emptySet(),
            connectedPeers = emptyList(),
            myPeerID = "self"
        )

        assertEquals(emptyList<String>(), participants)
    }

    @Test
    fun `media target routes to member when private mode active in a channel`() {
        val (peer, channel) = resolveChannelPrivateMediaTarget(
            toPeerIDOrNull = null,
            channelOrNull = "#general",
            privateModePeer = "alice"
        )

        assertEquals("alice", peer)
        assertEquals(null, channel)
    }

    @Test
    fun `media target keeps channel broadcast when private mode inactive`() {
        val (peer, channel) = resolveChannelPrivateMediaTarget(
            toPeerIDOrNull = null,
            channelOrNull = "#general",
            privateModePeer = null
        )

        assertEquals(null, peer)
        assertEquals("#general", channel)
    }

    @Test
    fun `media target ignores private mode outside a channel`() {
        val (peer, channel) = resolveChannelPrivateMediaTarget(
            toPeerIDOrNull = "alice",
            channelOrNull = null,
            privateModePeer = "bob"
        )

        assertEquals("alice", peer)
        assertEquals(null, channel)
    }
}
