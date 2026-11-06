package com.trustsy.verify.consumer

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import com.trustsy.verify.TrustsyResult
import com.trustsy.verify.TrustsyVerify
import com.trustsy.verify.TrustsyVerifyContract
import com.trustsy.verify.TrustsyVerifyView

/**
 * Every public entry point a host without Compose uses, reached from the
 * launcher activity so R8 keeps and processes the SDK code behind them
 * (unreachable code would be stripped before R8 ever looked at it).
 *
 * `adb shell am start -n com.trustsy.verify.consumer/.SmokeActivity
 *   --es link "<applicant link>" --es key "<sealing key>" [--ez view true]`
 */
class SmokeActivity : ComponentActivity() {
    private val verify = registerForActivityResult(TrustsyVerifyContract(), ::report)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "trustsy-verify ${TrustsyVerify.SDK_VERSION}")
        val session = TrustsyVerify.Session.fromLink(
            link = intent.getStringExtra("link").orEmpty(),
            sealingKey = intent.getStringExtra("key").orEmpty(),
            allowInsecure = intent.getBooleanExtra("insecure", false),
        )
        when {
            session == null -> report(TrustsyResult.Error(TrustsyResult.INVALID_LINK, "no usable link"))
            intent.getBooleanExtra("view", false) ->
                setContentView(TrustsyVerifyView(this).apply { start(session, ::report) })
            else -> verify.launch(session)
        }
    }

    private fun report(result: TrustsyResult) {
        // Exhaustive: a result variant added to the SDK breaks this build.
        val line = when (result) {
            is TrustsyResult.Completed -> "completed ${result.outcome.wire}"
            TrustsyResult.Cancelled -> "cancelled"
            is TrustsyResult.UnsupportedStep -> "unsupported ${result.screen}"
            is TrustsyResult.Error -> "error ${result.code}"
        }
        Log.i(TAG, line)
    }

    private companion object {
        const val TAG = "TrustsyConsumer"
    }
}
