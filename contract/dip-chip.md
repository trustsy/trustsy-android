# Dip-chip contract — Thai national ID over a contact smart card reader

The `dip_chip` step (`CHIP_READ`) reads a Thai national ID card **inserted** into a
contact reader. It is not the NFC step (`DOCUMENT_CHIP_READ`, ICAO 9303) — a Thai ID
card's chip answers only on its contacts. Three implementations follow this document:

| Host | Reader access | Code |
|---|---|---|
| Desktop (Windows / macOS / Linux) | PC/SC | `sdk/desktop/trustsy-dipchip` (Rust; C ABI in `trustsy-dipchip-ffi`) |
| Android | USB host + CCID (no PC/SC on Android) | `sdk/android/…/internal/dipchip/` |
| iOS / iPadOS | CryptoTokenKit `TKSmartCard` (USB-C / Lightning CCID readers) | `sdk/ios/Sources/TrustsyVerifyCore/DipChip*.swift` |

`dip-chip-vectors.json` is generated from the Rust crate
(`TRUSTSY_WRITE_CONTRACTS=1 cargo test -p trustsy-dipchip --test contract_vectors`, run
in `sdk/desktop`) and replayed by the Kotlin and Swift test suites. Change the Rust
read or seal → regenerate → both native suites must still pass.

## Trust model — read this first

The server accepts a chip read only when:

1. the applicant token was minted **with a `terminal_id`** (`POST /api/v1/cases/{id}/tokens`
   `{"terminal_id": …}`), and that terminal is on the API key's `allowed_terminals`;
2. the body's `terminal_id` equals the token's;
3. the payload opens under the server's chip key with `terminal_id` + `event_time` as AAD;
4. `event_time` is within 10 minutes of the server's clock;
5. the CID passes the mod-11 checksum.

The card proves nothing cryptographically (the Thai ID contact chip exposes no signature
these SDKs verify). A chip read is trusted because it came from a **partner-controlled
terminal** — a branch tablet, a teller desktop, a kiosk — not from the applicant's own
phone. Mint terminal-bound tokens only for devices you control.

## The read (identical on every host)

Transport: `transmit(command) → response` (data ‖ SW1 SW2) and `atr()`.

1. `SELECT 00 A4 04 00 08 A0 00 00 00 54 48 00 01`. Success is SW `90 00` **or** SW1 `61`;
   anything else → `read_failed` (not a Thai ID card).
2. GET RESPONSE base: `00 C0 00 01` when the ATR starts `3B 67`, else `00 C0 00 00`.
3. Each field, in this order, is two exchanges: send the read command; the answer must
   start `61` (else `read_failed`); then send `base ‖ Le` where `Le` is the read
   command's LAST byte; strip the trailing two SW bytes from that answer.

   | Field | Read command | Decode |
   |---|---|---|
   | cid | `80 B0 00 04 02 00 0D` | UTF-8 |
   | name (Thai) | `80 B0 00 11 02 00 64` | TIS-620 |
   | name (English) | `80 B0 00 75 02 00 64` | UTF-8 |
   | date of birth | `80 B0 00 D9 02 00 08` | UTF-8 |
   | gender | `80 B0 00 E1 02 00 01` | UTF-8 |
   | card issuer | `80 B0 00 F6 02 00 64` | TIS-620 |
   | issue date | `80 B0 01 67 02 00 08` | UTF-8 |
   | expiry date | `80 B0 01 6F 02 00 08` | UTF-8 |
   | address | `80 B0 15 79 02 00 64` | TIS-620 |

   Then trim Unicode whitespace from both ends. "UTF-8" decodes leniently (invalid bytes
   become U+FFFD). TIS-620 uses the 256-entry `tis620` table in the vectors (null =
   unmappable → the whole read fails `read_failed`).
4. Portrait: the 20 commands `80 B0 hi lo 02 00 FF` with `hi` = `01…14` and `lo` = `7B`
   down to `68`, each read like a field; concatenate the raw chunks; standard base64.
   Always read — the flow's face match compares the selfie with it.

### Parsing

- **Name** `prefix#first#middle#last` (missing parts empty): `prefix + first + " " + last`,
  or `prefix + first + " " + middle + " " + last` when middle is non-empty.
- **Address**: split on `#`; start with part 0 (even when empty), then append `" " + part`
  for every LATER non-empty part. (An empty part 0 therefore yields a leading space.)
- **Dates** `YYYYMMDD` in the Buddhist era: exactly 8 ASCII digits, year − 543, and a real
  calendar date — otherwise absent (`25400000`, `99999999`, `25600229` are all absent).
  Output `YYYY-MM-DD`.
- **Gender** (trimmed): `1` → `M`, `2` → `F`, empty → absent, anything else passes through.

### Sealed payload fields

`idNumber` = `thaiNationalId` = cid · `thaiName` / `englishName` = the formatted names ·
`dateOfBirth` / `dateOfIssue` / `dateOfExpiry` · `gender` · `address`. A string that is
empty **or whitespace-only** is absent (a blank name formats as `" "`). The card issuer is
read but not sent. Plaintext: `{"fields": {…}, "photoBase64": "…"}` as JSON (absent
fields omitted).

## The seal

HPKE base mode (RFC 9180), HKDF-SHA256 / AES-256-GCM, single shot, with the KEM the
pinned chip key implies — its LENGTH decides, unambiguously:

| Pinned chip key | KEM | Envelope `v` | `info` | `enc` |
|---|---|---|---|---|
| 1216 bytes | X-Wing (ML-KEM-768 + X25519, post-quantum) | 3 | `trustsy/chip-verify/hpke/v3` | 1120 bytes |
| 32 bytes | DHKEM(X25519, HKDF-SHA256) | 2 | `trustsy/chip-verify/hpke/v2` | 32 bytes |

- `aad` = `key_id` (1 byte) ‖ u32be(len terminal) ‖ terminal ‖ u32be(len event_time) ‖ event_time
- recipient = the pinned chip key (base64 standard) + `key_id`. This is NOT the
  sealed-transport key; the server holds the private halves in `config.chip`
  (`privatekey`/`keyid` for X25519, `pqprivatekey`/`pqkeyid` for X-Wing) and opens an
  envelope with the key matching its `v` AND `key_id`. Pin it in the app's build config.

Envelope: `{"v": 3|2, "key_id": <u8>, "enc": base64(enc), "ct": base64(ciphertext ‖ tag)}`.

Who seals which: the desktop SDK and the kiosk (`chip-crypto`, Rust) seal v3 when given an
X-Wing key — their submit is plain TLS, so the envelope is the only post-quantum layer.
The Android and iOS SDKs seal v2: their submit already travels inside the sealed
transport, which is post-quantum whenever the device can do X-Wing.

The vectors carry `seal` (v2, X25519) and `sealV3` (v3, X-Wing), each a recipient secret
key and an envelope sealed by Rust: a port proves it agrees on suite, `info` and AAD by
OPENING the ones it seals, and by building the same AAD bytes. It then seals its own
payload and opens it again.

## The submit

`POST /api/v1/flow/{flow_id}/collection/chip`, applicant bearer, body
`{"terminal_id": …, "event_time": <RFC 3339, now>, "enc": <envelope>}` → the next flow
snapshot (the server fires `CHIP_READ` itself). Native SDKs send it through the sealed
transport (`sealed-transport.md`); the desktop SDK over TLS like the kiosk. `flow_id` is the
case id. Refusals the UI distinguishes: `403` (token not bound to this terminal → setup
problem, not the applicant's), `400 invalid_cid` / `missing_cid` (a damaged or foreign
card → ask to re-insert), `400 stale_event_time` (device clock), `503 chip_unavailable`.

## Native screen

A snapshot whose applicant events include `CHIP_READ` mounts the dip-chip screen
(`screen-vectors.json` kind `dipChip`). On a host without terminal configuration
(`terminalId` + chip key) the SDK ends with `unsupportedStep` — it cannot read for a case
whose token it cannot match. Otherwise: "connect a reader" → "insert the card" → reading →
the server's snapshot. The screen never shows the card's contents; the read is the
applicant's own and the operator's confirmation, if any, is the partner app's UI.

### Android (USB CCID)

Interface class `0x0B`, one bulk-OUT and one bulk-IN endpoint. 10-byte headers,
little-endian lengths, `bSeq` incremented per command:

- `PC_to_RDR_IccPowerOn` `62` (bPowerSelect 0 = automatic) → `RDR_to_PC_DataBlock` `80` = ATR
- `PC_to_RDR_XfrBlock` `6F` (bBWI 0, wLevelParameter 0) → `80` = response APDU
- `PC_to_RDR_GetSlotStatus` `65` → `RDR_to_PC_SlotStatus` `81`; bmICCStatus (bStatus & 3):
  0 present+active, 1 present+inactive, 2 absent
- `PC_to_RDR_IccPowerOff` `63` → `81`
- bmCommandStatus (bStatus >> 6): 0 ok, 1 failed (bError says why), 2 time extension
  (keep reading). A response spans bulk-IN packets until `10 + dwLength` bytes arrived.

Readers that exchange at **character** level (dwFeatures without TPDU/short-APDU/extended-
APDU bits) are refused (`reader_unsupported`). TPDU-level readers work because every
command here is a T=0 case 3 (field reads, SELECT) or case 2 (GET RESPONSE) and the host
already drives the `61 xx` exchange. No `SetParameters` is sent: the Thai ID ATRs seen
(`3B 68 …`, `3B 67 …`) carry no TA1, so the default T=0 parameters a reader starts with
apply. A reader that insists on host-sent parameters is untested.

### iOS (CryptoTokenKit)

`TKSmartCardSlotManager.default` (non-nil on iPhone/iPad without an entitlement; macOS
needs `com.apple.security.smartcard`) → slot by name → `makeSmartCard()` →
`beginSession` → `transmit` → `endSession`. ATR from `slot.atr`.
