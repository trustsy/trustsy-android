# Native flow contract (v1)

The trustsy native SDKs render a verification flow with **native UI** — Jetpack
Compose on Android, SwiftUI on iOS. There is no WebView. Flutter and React
Native expose framework-native components that host those two implementations.

One flow, many front ends: a workflow built once in the console drives the web
verify app AND the native SDKs, because every front end speaks the same applicant
API and the SERVER's statechart decides every step. A front end only decides
*how a step looks*, never *which step comes next*.

The reference implementation is the web verify app (`frontend/verify/src/flow`).
Where this document is silent, that code is the answer; file names are given.

## 1. Session

The SDK needs three things: the API origin, an applicant bearer token, and the
server's pinned **sealing key** (`sealed-transport.md`).

| input | example | notes |
|---|---|---|
| link | `https://verify.example.com/u/AAECAwQFBgcICQoLDA0ODw` | what the console's "Share with applicant" shows |
| link | `https://verify.example.com/verify?token=<uuid>` | older link form |
| token + api base | `token=<uuid>`, `apiBaseUrl=https://verify.example.com` | what a partner backend gets from `POST /api/v1/cases/{id}/tokens` |
| sealing key | `AQAgAAEAAlftwPm3…` | always from the host app's build config, never from the network |

- `/u/<code>`: `code` is the token UUID's 16 bytes, base64url, no padding (22 chars).
  Decode, re-encode, and accept only if it round-trips (`flowLink.ts::decodeFlowCode`).
- The API base for a link is the link's ORIGIN.
- `/resume/<token>` links (desktop → phone handoff) are not accepted by the SDK.

**Transport.** Every call in this document is made THROUGH the sealed transport
(`sealed-transport.md`): the method, path, token and JSON body below go inside an
encrypted envelope to `POST /api/v1/sealed`, and the answer comes back sealed. The paths
and bodies written here are the envelope's contents, not what appears on the wire. There
is no plaintext mode.

## 2. The loop

```
GET  /api/v1/collection-flow                  → snapshot
classify snapshot  (§3)                       → screen
render screen; the applicant acts             → one of the calls in §4
every call that advances the flow returns the next snapshot → classify again
```

A snapshot (`CollectionFlowDto`, `types.ts::CollectionFlowSnapshot`):
`{ id, state, status, tags[], context, available_events[], done, branding, language?, step? }`.

- `id` is the case (runtime) id, needed by the liveness submit path.
- `branding.display_name` names the partner; `branding.privacy_url` / `terms_url`
  link their policies on the consent screen; `branding.style.colors.primary_button`
  / `primary_button_text` (`#RRGGBB` or `#RRGGBBAA`) colour the main buttons when
  present (`customization.ts`). Never read branding from `context`.
- `language` (`en` | `th`) is the language the link was minted with. Use it unless
  the host app passed one explicitly; else the device language if `th`, else `en`.

On `review`, `checking`, `blocked` and `awaitingUsers` screens, poll
`GET /collection-flow` every 4 s while visible (the web app also listens on a
realtime channel; polling is enough for v1).

## 3. Snapshot → screen

Port `step.ts::stepFor` and `screen.ts::screenFor` EXACTLY. They are pure.
`sdk/contract/screen-vectors.json` holds 78 input → output cases, generated from
those two functions (`frontend/verify/src/flow/screenVectors.test.ts`). Each SDK's
unit tests MUST replay every vector and compare `step.kind`, `step.actions`,
`step.outcome` and the whole `screen` object. Never edit the JSON by hand.

Screen kinds and v1 native support:

| screen | v1 native | event(s) |
|---|---|---|
| `terminal{outcome}` | yes — the SDK finishes with that outcome | — |
| `review`, `checking`, `blocked` | yes — wait screens, polling | — |
| `consent` | yes | `CONSENT_GIVEN` |
| `details` | yes | `DETAILS_SUBMITTED` |
| `document{offered}` / `documentBack{document}` | yes | `*_CAPTURED` |
| `documentChip` | yes (NFC) | via `POST /document-chip`, or `DOCUMENT_CHIP_UNAVAILABLE` |
| `dipChip` | yes, on a partner terminal (USB card reader + `terminal` config); else `unsupportedStep` | via `POST /flow/{id}/collection/chip` (`dip-chip.md`) |
| `phone` | yes | `PHONE_VERIFIED` |
| `liveness` | yes | via `POST /flow/{id}/collection/liveness` |
| `actions{actions}` | yes — one button per event | the event |
| `signature`, `company`, `keyPeople`, `awaitingUsers`, `documentGroups`, `businessDocument` | **no** | — |

An unsupported screen ends the SDK with `unsupportedStep(screen, state)`, so the host
app can send the applicant to the web link instead. It never shows a broken screen.

## 4. Calls, per screen

All bodies are JSON. Errors: a non-2xx with `{code, message, details?}`. A `403` with
code `location_not_allowed` or `vpn_not_allowed` is an access refusal: show
"verification is not available from your location / network" with a retry.

### Events — `POST /api/v1/collection-flow/events`
Body `{ "type": "<EVENT>", "payload": <object|omitted>, "telemetry": <object|omitted> }` → snapshot.
`telemetry` is optional device info (send `{ "device": { "platform", "model", "os_version" }, "sdk": "trustsy-<platform>/<version>" }`).

### Consent (`ConsentStep.tsx`)
Show what is collected (identity document, face, and the documents the flow asks for),
the partner name, trustsy's privacy notice and the partner's `privacy_url` / `terms_url`.
Agreeing REQUIRES a fresh location fix (no cached position — `maximumAge: 0` on the
web): `CONSENT_GIVEN` with payload
`{ "geolocation": { "latitude", "longitude", "accuracy", "altitude"?, "timestamp"? } }`
(`timestamp` = epoch ms). The server refuses consent without it. If permission is
denied, explain that location is required and offer to open settings.

### Details (`DetailsForm.tsx`)
Fields `firstName`, `lastName`, `dateOfBirth` (ISO `YYYY-MM-DD`), `nationality`
(ISO alpha-2), `phone` (E.164), `email` — see the file for the full list. Send only
non-empty fields: `DETAILS_SUBMITTED` with `{ "<field>": "<value>", … }`.

### Documents (`documents.ts`, `capture.ts`, `DocumentChoice.tsx`, `CaptureDocument.tsx`)
- `document{offered}`: one offered → go straight to its capture. Several → ask the
  issuing country first: `TH` offers `thai_id`, `driving_license`; any other country
  offers `passport` (`documentsFor`). Then a short guide, then the camera.
- Capture with the BACK camera, JPEG quality ~0.92, long edge ≤ 2048 px.
- Upload: `POST /api/v1/collection-flow/files` body
  `{ "content_base64", "content_type": "image/jpeg", "file_name" }` → `{ "file_id" }`.
  `file_name` MUST start `id-`: `id-front.jpg`, `id-back.jpg`,
  `id-driving-license-front.jpg`, `id-driving-license-back.jpg`, `id-passport.jpg`
  (`fileNameFor`) — the operator review lists files by that prefix.
- Then the event for that document and side (`DOCUMENTS` in `documents.ts`) with
  `{ "file_id", "side": "front"|"back", "country"? }` (`country` only when the applicant chose one).

### Document chip (`DocumentChipStep.tsx`, `documentChip.ts`)
1. Ask for the BAC/PACE key: document number, date of birth, expiry date (the
   `normalizeDocumentNumber` / `mrzDate` rules). Reuse the SDK's existing chip reader.
2. `POST /api/v1/collection-flow/document-chip/challenge` body `{}` →
   `{ challenge_id, challenge (base64, 8 bytes), expires_at }`.
3. Read SOD, DG1, DG2, DG14, DG15; run Active Authentication over the challenge.
4. `POST /api/v1/collection-flow/document-chip` body
   `{ sod, dg1, dg2?, dg14?, dg15?, active_authentication?: { challenge_id, signature }, reader?: {platform, sdk, sdk_version, access_control, chip_authentication} }`
   (all base64) → snapshot. The server verifies and fires `DOCUMENT_CHIP_READ` itself.
5. No NFC, NFC off, or the applicant declines: `DOCUMENT_CHIP_UNAVAILABLE` with
   `{ "reason": "no_nfc" | "declined" }`.

### Dip chip (`DipChipStep.tsx`, `dipChip.ts`; `dip-chip.md`)
A Thai ID card in a USB contact reader, on a device the host declared a partner
terminal. Wait for a reader, then a card; read it (`dip-chip.md` "The read"), seal it to
the pinned CHIP key, and `POST /api/v1/flow/{id}/collection/chip`
`{ terminal_id, event_time, enc }` → snapshot. The server fires `CHIP_READ` itself.
After a failed read, wait for the card to be REMOVED before reading again. A `403` that
is not an access refusal ends the run with `terminal_not_authorized`. The web shows only
where the step happens.

### Phone (`PhoneStep.tsx`)
`POST /api/v1/collection-flow/phone/send` `{ "phone": "<E.164>" }` → `{ ref_code, phone_masked, status }`;
`…/phone/verify` `{ "code" }` → `{ verified, status }` — **a wrong code is a 200 with
`verified: false`**; `…/phone/resend` `{}`. Only after `verified: true`: event
`PHONE_VERIFIED` (no payload).

### Liveness (`CaptureLiveness.tsx`, `ml/*`, `capture.ts::submitCapturedSelfie`)

1. **Position first, then start the session.** Front camera. Wait until ONE face is
   centred, large enough, roughly frontal (|yaw|, |pitch|, |roll| < 12°) and stable
   for ~1 s. Only THEN call the session — each step expires 20 s after it is issued
   (`STEP_TTL_SECS`), so positioning must not eat into it.
2. `POST /api/v1/collection-flow/liveness-session` body `{}` (or `{ "flash": false }`
   when the OS "reduce motion" setting is on) →
   `{ sessionId, state, challenge: { challengeId, step, action, sequence? } | null, … }`.
   Calling it again RESUMES the outstanding challenge.
3. Prompt the `action`: `turn_left`, `turn_right`, `blink`, `open_mouth`, `smile`,
   `nod`, `head_shake`, `move_closer`, `move_back`, `center`, `flash`.
4. Sample frames at 5–10 Hz while the step is live. Each frame
   (`liveness_challenge::ReportedFrame`):
   `{ yaw, pitch, roll, face_size, t_ms, eye_open?, smile?, mouth_open? }`
   - `yaw` degrees, **positive = the applicant turned to THEIR OWN LEFT**. The server
     passes `turn_left` when yaw RISES by more than 18° within the step and
     `turn_right` when it FALLS by more than 18° (a movement, not an absolute angle).
   - `pitch`, `roll` degrees. `face_size` = face box diagonal in pixels.
   - `t_ms` = ms since the run started (monotonic).
   - `eye_open` = open probability 0–1 (both eyes averaged). Blink passes on a dip
     below 0.35 then a recovery above 0.65 within 1000 ms.
   - `smile` 0–1 (passes above 0.55). `mouth_open` 0–1 jaw-open (passes on ≤ 0.45
     then > 0.45). Omit a field you cannot measure — never send a constant.
   - `move_closer` / `move_back`: `face_size` grows/shrinks by 1.25× against the
     step's median.
5. When the device judges the action done, `POST …/liveness-session/step`
   `{ sessionId, challengeId, frames: [every frame since the previous step was accepted] }`
   → the next session state. `state: "passed"` with `challenge: null` means the run
   is over; any other state without a challenge means it failed. A refused step fails the SESSION (no retry of that step); offer to start again.
6. `flash` (`sequence: [{ color: "red"|"green"|"blue", ms }]`): fill the screen with
   each colour in turn at full brightness while recording a SEPARATE front-camera clip;
   start the recording and paint the first colour in the same frame. Then submit the
   step with `frames: []` and keep the clip.
7. Record the whole challenge run as ONE video. Take a still selfie (JPEG) at a neutral pose.
8. Upload: selfie `{ content_type: "image/jpeg", file_name: "selfie.jpg" }`; run video
   `{ content_type: "video/mp4", file_name: "liveness.mp4" }`; flash clip
   `{ content_type: "video/mp4", file_name: "liveness-flash.mp4" }`.
   **MP4 only**: the upload sniffs the `ftyp` brand (`isom`, `iso2`, `iso4`, `mp41`,
   `mp42`, `avc1`, `M4V `, `dash`, `mmp4`). A QuickTime `.mov` (`qt  `) is refused —
   on iOS write with `AVAssetWriter` + `.mp4`, not `AVCaptureMovieFileOutput`.
9. `POST /api/v1/flow/{snapshot.id}/collection/liveness` body
   `{ photo_file_id, video_file_id?, flash_video_file_id?, challenge_report?: { challenges: [actions issued], frames: [last ≤ 30 frames] }, telemetry? }`
   → snapshot.

The device's judgement only decides when to submit. The server re-judges every step
from the numbers, and the biometric sidecar judges the video; nothing the SDK says
is believed.

### Session chat (`ChatWidget.tsx`; Android `internal/chat/*`, iOS `Chat.swift` + `ChatOverlay.swift`)

Drawn over EVERY step, invisible until the thread holds a message (the reviewing team writes
first; the applicant can only reply, `can_reply`).

- `GET /api/v1/collection-flow/messages` → `{messages: [{id, sender: operator|applicant, body,
  created_at, read_at}], can_reply}`. Operators are never named to the applicant.
- `?mark_read=true` ONLY while the chat is open — it stamps the team's messages `read_at`, which is
  also the unseen badge (`sender == operator && read_at == null`), so the count survives a restart.
  A closed launcher reads without the flag.
- `POST` the same path `{body}` (1–2000 chars) → the thread. `409 chat_not_open` before the team
  writes; `429 chat_rate_limited` past 10/minute or 100 per case — say "wait a minute".
- Live: dial Soketi with the descriptor from `GET /collection-flow/realtime`
  (`ws(s)://host:port/app/{key}?protocol=7`), sign `private-flow-{case}` through
  `POST /collection-flow/pusher/auth {socket_id, channel_name}`, and on event `chat-message`
  re-read the thread. The event carries an id only — NEVER render socket data: the text stays
  inside the sealed transport. Answer `pusher:ping` with `pusher:pong`; reconnect with backoff.
  Without a key (realtime off) poll every 20 s.
- A chat that fails to load is not shown; it must never block a step.

## 5. Result

The SDK ends with exactly one of:

| result | when |
|---|---|
| `completed(outcome)` | a `terminal` screen, or a `review` screen the applicant closes. `outcome` ∈ `approved`, `rejected`, `completed`, `failed`, `review` |
| `cancelled` | the applicant closed the flow before handing off |
| `unsupportedStep(screen, state)` | §3 |
| `error(code, message)` | `invalid_link`, `invalid_sealing_key`, `sealing_key_rejected` (422 `sealed_unknown_key`), `sealed_response_invalid`, `clock_skew` (`sealed_stale`), `unauthorized` (401/404 on the first load: expired or unknown token), `network` |

## 6. Copy

English and Thai, taken from the web app's locale files
(`frontend/verify/public/locales/{en,th}/{flow,screens,common}.json`) for the screens
the SDK implements, so a flow reads the same in a browser and in an app.
