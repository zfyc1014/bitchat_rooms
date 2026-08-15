package com.bitchat.android.ui

/**
 * Pure helpers backing in-channel P2P private mode. Kept free of view-model state so the
 * routing decisions are unit-testable without a full [ChatViewModel] harness.
 */

/**
 * Candidate members for in-channel private mode: channel members unioned with the currently
 * connected mesh peers, minus ourselves. Order is stable (channel members first) and
 * duplicates are removed.
 */
fun channelPrivateModeParticipants(
    channelMembers: Set<String>?,
    connectedPeers: List<String>,
    myPeerID: String
): List<String> {
    val result = ArrayList<String>()
    channelMembers.orEmpty().forEach { peerID ->
        if (peerID != myPeerID && peerID !in result) result.add(peerID)
    }
    connectedPeers.forEach { peerID ->
        if (peerID != myPeerID && peerID !in result) result.add(peerID)
    }
    return result
}

/**
 * Resolves the media destination while in a channel. When private mode is active inside a
 * channel, media goes to the selected member instead of the channel broadcast.
 */
fun resolveChannelPrivateMediaTarget(
    toPeerIDOrNull: String?,
    channelOrNull: String?,
    privateModePeer: String?
): Pair<String?, String?> {
    return if (privateModePeer != null && channelOrNull != null) {
        privateModePeer to null
    } else {
        toPeerIDOrNull to channelOrNull
    }
}
