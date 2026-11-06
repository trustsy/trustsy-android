# Files shared with the trustsy web verify app

- `public/models/face_landmarker.task` — Google MediaPipe Face Landmarker (`face_landmarker/float16/1`),
  Apache License 2.0. Pinned by SHA-256 in `trustsy-verify/build.gradle.kts`: the server's
  liveness thresholds were set against these exact weights.
- `src/flow/dialCodes.json` — the country dial-code registry the web app uses.
