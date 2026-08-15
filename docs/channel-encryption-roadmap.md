# Channel Encryption Roadmap

Topic channels (`#room`) support optional password protection, but the
end-to-end encryption path is still incomplete. This document records what
works, what is stubbed, and the steps needed to finish it.

## What works today

- `ChannelManager.verifyChannelPassword` performs a real check against a
  persisted key commitment (SHA-256 of the PBKDF2-derived AES key). The old
  `// TODO: REMOVE THIS - FOR TESTING ONLY` always-true stub is removed.
- Key commitment is persisted via `DataManager.saveChannelKeyCommitments` /
  `loadChannelKeyCommitments`, so verification survives app restarts.
- `NoiseChannelEncryption` (`noise/NoiseChannelEncryption.kt`) holds a complete,
  iOS-compatible reference implementation of PBKDF2-HMAC-SHA256 key derivation,
  AES-256-GCM encrypt/decrypt, and commitment calculation.

## What is stubbed

- `ChannelManager.sendEncryptedChannelMessage` is
  `// TODO: REIMPLEMENT – REMOVED FOR NOW` and returns immediately.
- `MeshCore.sendMessage` only broadcasts plaintext `content.toByteArray()`;
  it has no path for `BitchatMessage.isEncrypted` + `encryptedContent`
  (flag `0x80` already exists in the binary wire format).
- The receive path (`MeshDelegateHandler.didReceiveMessage`) does not decrypt
  `isEncrypted` channel messages. `decryptChannelMessage` exists in the
  delegate chain but is never called for incoming channel traffic.
- Password-protected status and key commitment are stored only locally; they
  are not broadcast to peers, so other devices cannot learn that a channel
  requires a password.

## Key derivation (compatibility contract)

- PBKDF2-HMAC-SHA256, 100,000 iterations, 256-bit key.
- Salt = channel name UTF-8 bytes. `ChannelManager.deriveChannelKey` and
  `NoiseChannelEncryption` must stay in sync.
- Encryption = AES-256-GCM, 12-byte IV prepended to ciphertext, 128-bit tag.

## To finish end-to-end encrypted channels

1. Implement `sendEncryptedChannelMessage` to encrypt via the channel key and
   deliver ciphertext through `onEncryptedPayload`, falling back to
   `onFallback()` when no key exists.
2. Add a mesh transport path that carries `isEncrypted` + `encryptedContent`.
3. In the receive path, detect `isEncrypted` and decrypt via
   `decryptChannelMessage` before display/persistence.
4. Broadcast password-protected status and key commitment in channel metadata
   so peers can prompt for a password and verify it.
