package com.bitchat.android.net

import android.content.Context
import android.util.Log
import com.bitchat.android.crypto.EncryptionService
import com.bitchat.android.mesh.FragmentingPacketSender
import com.bitchat.android.mesh.MeshCore
import com.bitchat.android.mesh.MeshDelegate
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.mesh.MeshTransport
import com.bitchat.android.mesh.PeerInfo
import com.bitchat.android.mesh.PrivateMediaPreparation
import com.bitchat.android.model.BitchatFilePacket
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.RoutedPacket
import com.bitchat.android.protocol.BitchatPacket
import com.bitchat.android.protocol.SpecialRecipients
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.service.TransportBridgeService
import com.bitchat.android.sync.GossipSyncManager
import com.bitchat.android.util.toHexString
import com.bitchat.android.wifiaware.SyncedSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

/**
 * LAN TCP/IP transport for bitchat.
 *
 * This is a third local mesh transport alongside BLE and Wi-Fi Aware. It follows the exact same
 * pluggable-transport pattern as [com.bitchat.android.wifiaware.WifiAwareMeshService]: it owns a
 * [MeshCore] coordinator, exposes a [MeshTransport] inner class, and implements both [MeshService]
 * (for the UI/router) and [TransportBridgeService.TransportLayer] (so packets can be relayed
 * between transports).
 *
 * Discovery is done with a lightweight UDP broadcast on the local network segment. Every peer
 * announces its peer ID + TCP port; the peer with the lexicographically smaller peer ID opens the
 * TCP connection to the larger peer so only one socket is established per pair. Frames use the
 * shared [SyncedSocket] `[4-byte length][payload]` framing so all existing protocol/Noise code
 * works unchanged.
 */
class TcpMeshService(private val context: Context) : MeshService, TransportBridgeService.TransportLayer {

    companion object {
        private const val TAG = "TcpMeshService"

        /** Transport bridge identifier, matching the "BLE"/"WIFI" convention. */
        const val TRANSPORT_ID = "TCP"

        private const val MAX_TTL: UByte = 7u

        /** TCP listen port for peer connections. */
        const val TCP_PORT = 42101

        /** UDP discovery/announcement port. */
        const val DISCOVERY_PORT = 42100

        /** How often we (re)announce ourselves on the LAN. */
        private const val ANNOUNCE_INTERVAL_MS = 3_000L

        /** Magic prefix so we only accept our own discovery datagrams. */
        private const val DISCOVERY_PREFIX = "BITCHAT_TCP"
    }

    private val encryptionService = EncryptionService(context)

    override val myPeerID: String = encryptionService.getIdentityFingerprint().take(16)

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val tcpTransport = TcpTransport()
    private lateinit var meshCore: MeshCore
    private lateinit var fragmentingSender: FragmentingPacketSender

    // --- connection bookkeeping ---
    private val peerSockets = ConcurrentHashMap<String, SyncedSocket>() // peerID -> socket
    private val socketToPeer = ConcurrentHashMap<SyncedSocket, String>() // socket -> peerID
    private val discoveredAddresses = ConcurrentHashMap<String, String>() // peerID -> "host"

    private var serverSocket: ServerSocket? = null
    private var discoverySocket: DatagramSocket? = null

    @Volatile
    private var isActive = false

    // Service-level notification manager for background (no-UI) DMs
    private val serviceNotificationManager = com.bitchat.android.ui.NotificationManager(
        context.applicationContext,
        androidx.core.app.NotificationManagerCompat.from(context.applicationContext)
    )

    fun isRunning(): Boolean = isActive

    override var delegate: MeshDelegate? = null
        set(value) {
            field = value
            if (::meshCore.isInitialized) {
                meshCore.delegate = value
                meshCore.refreshPeerList()
            }
        }

    init {
        // Ensure BluetoothMeshService is initialized so we share its GossipSyncManager, matching
        // the Wi-Fi Aware transport. This keeps a single gossip source/delegate across transports.
        MeshServiceHolder.getOrCreate(context)
        val shared = MeshServiceHolder.sharedGossipSyncManager
        encryptionService.onSessionEstablished = { peerID ->
            Log.d(TAG, "TCP Noise session established with ${peerID.take(8)}")
            try {
                com.bitchat.android.services.MessageRouter
                    .tryGetInstance()
                    ?.onSessionEstablished(peerID)
            } catch (_: Exception) { }
        }
        meshCore = MeshCore(
            context = context.applicationContext,
            scope = serviceScope,
            transport = tcpTransport,
            encryptionService = encryptionService,
            myPeerID = myPeerID,
            maxTtl = MAX_TTL,
            sharedGossipManager = shared,
            gossipConfigProvider = object : GossipSyncManager.ConfigProvider {
                override fun seenCapacity(): Int = 500
                override fun gcsMaxBytes(): Int = 400
                override fun gcsTargetFpr(): Double = 0.01
            },
            hooks = MeshCore.Hooks(
                onMessageReceived = { message -> handleMessageReceived(message) },
                onAnnounceProcessed = { routed, _ ->
                    routed.peerID?.let { pid ->
                        try {
                            meshCore.gossipSyncManager.scheduleInitialSyncToPeer(pid, 1_000)
                        } catch (_: Exception) { }
                    }
                },
                announcementNicknameProvider = {
                    try {
                        com.bitchat.android.services.NicknameProvider.getNickname(context, myPeerID)
                    } catch (_: Exception) { null }
                },
                leavePayloadProvider = {
                    (delegate?.getNickname() ?: myPeerID).toByteArray(Charsets.UTF_8)
                }
            )
        )
        fragmentingSender = FragmentingPacketSender(serviceScope, meshCore.fragmentManager, TAG)
    }

    private fun handleMessageReceived(message: BitchatMessage): Boolean {
        // Match BLE admission semantics so rejected/duplicate private messages never trigger a
        // notification after conversation state was cleared.
        if (!com.bitchat.android.services.IncomingMessageAdmission.admitToAppState(message)) {
            return false
        }
        if (delegate == null && message.isPrivate) {
            try {
                val senderPeerID = message.senderPeerID
                if (senderPeerID != null) {
                    val nick = try {
                        meshCore.getPeerNickname(senderPeerID)
                    } catch (_: Exception) { null } ?: senderPeerID
                    val preview = com.bitchat.android.ui.NotificationTextUtils
                        .buildPrivateMessagePreview(message)
                    serviceNotificationManager.setAppBackgroundState(true)
                    serviceNotificationManager.showPrivateMessageNotification(senderPeerID, nick, preview)
                }
            } catch (_: Exception) { }
        }
        return true
    }

    // ---------------------------------------------------------------------------------
    // TransportLayer implementation (bridge relay)
    // ---------------------------------------------------------------------------------

    override fun send(packet: RoutedPacket) {
        // Received from another transport (e.g. BLE) -> send out over TCP.
        meshCore.sendFromBridge(packet)
    }

    override suspend fun sendAndReport(packet: RoutedPacket): Boolean {
        return meshCore.sendFromBridgeAndReport(packet)
    }

    override fun sendToPeer(peerID: String, packet: BitchatPacket) {
        sendPacketToPeer(peerID, packet)
    }

    // ---------------------------------------------------------------------------------
    // Send helpers
    // ---------------------------------------------------------------------------------

    private fun broadcastRaw(bytes: ByteArray): Boolean {
        var accepted = false
        peerSockets.forEach { (pid, sock) ->
            try {
                sock.write(bytes)
                accepted = true
            } catch (e: IOException) {
                Log.e(TAG, "TX: write failed to ${pid.take(8)}: ${e.message}")
            }
        }
        return accepted
    }

    private fun broadcastPacket(routed: RoutedPacket): Boolean {
        val packet = routed.packet
        if (packet.senderID.toHexString() == myPeerID && !packet.route.isNullOrEmpty()) {
            val firstHop = packet.route!![0].toHexString()
            if (sendRoutedPacketToPeer(firstHop, routed)) {
                return true
            }
        }

        val recipientId = packet.recipientID?.toHexString()
        if (recipientId != null && !packet.recipientID.contentEquals(SpecialRecipients.BROADCAST)) {
            if (sendRoutedPacketToPeer(recipientId, routed)) {
                return true
            }
        }

        return fragmentingSender.send(routed, "TCP broadcast") { single ->
            broadcastSinglePacket(single)
        }
    }

    private fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        return sendRoutedPacketToPeer(peerID, RoutedPacket(packet))
    }

    private fun sendRoutedPacketToPeer(peerID: String, routed: RoutedPacket): Boolean {
        if (getSocketForPeer(peerID) == null) return false
        return fragmentingSender.send(routed, "TCP peer ${peerID.take(8)}") { single ->
            sendSinglePacketToPeer(peerID, single.packet)
        }
    }

    private fun broadcastSinglePacket(routed: RoutedPacket): Boolean {
        val data = routed.packet.toBinaryData() ?: return false
        return broadcastRaw(data)
    }

    private fun sendSinglePacketToPeer(peerID: String, packet: BitchatPacket): Boolean {
        val data = packet.toBinaryData() ?: return false
        val sock = getSocketForPeer(peerID)
        if (sock == null) {
            Log.d(TAG, "TX: no socket for ${peerID.take(8)}")
            return false
        }
        return try {
            sock.write(data)
            true
        } catch (e: IOException) {
            Log.e(TAG, "TX: write to ${peerID.take(8)} failed: ${e.message}")
            false
        }
    }

    private fun getSocketForPeer(peerID: String): SyncedSocket? = peerSockets[peerID]

    // ---------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------

    override fun startServices() {
        if (isActive) return
        isActive = true
        Log.i(TAG, "Starting LAN TCP mesh with peer ID: $myPeerID")

        TransportBridgeService.register(TRANSPORT_ID, this)
        meshCore.startCore()
        try { MeshServiceHolder.startSharedGossip(TRANSPORT_ID) } catch (_: Exception) { }

        startTcpServer()
        startDiscovery()
    }

    override fun stopServices() {
        if (!isActive) return
        isActive = false
        Log.i(TAG, "Stopping LAN TCP mesh")

        TransportBridgeService.unregister(TRANSPORT_ID)
        try { MeshServiceHolder.stopSharedGossip(TRANSPORT_ID) } catch (_: Exception) { }
        try { com.bitchat.android.services.AppStateStore.clearTransportPeers(TRANSPORT_ID) } catch (_: Exception) { }
        try { com.bitchat.android.services.AppStateStore.clearTransportDirectPeers(TRANSPORT_ID) } catch (_: Exception) { }

        try { serverSocket?.close() } catch (_: Exception) { }
        try { discoverySocket?.close() } catch (_: Exception) { }
        serverSocket = null
        discoverySocket = null

        // Close every peer socket so read loops unwind and mark peers disconnected.
        peerSockets.values.forEach { runCatching { it.close() } }
        peerSockets.clear()
        socketToPeer.clear()

        try { meshCore.stopCore() } catch (_: Exception) { }
        try { meshCore.shutdown() } catch (_: Exception) { }
        TcpController.onServiceStopped(this)
        serviceScope.cancel()
    }

    // ---------------------------------------------------------------------------------
    // TCP server + discovery
    // ---------------------------------------------------------------------------------

    private fun startTcpServer() {
        serviceScope.launch {
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(TCP_PORT))
                }
            } catch (e: Exception) {
                Log.e(TAG, "TCP server bind failed on port $TCP_PORT: ${e.message}")
                return@launch
            }
            serverSocket = socket
            if (!isActive) {
                runCatching { socket.close() }
                return@launch
            }
            Log.i(TAG, "TCP server listening on port $TCP_PORT")

            while (isActive) {
                val client = try {
                    socket.accept()
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "TCP accept interrupted: ${e.message}")
                    break
                }
                client.tcpNoDelay = true
                val remoteHost = (client.remoteSocketAddress as? InetSocketAddress)
                    ?.address?.hostAddress ?: "unknown"
                // We may already know this peer from discovery; otherwise use a provisional ID
                // and let the first ANNOUNCE/NOISE frame rebind the socket.
                val provisional = peerIdForAddress(remoteHost) ?: "incoming:$remoteHost"
                Log.i(TAG, "TCP accepted connection from $remoteHost (${provisional.take(8)})")
                val synced = try {
                    SyncedSocket(client)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to wrap accepted socket: ${e.message}")
                    runCatching { client.close() }
                    continue
                }
                launch { listenToPeer(synced, provisional) }
            }
        }
    }

    private fun startDiscovery() {
        serviceScope.launch {
            val socket = try {
                DatagramSocket(DISCOVERY_PORT).apply {
                    reuseAddress = true
                    broadcast = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "UDP discovery bind failed on port $DISCOVERY_PORT: ${e.message}")
                return@launch
            }
            discoverySocket = socket
            if (!isActive) {
                runCatching { socket.close() }
                return@launch
            }
            Log.i(TAG, "UDP discovery listening on port $DISCOVERY_PORT")

            // Receiver
            launch { runDiscoveryReceiver(socket) }
            // Announcer
            launch { runDiscoveryAnnouncer(socket) }
        }
    }

    private suspend fun runDiscoveryReceiver(socket: DatagramSocket) {
        val buffer = ByteArray(1024)
        while (isActive) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                socket.receive(packet)
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "UDP receive interrupted: ${e.message}")
                break
            }
            val payload = try {
                String(packet.data, packet.offset, packet.length, StandardCharsets.UTF_8)
            } catch (_: Exception) { continue }

            if (!payload.startsWith(DISCOVERY_PREFIX)) continue
            val parts = payload.split('\t')
            if (parts.size < 3) continue
            val peerId = parts[1].trim()
            val port = parts[2].trim().toIntOrNull() ?: continue
            val host = packet.address?.hostAddress ?: continue
            if (peerId.isBlank() || peerId == myPeerID) continue

            discoveredAddresses[peerId] = host
            Log.d(TAG, "Discovered TCP peer ${peerId.take(8)} at $host:$port")

            // Only the smaller peer ID dials out, so each pair ends up with a single socket.
            if (myPeerID < peerId && !peerSockets.containsKey(peerId)) {
                connectToPeer(peerId, host, port)
            }
        }
    }

    private suspend fun runDiscoveryAnnouncer(socket: DatagramSocket) {
        while (isActive) {
            val payload = "$DISCOVERY_PREFIX\t$myPeerID\t$TCP_PORT".toByteArray(StandardCharsets.UTF_8)
            val targets = broadcastAddresses()
            for (target in targets) {
                try {
                    socket.send(DatagramPacket(payload, payload.size, target, DISCOVERY_PORT))
                } catch (_: Exception) {
                    // Ignore per-target send failures; some interfaces won't accept broadcast.
                }
            }
            delay(ANNOUNCE_INTERVAL_MS)
        }
    }

    /** Best-effort list of IPv4 broadcast addresses for the current interfaces. */
    private fun broadcastAddresses(): List<InetAddress> {
        val result = mutableListOf<InetAddress>()
        try {
            result.add(InetAddress.getByName("255.255.255.255"))
            val interfaces = NetworkInterface.getNetworkInterfaces()
            if (interfaces != null) {
                for (ni in interfaces) {
                    if (!ni.isUp || ni.isLoopback) continue
                    val addresses = ni.interfaceAddresses
                    for (addr in addresses) {
                        val broadcast = addr.broadcast ?: continue
                        result.add(broadcast)
                    }
                }
            }
        } catch (_: Exception) { }
        return result
    }

    private fun peerIdForAddress(host: String): String? {
        return discoveredAddresses.entries.firstOrNull { it.value == host }?.key
    }

    private fun connectToPeer(peerId: String, host: String, port: Int) {
        serviceScope.launch {
            val raw = try {
                Socket().apply { tcpNoDelay = true }.also {
                    it.connect(InetSocketAddress(host, port), 5_000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "TCP connect to ${peerId.take(8)}@$host:$port failed: ${e.message}")
                return@launch
            }
            Log.i(TAG, "TCP connected to ${peerId.take(8)}@$host:$port")
            val synced = try { SyncedSocket(raw) } catch (e: Exception) {
                runCatching { raw.close() }
                return@launch
            }
            peerSockets[peerId] = synced
            socketToPeer[synced] = peerId
            meshCore.addOrUpdatePeer(peerId, peerId)

            // Deterministic Noise initiator (smaller peer ID), mirroring Wi-Fi Aware.
            meshCore.initiateNoiseHandshake(peerId)

            serviceScope.launch { listenToPeer(synced, peerId) }

            serviceScope.launch {
                delay(150)
                sendBroadcastAnnounce()
            }
        }
    }

    private fun listenToPeer(synced: SyncedSocket, initialPeerId: String) {
        var logicalPeerId = initialPeerId

        while (isActive) {
            val frame = try {
                synced.read()
            } catch (e: Exception) {
                if (isActive) Log.w(TAG, "TCP read error: ${e.message}")
                break
            } ?: break

            if (frame.isEmpty()) {
                // Keep-alive (0-length frame)
                continue
            }

            val pkt = BitchatPacket.fromBinaryData(frame) ?: continue
            val senderPeerHex = pkt.senderID?.toHexString()?.take(16) ?: continue

            // Rebind a provisionally-keyed (incoming) socket once a real peer ID is revealed.
            if (senderPeerHex != myPeerID && senderPeerHex != logicalPeerId) {
                rebindSocket(synced, logicalPeerId, senderPeerHex)
                logicalPeerId = senderPeerHex
            }

            // peerID = originator, relayAddress = neighbor that sent it to us.
            meshCore.processIncoming(pkt, senderPeerHex, logicalPeerId, null)
        }

        Log.i(TAG, "Disconnected from ${logicalPeerId.take(8)} (socket closed)")
        val socketForCleanup = synced
        socketToPeer.remove(socketForCleanup)
        if (peerSockets[logicalPeerId] === socketForCleanup) {
            peerSockets.remove(logicalPeerId)
        }
        handlePeerDisconnection(logicalPeerId)
        runCatching { socketForCleanup.close() }
    }

    private fun rebindSocket(socket: SyncedSocket, oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        val existing = peerSockets[newKey]
        if (existing != null && existing !== socket) {
            // We already have a socket for this peer; don't clobber it.
            return
        }
        if (peerSockets[oldKey] === socket) {
            peerSockets.remove(oldKey)
        }
        peerSockets[newKey] = socket
        socketToPeer[socket] = newKey
        meshCore.addOrUpdatePeer(newKey, newKey)
        Log.d(TAG, "Rebound socket ${oldKey.take(8)} -> ${newKey.take(8)}")
    }

    private fun handlePeerDisconnection(peerId: String) {
        serviceScope.launch {
            meshCore.removePeer(peerId)
        }
    }

    // ---------------------------------------------------------------------------------
    // MeshService implementation (delegates to MeshCore)
    // ---------------------------------------------------------------------------------

    override fun sendMessage(content: String, mentions: List<String>, channel: String?) {
        meshCore.sendMessage(content, mentions, channel)
    }

    override fun sendPrivateMessage(
        content: String,
        recipientPeerID: String,
        recipientNickname: String,
        messageID: String?
    ) {
        meshCore.sendPrivateMessage(content, recipientPeerID, recipientNickname, messageID)
    }

    override fun sendReadReceipt(messageID: String, recipientPeerID: String, readerNickname: String) {
        meshCore.sendReadReceipt(messageID, recipientPeerID, readerNickname)
    }

    override fun sendVerifyChallenge(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        meshCore.sendVerifyChallenge(peerID, noiseKeyHex, nonceA)
    }

    override fun sendVerifyResponse(peerID: String, noiseKeyHex: String, nonceA: ByteArray) {
        meshCore.sendVerifyResponse(peerID, noiseKeyHex, nonceA)
    }

    override fun sendFileBroadcast(file: BitchatFilePacket) {
        meshCore.sendFileBroadcast(file)
    }

    override fun sendFilePrivate(recipientPeerID: String, file: BitchatFilePacket) {
        meshCore.sendFilePrivate(recipientPeerID, file)
    }

    override fun sendVoiceFrame(recipientPeerID: String?, payload: ByteArray) {
        meshCore.sendVoiceFrame(recipientPeerID, payload)
    }

    override fun prepareFilePrivate(
        recipientPeerID: String,
        file: BitchatFilePacket,
        transferId: String,
        allowLegacyFallback: Boolean
    ): PrivateMediaPreparation {
        return meshCore.prepareFilePrivate(recipientPeerID, file, transferId, allowLegacyFallback)
    }

    override fun cancelFileTransfer(transferId: String): Boolean {
        return meshCore.cancelFileTransfer(transferId)
    }

    override fun sendBroadcastAnnounce() {
        meshCore.sendBroadcastAnnounce()
    }

    override fun sendAnnouncementToPeer(peerID: String) {
        meshCore.sendAnnouncementToPeer(peerID)
    }

    override fun getPeerNicknames(): Map<String, String> = meshCore.getPeerNicknames()

    override fun getPeerRSSI(): Map<String, Int> = meshCore.getPeerRSSI()

    override fun getActivePeerCount(): Int = meshCore.getActivePeerCount()

    override fun hasEstablishedSession(peerID: String): Boolean = meshCore.hasEstablishedSession(peerID)

    override fun getSessionState(peerID: String): com.bitchat.android.noise.NoiseSession.NoiseSessionState =
        meshCore.getSessionState(peerID)

    override fun initiateNoiseHandshake(peerID: String) = meshCore.initiateNoiseHandshake(peerID)

    override fun getPeerFingerprint(peerID: String): String? = meshCore.getPeerFingerprint(peerID)

    override fun getPeerInfo(peerID: String): PeerInfo? = meshCore.getPeerInfo(peerID)

    override fun updatePeerInfo(
        peerID: String,
        nickname: String,
        noisePublicKey: ByteArray,
        signingPublicKey: ByteArray,
        isVerified: Boolean
    ): Boolean = meshCore.updatePeerInfo(peerID, nickname, noisePublicKey, signingPublicKey, isVerified)

    override fun getIdentityFingerprint(): String = meshCore.getIdentityFingerprint()

    override fun getStaticNoisePublicKey(): ByteArray? = meshCore.getStaticNoisePublicKey()

    override fun shouldShowEncryptionIcon(peerID: String): Boolean = meshCore.shouldShowEncryptionIcon(peerID)

    override fun getEncryptedPeers(): List<String> = meshCore.getEncryptedPeers()

    override fun getDeviceAddressForPeer(peerID: String): String? = tcpTransport.getDeviceAddressForPeer(peerID)

    override fun getDeviceAddressToPeerMapping(): Map<String, String> = tcpTransport.getDeviceAddressToPeerMapping()

    override fun printDeviceAddressesForPeers(): String {
        val map = getDeviceAddressToPeerMapping()
        return if (map.isEmpty()) {
            "TCP: no connected peers"
        } else {
            map.entries.joinToString("\n") { "${it.key.take(8)} -> ${it.value}" }
        }
    }

    override fun getDebugStatus(): String {
        val transportInfo = buildString {
            appendLine("TCP server active: $isActive")
            appendLine("TCP port: $TCP_PORT, discovery port: $DISCOVERY_PORT")
            appendLine("Connected peers: ${peerSockets.size}")
            peerSockets.forEach { (pid, sock) ->
                appendLine("  ${pid.take(8)} (${socketAddress(sock)})")
            }
        }
        val deviceMap = peerSockets.mapValues { socketAddress(it.value) }
        return meshCore.getDebugStatus(transportInfo, deviceMap, title = "LAN TCP Mesh Debug Status")
    }

    private fun socketAddress(sock: SyncedSocket): String {
        return socketPeerAddress(sock)
    }

    override fun clearAllInternalData() {
        meshCore.clearAllInternalData()
    }

    override fun clearAllEncryptionData() {
        meshCore.clearAllEncryptionData()
    }

    // ---------------------------------------------------------------------------------
    // MeshTransport
    // ---------------------------------------------------------------------------------

    private inner class TcpTransport : MeshTransport {
        override val id: String = TRANSPORT_ID

        override fun broadcastPacket(routed: RoutedPacket): Boolean =
            this@TcpMeshService.broadcastPacket(routed)

        override fun sendPacketToPeer(peerID: String, packet: BitchatPacket): Boolean =
            this@TcpMeshService.sendPacketToPeer(peerID, packet)

        override fun cancelTransfer(transferId: String): Boolean =
            fragmentingSender.cancelTransfer(transferId)

        override fun getDeviceAddressForPeer(peerID: String): String? =
            peerSockets[peerID]?.let { socketPeerAddress(it) }

        override fun getDeviceAddressToPeerMapping(): Map<String, String> =
            peerSockets.mapValues { socketPeerAddress(it.value) }

        override fun getTransportDebugInfo(): String = buildString {
            appendLine("TCP transport: ${peerSockets.size} active sockets")
            peerSockets.forEach { (pid, sock) ->
                appendLine("  ${pid.take(8)} -> ${socketPeerAddress(sock)}")
            }
        }
    }

    private fun socketPeerAddress(sock: SyncedSocket): String {
        return try {
            sock.inetAddress?.hostAddress ?: sock.rawSocket.remoteSocketAddress?.toString() ?: "unknown"
        } catch (_: Exception) { "unknown" }
    }
}
