> This repository is exported from the trustsy monorepo, where the SDKs are built and
> tested together. `contract/` and `SDK-OVERVIEW.md` are copies shared by every trustsy SDK.

# trustsy Verify — Android (Kotlin, Jetpack Compose)

Runs a trustsy verification flow — the same flow the web verify app runs,
built once in the flow builder — with **native Compose screens**. No WebView.
The server's statechart decides every step; the SDK decides only how a step
looks (`sdk/contract/native-flow.md`).

Every call is **end-to-end sealed** (`sdk/contract/sealed-transport.md`): HPKE
with the post-quantum X-Wing KEM (ML-KEM-768 + X25519) / HKDF-SHA256 / AES-256-GCM
to a server key your app pins, one
`POST /api/v1/sealed` endpoint, the bearer token inside the envelope. A proxy
the phone trusts sees ciphertext only. There is no plaintext mode.

## Integrate

```kotlin
// Your app's build config — PINNED, never fetched at runtime.
// buildConfigField("String", "TRUSTSY_SEALING_KEY", "\"AQAgAAEAA…\"")

private val verify = registerForActivityResult(TrustsyVerifyContract()) { result ->
    when (result) {
        is TrustsyResult.Completed -> /* result.outcome: APPROVED, REJECTED, COMPLETED, FAILED, REVIEW */
        TrustsyResult.Cancelled -> …
        is TrustsyResult.UnsupportedStep -> /* open the web link instead */
        is TrustsyResult.Error -> /* result.code: invalid_link, invalid_sealing_key, … */
    }
}

// link: from your backend (POST /api/v1/cases/{id}/collection-flow-url)
TrustsyVerify.Session.fromLink(link, sealingKey = BuildConfig.TRUSTSY_SEALING_KEY)
    ?.let(verify::launch)
```

Or embed it in your own UI (the Flutter and React Native wrappers do):

```kotlin
TrustsyVerifyFlow(session, onResult = { result -> … }, modifier = Modifier.fillMaxSize())
```

Or as a classic `View`, for hosts that do not compile Compose (the Flutter
platform view and the React Native view manager use this; the host needs no
Compose compiler plugin):

```kotlin
val view = TrustsyVerifyView(activity)
view.start(session) { result -> … }
container.addView(view)
```

Both must be inside a `ComponentActivity` (Flutter: `FlutterFragmentActivity`,
not `FlutterActivity`) — the flow requests camera / location permissions and
turns on NFC reader mode itself — but need not be its root.

The result is for your UI only; the decision of record reaches your backend by
webhook.

## Public API

```kotlin
object TrustsyVerify {
    const val SDK_VERSION: String
    data class Session(
        val apiBaseUrl: String,          // origin; calls go to {apiBaseUrl}/api/v1/sealed
        val token: String,               // applicant token (UUID); only ever inside the envelope
        val sealingKey: String,          // key configs, base64url, comma-separated: X-Wing first, X25519 for iOS builds (sealed-transport.md)
        val language: String? = null,    // "en" | "th"; null = the link's, else the device's
        val allowInsecure: Boolean = false, // accept http:// (local development only)
    ) {
        companion object {
            fun fromLink(link: String, sealingKey: String, language: String? = null, allowInsecure: Boolean = false): Session?
        }
    }
}

class TrustsyVerifyContract : ActivityResultContract<TrustsyVerify.Session, TrustsyResult>

@Composable fun TrustsyVerifyFlow(session: TrustsyVerify.Session, onResult: (TrustsyResult) -> Unit, modifier: Modifier = Modifier)

class TrustsyVerifyView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) : AbstractComposeView {
    fun start(session: TrustsyVerify.Session, onResult: (TrustsyResult) -> Unit)  // onResult once per start, main thread
}

sealed interface TrustsyResult {
    data class Completed(val outcome: Outcome) : TrustsyResult
    data object Cancelled : TrustsyResult
    data class UnsupportedStep(val screen: String, val state: String?) : TrustsyResult
    data class Error(val code: String, val message: String) : TrustsyResult
    enum class Outcome(val wire: String) { APPROVED, REJECTED, COMPLETED, FAILED, REVIEW }
    // codes: INVALID_LINK, INVALID_SEALING_KEY, SEALING_KEY_REJECTED, SEALED_RESPONSE_INVALID,
    //        CLOCK_SKEW, UNAUTHORIZED, NETWORK
}
```

`fromLink` accepts `https://…/u/<22-char code>` (the code must round-trip) and
`https://…/verify?token=<uuid>`; `/resume/…` handoff links are refused. The
sealing key is checked when the flow starts (`invalid_sealing_key`).

## App requirements

- `minSdk 24`, `compileSdk 36`. The SDK's manifest adds `INTERNET`, `CAMERA`,
  location and `NFC`; camera and NFC are optional features.
- Bouncy Castle (used by the NFC stack and the sealed transport) ships one
  license file twice. Every app needs:

  ```kotlin
  android { packaging { resources { excludes += setOf("META-INF/LICENSE.md", "META-INF/versions/9/OSGI-INF/MANIFEST.MF") } } }
  ```
- The SDK bundles MediaPipe's native libraries (~10 MB per ABI); split by ABI
  (`bundle` / `splits`) if size matters.

## What runs natively (v1)

consent (fresh high-accuracy location, required) · details · document choice →
guide → back-camera capture (cropped to the on-screen guide, JPEG q92, long edge
≤ 2048, `id-` file names) · document back · phone OTP · NFC chip (JMRTD:
PACE then BAC, SOD/DG1/DG2/DG14/DG15, Active Authentication over the server's
challenge) · liveness · plain action buttons · review / checking / blocked
(polling every 4 s) · terminal. `signature`, `company`, `keyPeople`,
`awaitingUsers`, `documentGroups`, `businessDocument` end the run with
`UnsupportedStep`.

### Liveness

MediaPipe Face Landmarker `0.10.35` — the same runtime version the web app pins —
with the web app's own `face_landmarker.task` (Google `face_landmarker/float16/1`,
Apache-2.0; copied from `frontend/verify/public/models` at build time and
checked against its sha256). Frames are mapped exactly as the web maps them:
`eye_open = 1 − mean(eyeBlink*)`, `smile = mean(mouthSmile*)`,
`mouth_open = jawOpen`, `face_size` = landmark box diagonal, head pose from the
facial transformation matrix (`eulerFromMatrix`, yaw negated: positive = the
applicant's own left).

1. A positioning gate (one face, centred, large enough, |yaw|/|pitch|/|roll| < 12°,
   stable 1 s) runs BEFORE the session starts — each step expires 20 s after issue.
2. Each server-issued step is judged on the device by a line-for-line port of the
   server's `challenge_satisfied`, run over exactly the frames that will be
   submitted (every frame since the previous accepted step) AND over the frames
   since the applicant returned to neutral — so returning from a right turn is
   never counted as a left turn.
3. `flash`: full brightness, the server's colours, a separate MP4 clip that starts
   with the first colour; declined when the OS animator scale is 0.
4. The run is recorded to MP4 (CameraX `Recorder`, SD, ~1 Mbps). A device that
   cannot bind preview + analysis + video degrades to no video (optional on the wire).
5. The selfie is the analysis frame on which the gate passed.

## Build and test

```
cd sdk/android
./gradlew :trustsy-verify:testDebugUnitTest :trustsy-verify:assembleRelease :sample:assembleDebug
./gradlew :trustsy-verify:publishToMavenLocal   # wrappers built on this machine
```

## Distribute

The SDK is not on a public repository. It publishes a self-contained Maven
repository — static files to host anywhere (`com.trustsy:trustsy-verify`:
AAR, sources, POM, Gradle module metadata, checksums):

```
./gradlew :trustsy-verify:publishReleasePublicationToTrustsyRepository   # → build/repository
./gradlew … -PtrustsyRepositoryDir=/path/to/repo                       # elsewhere
```

An app then adds it in `settings.gradle.kts` and depends on it:

```kotlin
dependencyResolutionManagement {
    repositories {
        google(); mavenCentral()
        maven { url = uri("https://<where you host it>"); content { includeGroup("com.trustsy") } }
    }
}
dependencies { implementation("com.trustsy:trustsy-verify:0.2.0") }
```

Every other dependency comes from Google and Maven Central.

**Check the published artifact before hosting it.** `ci/consumer` is a separate
build that sees ONLY that repository (plus Google / Maven Central — no
mavenLocal, no project dependency):

```
./gradlew -p ci/consumer assembleDebug assembleRelease                  # Gradle module metadata
./gradlew -p ci/consumer clean assembleRelease -PtrustsyPomOnly         # the POM alone (Maven, non-Gradle tools)
```

Its app applies no Compose compiler, like the Flutter and React Native wrappers,
so Compose UI and `androidx.activity` must reach its compile classpath through
the POM; its release build runs R8 with nothing but the SDK's consumer rules.
`ANDROID_HOME` must be set (it has no `local.properties`). Mutation probe:
`.claudecode/test/2026-10-15-android-distribution-mutation-probe.sh` (4/4: an
`api` dependency demoted, two dependencies dropped from the POM, nothing
published).

The unit tests replay `sdk/contract/screen-vectors.json` and
`sdk/contract/sealed-vectors.json` in place. `LiveSealedServerTest` checks the
transport against a real api when `TRUSTSY_LIVE_API`, `TRUSTSY_LIVE_TOKEN` and
`TRUSTSY_LIVE_KEY` are set (skipped otherwise).

Sample app (dev api; the debug build pins the dev key and allows cleartext):

```
adb shell am start -n com.trustsy.verify.sample/.MainActivity \
  --es url "http://10.0.2.2:8096/verify?token=<token>" --ez insecure true [--ez embed true | --ez view true]
```

## Licensing — read before shipping

JMRTD and scuba are **LGPL-3.0**: shipping them inside an app has obligations
(relinking, notices); get a legal sign-off. Bouncy Castle is MIT-style;
MediaPipe and the face model are Apache-2.0.
