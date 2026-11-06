# Sealed transport (v1) — end-to-end encryption for the native SDKs

Every call a native SDK makes goes through ONE endpoint, `POST {origin}/api/v1/sealed`,
encrypted to a server key the SDK carries **pinned**. A proxy that the phone trusts
(an installed profile, a user CA on a debug build, a rooted device) still sees only
ciphertext: no token, no path, no photo, no face, no chip data, no snapshot.

Server: `backend/crates/common/sealed-transport` (construction) and
`backend/crates/features/verif/src/sealed_gateway.rs` (the route). The construction is
Oblivious HTTP's (RFC 9458 §4.3–4.4) over a JSON envelope.

## Key config (pinned)

A key config is base64url (no padding) of `key_id (1) ‖ kem_id (2) ‖ kdf_id 0x0001 (2) ‖
aead_id 0x0002 (2) ‖ public key`, big endian. Two suites exist; the KEM names which:

| Suite | kem_id | public key | `enc` | Bytes | Who can seal |
|---|---|---|---|---|---|
| **X-Wing** (ML-KEM-768 + X25519, post-quantum) | `0x647a` | 1216 | 1120 | 1223 | Android (Bouncy Castle 1.86+), iOS 26+ |
| DHKEM(X25519, HKDF-SHA256) | `0x0020` | 32 | 32 | 39 | every SDK |

Both use HKDF-SHA256 / AES-256-GCM, HPKE base mode. X-Wing is secure while EITHER half
is, so it is strictly stronger; X25519 stays only for iOS 17–25, which has no X-Wing.

**The host app pins both, separated by a comma** (base64url never contains one), X-Wing
first by convention. The X25519 dev key config is:

```
AQAgAAEAAlftwPm3Tckm0oDXKB2LUpCcqfYNbSihX2L4bKTH6NB6
```

(the X-Wing dev key config is 1631 characters; the api prints both at boot as
`sealed transport key loaded … kem=…`, and `sdk/README.md` carries them).

Choosing among the pinned entries — every SDK, the same rule:

1. An entry whose `kem_id` this SDK does not know is **skipped** (a newer suite).
2. Any other entry that does not parse — wrong length for its suite, other KDF/AEAD,
   not base64url — **refuses the whole pin** (`invalid_sealing_key`). A truncated X-Wing
   key must fail loudly, never leave the app quietly on X25519.
3. An entry this OS cannot seal to (X-Wing below iOS 26) is skipped.
4. X-Wing wins over X25519; within a suite the **first** entry wins (list the newest
   key first while rotating).

Nothing on the wire negotiates the suite, so a proxy cannot downgrade a client that
holds an X-Wing key. `api --gen-sealing-key <id> [xwing|x25519]` makes a key (X-Wing by
default). **Never fetch a key config at runtime** — a key fetched over the intercepted
channel is the proxy's key.

Android: Bouncy Castle `org.bouncycastle.crypto.hpke.HPKE` (mode 0, kem `kem_X_WING` /
`kem_X25519_SHA256`, kdf 1, aead 2). iOS: CryptoKit `HPKE.Sender` with
`.XWingMLKEM768X25519_SHA256_AES_GCM_256` (iOS 26+) or
`HPKE.Ciphersuite(kem: .Curve25519_HKDF_SHA256, kdf: .HKDF_SHA256, aead: .AES_GCM_256)`.

## Request

```
header = key_id ‖ kem_id ‖ 0x0001 ‖ 0x0002                   (7 bytes, big endian)
info   = "trustsy sealed request v1" ‖ 0x00 ‖ header
(enc, ctx) = HPKE.SetupBaseS(pkR, info)
ct     = ctx.Seal(aad = "", envelope_json)
body   = header ‖ enc (32 X25519 · 1120 X-Wing) ‖ ct
secret = ctx.Export("trustsy sealed response v1", 32)          (keep for the response)

POST /api/v1/sealed
Content-Type: application/trustsy-sealed
(no Authorization header — the token is inside)
```

`envelope_json` (UTF-8; unknown fields are refused):

```json
{ "v": 1, "ts": 1791830000000, "method": "POST",
  "path": "/api/v1/collection-flow/events",
  "token": "<applicant bearer token>",
  "body": { "type": "CONSENT_GIVEN", "payload": { … } } }
```

- `ts`: device clock, epoch ms. More than 5 minutes off → refused (`sealed_stale`).
- `method`: `GET` or `POST`. `body` omitted for a GET.
- `path`: only the applicant surface — `/api/v1/collection-flow[/…]` and
  `/api/v1/flow/{id}/collection/{start|liveness}`. Anything else → 404 `sealed_path_not_allowed`.
- Seal a FRESH request every time (a new `enc`). The server refuses a repeated `enc`
  (`sealed_replayed`) — so never retry by resending the same bytes; reseal instead.

## Response

On `200` with `Content-Type: application/trustsy-sealed`:

```
body   = response_nonce (32) ‖ ct
salt   = enc ‖ response_nonce
prk    = HKDF-Extract(salt, secret)               (HKDF-SHA256)
key    = HKDF-Expand(prk, "key", 32)
iv     = HKDF-Expand(prk, "nonce", 12)
plain  = AES-256-GCM-Open(key, iv, aad = "", ct)
```

`plain` is the inner route's answer, whole:

```json
{ "status": 200, "content_type": "application/json", "body": { …snapshot… } }
```

`body` when the inner answer was JSON, else `body_base64`. Treat `status` exactly as you
would the HTTP status of the plain call (`401` = token expired/unknown, `403
location_not_allowed`, `409 event_not_allowed`, …). Refusals AFTER the request opened
are sealed too (`sealed_stale`, `sealed_replayed`, `sealed_path_not_allowed`,
`sealed_envelope_invalid`, `sealed_version`).

Outer (plain) answers happen only BEFORE the request opens and carry no data:
`415 sealed_content_type`, `400 sealed_malformed` / `sealed_undecryptable`,
`413 sealed_too_large`, `422 sealed_unknown_key` (the pinned key was rotated out — the
host app must ship the new key), `503 sealed_unavailable` (server has no key).

## Client rules

1. Every call is sealed. There is no plaintext fallback, ever.
2. A `200` that is not `application/trustsy-sealed`, or that does not open, is an error
   (`sealed_response_invalid`) — a proxy answering in clear must not be believed.
3. Never put the token in a header, a URL, or a log line.
4. TLS still applies on top (`https://`); this is in addition to it, not instead.

## Web (verify)

The verify SPA seals the same way when built with `FRONTEND_SEALED_KEY_CONFIG` (the
pin, as above): `frontend/verify/src/flow/{sealed,transport}.ts`, X25519 only (no
browser seals X-Wing; an X-Wing entry is skipped, a pin with no X25519 entry fails
every call). Every applicant call goes through `applicantFetch`; unset = plain JSON
over TLS (dev). It also reads the outer `Date` header to correct `ts` once on
`sealed_stale` (a phone clock set by hand). The pin lives in a bundle served over the
same TLS, so this stops TLS terminators and logging proxies from reading applicant
data, not an attacker who can rewrite the page. The public `/api/v1/links/{id}/sessions`
call (no token, no applicant data) stays plain. Vectors: `sealed.test.ts`; against a
live api: `TRUSTSY_SEALED_WEB_URL` + `TRUSTSY_SEALED_WEB_KEY`, `transport.live.test.ts`.

## Test vectors

`sdk/contract/sealed-vectors.json` (generated by
`TRUSTSY_WRITE_CONTRACTS=1 cargo test -p sealed-transport`, then checked by the same
test) holds `suites`: X25519 first, then X-Wing, each `{suite, key, cases}`. For every
suite this SDK can seal to, and each case, an SDK test must:

1. parse `key.key_config` → `key.key_id`, the suite's `kem_id` and `public_key_hex`;
   build `info` == `request_info_hex`;
2. open `request_body_hex` with a library RECIPIENT using `secret_key_hex` (an X25519
   scalar, or the X-Wing 32-byte SEED) → exactly `request_plaintext`, and that
   recipient's `Export("trustsy sealed response v1", 32)` == `export_secret_hex`
   (proves suite, framing and `info` match the server; for X-Wing, also that the SDK's
   library implements the same X-Wing draft as the server's);
3. open `response_body_hex` with the SDK's own response-opening function given
   `export_secret_hex` and `enc_hex` → exactly `response_plaintext`;
4. round trip: seal a request with the SDK's own code to `key_config`, open it with a
   recipient on `secret_key_hex`, and check the SDK's kept secret equals the recipient's export.

Plus the pin-selection rules above, each with a case that would come out differently
under the other rules. The X25519 entry is the pre-X-Wing file's key and cases, kept
byte for byte — app builds already in the field keep opening.

Real-server check: the dev api (`backend/config/config.yaml`, both dev keys) runs the
same gateway; `sealed_gateway_e2e` pins it from Rust (a session over X-Wing with X25519
beside it, and a cross-suite header refused), and each SDK's opt-in live test
(`TRUSTSY_LIVE_*` on Android, `TRUSTSY_SEALED_*` on iOS) seals to it from the device's library.
