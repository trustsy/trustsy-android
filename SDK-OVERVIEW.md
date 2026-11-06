# trustsy native SDKs

Build a verification flow **once** in the flow builder; run it on the web (the verify
app) and in a native app. The SDKs draw every step with the platform's own UI —
Jetpack Compose on Android, SwiftUI on iOS — and add what a browser cannot do:
reading **ePassport / eID chips over NFC** (ICAO Doc 9303) and on-device liveness.
The server's statechart still decides every step; an SDK only decides how it looks.

| Package | Path | UI |
|---|---|---|
| Android (Kotlin) — `com.trustsy:trustsy-verify` 0.2.0 | `sdk/android` | Compose |
| iOS (Swift) — `TrustsyVerify` 0.2.0 (SwiftPM / CocoaPods), **iOS 17+** (post-quantum sealing on iOS 26+) | `sdk/ios` | SwiftUI |
| Flutter — `trustsy_verify` 0.2.0 | `sdk/flutter/trustsy_verify` | the Android + iOS screens, full screen or embedded as a platform view |
| React Native — `@trustsy/react-native-verify` | `sdk/react-native` | the Android + iOS screens, full screen or a Fabric component (New Architecture) |
| Desktop dip chip (Rust + C ABI) — `trustsy-dipchip` | `sdk/desktop` | none: reads a Thai ID card over PC/SC for your own desktop app (`sdk/desktop/README.md`) |
| Web (JavaScript, zoid) — `trustsy-verify.js` | `frontend/verify/src/sdk` | the verify web flow, in a popup (default) or an iframe on your page — see "Web SDK" below |

Contracts every implementation is tested against (`sdk/contract/`):

- `native-flow.md` — the calls and payloads per screen, liveness, results.
- `screen-vectors.json` — which screen a snapshot mounts; generated from the web app's own
  `stepFor` + `screenFor` (`frontend/verify/src/flow/screenVectors.test.ts`).
- `sealed-transport.md` + `sealed-vectors.json` — the end-to-end encryption.
- `dip-chip.md` + `dip-chip-vectors.json` — reading a Thai ID card in a contact reader;
  generated from the desktop crate and replayed by the Android and iOS ports.

## End-to-end encryption

Every SDK request goes to `POST /api/v1/sealed`, encrypted (HPKE, HKDF-SHA256 / AES-256-GCM,
post-quantum **X-Wing** — ML-KEM-768 + X25519 — where the device can, X25519 otherwise) to a
server key **built into your app**. The method, path, applicant token and body travel inside
the ciphertext, and the answer comes back encrypted to a key only that request's sender
holds. A proxy the phone has been told to trust — a user-installed CA, a configuration
profile, a rooted device — sees only `POST /api/v1/sealed` and a length. Replayed requests
are refused. There is no plaintext fallback.

- **Pin two key configs, comma-separated: the X-Wing one and the X25519 one.** Android seals
  with X-Wing; iOS does from iOS 26 and uses X25519 on iOS 17–25. The rules for choosing are
  in `contract/sealed-transport.md` — a malformed entry refuses the whole pin rather than
  quietly falling back.
- Get the key configs from the api's boot log (`sealed transport key loaded … kem=… key_config=…`)
  or generate key pairs with `api --gen-sealing-key <id> xwing` / `… <id> x25519` and put the
  secrets in `SEALED_PQSECRETKEY` / `SEALED_SECRETKEY` (each with its own id). **Ship the key
  configs in your app's build config — never fetch them at runtime.**
- Rotating (per suite): set the new key in the current slot and the old one in the
  `previous…` slot, ship app builds pinning the new key, then drop the old one. An app
  pinning only removed keys ends with `sealing_key_rejected`. Emptying the X25519 key is the
  whole switch to post-quantum-only, once no iOS 17–25 build is left.
- Dev key configs (`backend/config/config.yaml`, examples use them) — X25519 (id 1):
  `AQAgAAEAAlftwPm3Tckm0oDXKB2LUpCcqfYNbSihX2L4bKTH6NB6`
  and X-Wing (id 2):
  `AmR6AAEAAqkScFwjjMlIi9VSv7EoD_VCyaBCu0eLywPCp_RGloyyTb-RTQdRGBHBQY1QjEHKNK-wexTytpGwpq9FIHv6AEU1yrYTxMyQa6_grRrQrRFEf67bIy1kXGiCzQjXxyqbn9JKpgiTqhpzRf_JOlYceuREZwZKpw1Enncrk34kgdISmcXDxH27X-bWNFoJtAkZJwdFd2CrdT8JAetMm7mpBYEsDyoIVHPmbYq8PPATqZ87WpFCc8DSlADbuIOyNmvBkJlpd-hobfcLXxA2qo08hF3Kz2nLs9OUOTHQc838HovSeIcDJPx2t8xSugZcG_glsnR0RaulTqanW3bkbkxFEh2sL8uGGumaL8MrkU_yuJk3NGtwkjO1SYyWwCcmdibwftlSKza7ii40pujyULe4hv6RL9qBMCRRNUz6C07VegJopKgRZ-exLZysaa7sM-X0KtmGn8jbnGlqhCUEw0OFteMaf4vlhusMLm4MasqQabhBRnNzzoqZkoG7RR5VKZTknoV5MeIMZmjrLGERAKzSjtq7c4Q2o8ubLiGxwPumzRZSCJT2oVeHbTdIOWWoV_qjx0M3OGnTJC4JgOv0ZUMIdeSYsc3Rw2s1QgkqNrvgdbZSFS84rymYehAmOFWDTslrYbmLA74kUeF0XbSUkA9lkj-kSfpZQxPXiyy1WW1Ak5e4W0_lhYJryysrjWrZl1N5AI6Zs6nyZ4LhJ2IjqgEhekyaaE7je5MSkEXglppqJGZhcnZBwfq6rQPHG2TbEIoGhoZUF-1Dl_TLxWbbk4jAEqyaS030ke-5a1Ohv3S7VoKSc6MxnGKCEZtjp6_3ay4pqM9DVaTcM_N2Mo4mtVdwBcDiIo76Y85qIqkRnb2AZmGMeEIWrb0VIn83iejXOmpaG3JnNqd5GoKXhbnKd5MQgqD3PP94JqQCYNFpJdPcsUrxPh3otSqJBsIaBFhgnB3yIBxFqc_liWpKRMT8FUrkYBGRnlW5MIuCWhbwp8dpKkJ2HB7Zi3L2sdEwOL01muYhNqDlduGpgVsXFBg0K2YhdPF0aDHhs-80pQ0jwoPIamoKnANLRQ7HZqv7ImrRjwnpnhrkjunbfzc0FIODynAVM7jKKSH7xzMYigezkU2al9I6wgUXk42hq-a7b2mDJojloU7lmX9IcwSspkM7GLQIpIOZmYagHHaXOS8DrM3LDrrFmZKhjgmIn47VlA20TwFydqSlLqomu2ASJ0RBeqE5tkVEWuJxJSASFteXqZ5iuCopYHsyAU4Gu2VLlc06e6vLfzUWnxqohAjpjWLZz9ySRBX3iBM8knjrKkOwsj8ENDsKmJLFk5ecgweKkXywPNBmXyOKKffoBcnhkzmAc4gse4umKrYLnCt7fpZ0tsQBLXPYzeqYPDEmBXuBIblSLJGqxp5xRXTSzbSHPHJkujmpT726jy94yD95PXPqVhBDDY4jypBzI1zsyMe7eSIoSa81lF7cEEEgYQOzZVU5uJgYn6GWj_xWXBnsTBpazZT7OAgEcxusQdVRlzzbr6ghC09UsEeqPSW15JVXSIKBtIoo1rmeIIQu8PPmgPzx-MEjC-nvGXGNF5hfJ-vmLDdKMNdoR-S5BN_OyEuqIiUAd27QGVk`

It does not protect a phone whose owner hooks the app itself, and it does not replace TLS.

## Flow

1. Your **backend** creates the case and asks for the applicant link
   (`POST /api/v1/cases/{id}/collection-flow-url` or `/tokens`). Never mint it in the app.
2. Start the SDK with that link and your pinned key.
3. The SDK runs each step natively; the result is for your UI only — the decision of
   record is the webhook.

Screens drawn natively: consent (with a fresh location fix), details, ID document
choice + front/back photos, NFC chip, the Thai ID dip chip (on a partner terminal), phone OTP, liveness (incl. the screen-flash step),
the wait screens and outcomes. A step the SDK does not draw yet — e-signature, and the KYB
steps (company, key people, document groups, business certificate) — ends the SDK with
`unsupportedStep`; send the applicant to the same link in a browser.

## Usage

```kotlin
// Android
val verify = registerForActivityResult(TrustsyVerifyContract()) { result -> /* TrustsyResult */ }
TrustsyVerify.Session.fromLink(link, sealingKey = BuildConfig.TRUSTSY_SEALING_KEY)?.let(verify::launch)
// or embed: TrustsyVerifyFlow(session, onResult) in Compose, TrustsyVerifyView in a View tree
```

```swift
// iOS
let session = try TrustsyVerifySession(link: link, sealingKey: sealingKey)
RootView().trustsyVerification { result in /* TrustsyVerifyResult */ }   // SwiftUI, once
TrustsyVerify.shared.startVerification(session: session)                // then from anywhere
// UIKit: TrustsyVerify.present(from: self, session: session) { result in … }
// or embed: TrustsyVerifyView(session:onResult:) in SwiftUI, TrustsyVerifyViewController as a child VC
```

```dart
// Flutter
final result = await TrustsyVerify.start(TrustsyVerifySession.fromLink(link, sealingKey: kSealingKey));
// or embed: TrustsyVerifyView(session: session, onResult: (r) { … })
```

```tsx
// React Native
const result = await startVerification({ link, sealingKey: SEALING_KEY });
// or embed: <TrustsyVerifyView session={{ link, sealingKey: SEALING_KEY }} onResult={…} />
```

Results: `completed(outcome)` (approved / rejected / completed / failed / review),
`cancelled`, `unsupportedStep(screen, state)`, `error(code, message)` — codes include
`invalid_link`, `invalid_sealing_key`, `sealing_key_rejected`, `sealed_response_invalid`,
`clock_skew`, `unauthorized`, `network`, `invalid_chip_key`, `terminal_not_authorized`.

`allowInsecure` accepts an `http://` origin for a local dev server only.

## Web SDK (JavaScript, popup)

For websites. The flow is the verify web app itself, opened from your page by
[zoid](https://github.com/krakenjs/zoid) (Apache-2.0) — **in a popup by default**, so the
camera, location and passkeys work with no Permissions-Policy on your page. It is not the
sealed transport above: a browser cannot pin a key, so the web flow uses TLS like `/verify`.

Load the script **from the verify origin your session links point at** — it must be the same
release as the page it opens, and that deployment serves both:

```html
<script src="https://verify.trustsy.com/sdk/trustsy-verify.js"></script>
<script>
  verifyButton.addEventListener('click', () => {
    // `url` comes from YOUR backend: POST /api/v1/identity/verification_sessions → `url`
    const run = TrustsyVerify.start({
      url,
      onReady: () => {},
      onStep: (state) => {},              // the flow's state name, e.g. data_collection.awaiting_consent
      onComplete: (status) => {},         // 'approved' | 'rejected' | 'manual_review' — at most once
      onCancel: () => {},                 // the applicant closed the window first
      onError: (code) => {},              // popup_blocked | load_failed | access_refused | flow_failed | render_failed
    });
    // run.close(), run.focus()
  });
</script>
```

- Call `start` **directly in a click handler** — browsers open popups only in answer to the
  user; otherwise you get `onError('popup_blocked')`.
- Inline instead: `start({ url, context: 'iframe', container: '#kyc' })`. The frame gets
  `allow="camera; microphone; geolocation; publickey-credentials-get; publickey-credentials-create"`.
- ES module: `import { start } from 'https://verify.trustsy.com/sdk/trustsy-verify.mjs'`
  (served with CORS).
- `url` must be the session link as returned (`https://…/u/<code>`, or an older
  `…/verify?token=`); a token or a relative path throws a `TypeError` before any window opens.
- Exactly one of `onComplete` / `onCancel` / `onError` ends a run.
- No applicant data reaches your page — the same `trustsy:*` events as the iframe embed
  (`/embed`). The decision of record is the `identity.verification_session.*` webhook.
- The script is not versioned in its URL and is served `no-cache`, so a Subresource Integrity
  hash would break on the next deploy; pin the file yourself if you need SRI.

## Dip chip: Thai ID cards in a USB reader (partner terminals)

The dip-chip step (`CHIP_READ`) reads a Thai national ID card inserted into a contact
smart card reader — at a branch, a counter, a kiosk. On a desktop use `sdk/desktop`; on
an Android or iOS device plug a USB-C (or Lightning) CCID reader in and give the session a
**terminal**:

```kotlin
TrustsyVerify.Session.fromLink(link, sealingKey = SEALING_KEY,
    terminal = TrustsyVerify.Terminal(terminalId = "BRANCH-042", chipKey = CHIP_KEY, chipKeyId = 1))
```
```swift
let terminal = try TrustsyVerifyTerminal(terminalID: "BRANCH-042", chipKey: chipKey, chipKeyID: 1)
let session = try TrustsyVerifySession(link: link, sealingKey: sealingKey, terminal: terminal)
```
```dart
TrustsyVerifySession.fromLink(link, sealingKey: kSealingKey,
    terminal: const TrustsyTerminal(terminalId: 'BRANCH-042', chipKey: kChipKey, chipKeyId: 1));
```
```tsx
startVerification({ link, sealingKey, terminal: { terminalId: 'BRANCH-042', chipKey: CHIP_KEY, chipKeyId: 1 } });
```

Your backend mints the applicant token **with** that terminal id
(`POST /api/v1/cases/{id}/tokens {"terminal_id": "BRANCH-042"}`, on the API key's
`allowed_terminals`) — the server refuses a read from any other terminal, and a read is
trusted only because the terminal is yours (`sdk/contract/dip-chip.md` "Trust model").
`chipKey` is the server's chip key (`config.chip`), a different key from `sealingKey`; pin
it the same way. Without `terminal` a dip-chip step ends with `unsupportedStep`. Android
asks the user to allow the reader (system dialog) the first time; iOS needs no
entitlement or permission (CryptoTokenKit).

## Push: chat notifications (pairing only — sending is not built yet)

Your app owns push; the SDK only pairs it with the session. Hand the SDK your Firebase
Cloud Messaging registration token whenever you have one — before or during a flow:

```swift
func messaging(_ messaging: Messaging, didReceiveRegistrationToken token: String?) {
    TrustsyVerify.setPushToken(token)
}
```
```kotlin
override fun onNewToken(token: String) = TrustsyVerify.setPushToken(token)  // FirebaseMessagingService
```
```dart
TrustsyVerify.setPushToken(await FirebaseMessaging.instance.getToken());     // Flutter
```
```ts
setPushToken(await messaging().getToken());                                  // React Native
```

While a session is active the SDK registers it (`POST /api/v1/collection-flow/push-token`,
inside the sealed transport) and again whenever it changes; a failed pairing never interrupts
the flow. The server keeps a case's five freshest tokens and erases them on redaction. The
token is an address, not a secret, and a notification will carry no message text.

## App requirements

**Android** — `minSdk 24`. Bouncy Castle (NFC stack) ships one licence file twice:

```kotlin
android { packaging { resources { excludes += setOf("META-INF/LICENSE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") } } }
```

The SDK's manifest adds `NFC`, `CAMERA`, location and `INTERNET`; NFC and the camera are
optional features. Flutter: embedding needs `FlutterFragmentActivity`; the plugin adds the
SDK's repository itself (`trustsyVerifyAndroidRepository`, else mavenLocal).

**iOS** — iOS 17. Capability **Near Field Communication Tag Reading**; `Info.plist`:
`NFCReaderUsageDescription`, `com.apple.developer.nfc.readersession.iso7816.select-identifiers`
= `A0000002471001`, `NSCameraUsageDescription`, `NSLocationWhenInUseUsageDescription`.
Liveness uses ARKit face tracking (TrueDepth); older phones fall back to Vision and cannot
do blink / smile / open-mouth steps.

## Example apps

**trustsy Swift** (`sdk/ios/Example`), **trustsy Flutter**
(`sdk/flutter/trustsy_verify/example`), **trustsy RN** (`sdk/react-native/example`), and
the Android sample (`sdk/android/sample`, takes the link as the `url` extra). Each pins the
dev key.

```
sdk/install-ios-examples.sh <TEAM_ID> [DEVICE_ID]   # builds, signs, installs the three iOS apps
sdk/phone-dev-stack.sh                              # api :8096, verify :5299, console :3100
```

In the console, **New case** opens **Share with applicant** (QR + link); paste the link
into an example app. Android emulator: `emulator -avd <avd> -camera-front webcam0` uses the
Mac's camera, so liveness can run against a real face.

## Building

| | command |
|---|---|
| Android | `cd sdk/android && ./gradlew :trustsy-verify:testDebugUnitTest :trustsy-verify:assembleRelease :sample:assembleDebug` (`:trustsy-verify:publishToMavenLocal` for the wrappers; `publishReleasePublicationToTrustsyRepository` + `./gradlew -p ci/consumer assembleRelease` to distribute — `android/README.md` "Distribute") |
| iOS | `cd sdk/ios && swift test`; `xcodebuild -scheme TrustsyVerify -destination 'generic/platform=iOS'` (`DEVELOPER_DIR=/Applications/Xcode.app/Contents/Developer` on this machine) |
| Flutter | `cd sdk/flutter/trustsy_verify && flutter analyze && flutter test`; example `flutter build apk --debug` / `flutter build ios --no-codesign` |
| React Native | `cd sdk/react-native && npx tsc --noEmit && npx jest`; example `./gradlew :app:assembleDebug`, `pod install` + `xcodebuild` |

Live checks against a running dev api: Android `LiveSealedServerTest` (env-gated),
iOS `TRUSTSY_SEALED_E2E=1 swift test --filter SealedLiveServerTests`.

React Native 0.82 on Xcode 27: RN's own `fmt` pod needs, after `react_native_post_install`:
`installer.pods_project.targets.each { |t| next unless t.name == "fmt"; t.build_configurations.each { |c| c.build_settings["CLANG_CXX_LANGUAGE_STANDARD"] = "c++17" } }`.

## Status — what has and has not run

- Unit tests and live sealed round trips pass on both native SDKs; the Android SDK ran on an
  emulator through consent → ID photos → the liveness screen.
- **Not yet run:** a liveness step against a real face, an NFC chip read, anything on an
  iPhone, the Flutter and React Native wrappers on any device. The turn direction
  (+yaw = the applicant's own left) is derived, not observed — check it first on a phone.

## Licensing — read before shipping

- Android reads chips with **JMRTD (LGPL-3.0)** and **scuba** (LGPL-3.0): legal sign-off before release.
- iOS uses **NFCPassportReader (MIT)**, which pulls **OpenSSL (Apache-2.0)**.
- Android liveness uses **MediaPipe Tasks** (Apache-2.0) and its Face Landmarker model.
- Bouncy Castle is MIT-style.
