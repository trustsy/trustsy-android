package com.trustsy.verify.sample

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.trustsy.verify.TrustsyResult
import com.trustsy.verify.TrustsyVerify
import com.trustsy.verify.TrustsyVerifyContract
import com.trustsy.verify.TrustsyVerifyFlow
import com.trustsy.verify.TrustsyVerifyView

/**
 * The whole integration: get the applicant link from YOUR backend (it called
 * POST /api/v1/cases/{id}/collection-flow-url), launch, read the result.
 *
 * Here the link arrives as the `url` extra, so a test can drive it:
 *   adb shell am start -n com.trustsy.verify.sample/.MainActivity --es url <link>
 * `--ez insecure true` accepts http:// for a dev server; `--ez embed true`
 * hosts [TrustsyVerifyFlow] inside this activity instead of launching the
 * SDK's own; `--ez view true` hosts it as a classic [TrustsyVerifyView]
 * (what the Flutter and React Native wrappers do — no Compose in the host).
 */
class MainActivity : ComponentActivity() {
    private var label by mutableStateOf("Pass an applicant link as the `url` extra.")
    private var embedded by mutableStateOf<TrustsyVerify.Session?>(null)

    private val verify = registerForActivityResult(TrustsyVerifyContract()) { show(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                val session = embedded
                if (session != null) {
                    TrustsyVerifyFlow(
                        session = session,
                        onResult = { embedded = null; show(it) },
                        modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                    )
                } else {
                    Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp)) { Text(label) }
                }
            }
        }
        val url = intent.getStringExtra("url") ?: return
        val session = TrustsyVerify.Session.fromLink(
            url,
            sealingKey = BuildConfig.TRUSTSY_SEALING_KEY,
            allowInsecure = intent.getBooleanExtra("insecure", false),
        )
        if (session == null) {
            show(TrustsyResult.Error(TrustsyResult.INVALID_LINK, "not an applicant link"))
            return
        }
        if (savedInstanceState != null) return
        when {
            intent.getBooleanExtra("view", false) ->
                setContentView(TrustsyVerifyView(this).apply { start(session) { show(it) } })
            intent.getBooleanExtra("embed", false) -> embedded = session
            else -> verify.launch(session)
        }
    }

    private fun show(result: TrustsyResult) {
        Log.i(TAG, "result=$result")
        label = "Result: $result"
    }

    private companion object {
        const val TAG = "TrustsyVerifySample"
    }
}
