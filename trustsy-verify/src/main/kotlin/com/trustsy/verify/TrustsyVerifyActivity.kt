package com.trustsy.verify

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.ui.Modifier

/**
 * Hosts [TrustsyVerifyFlow] for [TrustsyVerifyContract]. Everything the flow
 * needs (permissions, NFC, camera) it asks for itself; this activity only
 * returns the result.
 */
class TrustsyVerifyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val session = TrustsyVerifyContract.sessionFrom(intent)
        if (session == null) {
            finishWith(TrustsyResult.Error(TrustsyResult.INVALID_LINK, "no session in the intent"))
            return
        }
        setContent {
            TrustsyVerifyFlow(
                session = session,
                onResult = ::finishWith,
                modifier = Modifier.fillMaxSize().safeDrawingPadding(),
            )
        }
    }

    private fun finishWith(result: TrustsyResult) {
        setResult(Activity.RESULT_OK, TrustsyResult.toIntent(result))
        finish()
    }
}
