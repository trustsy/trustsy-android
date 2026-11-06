package com.trustsy.verify

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract

/**
 * Runs the flow in the SDK's own activity and returns how it ended.
 *
 * ```kotlin
 * private val verify = registerForActivityResult(TrustsyVerifyContract()) { result -> … }
 * ```
 */
class TrustsyVerifyContract : ActivityResultContract<TrustsyVerify.Session, TrustsyResult>() {

    override fun createIntent(context: Context, input: TrustsyVerify.Session): Intent =
        Intent(context, TrustsyVerifyActivity::class.java)
            .putExtra(EXTRA_API_BASE_URL, input.apiBaseUrl)
            .putExtra(EXTRA_TOKEN, input.token)
            .putExtra(EXTRA_SEALING_KEY, input.sealingKey)
            .putExtra(EXTRA_LANGUAGE, input.language)
            .putExtra(EXTRA_ALLOW_INSECURE, input.allowInsecure)
            .apply {
                input.terminal?.let {
                    putExtra(EXTRA_TERMINAL_ID, it.terminalId)
                    putExtra(EXTRA_CHIP_KEY, it.chipKey)
                    putExtra(EXTRA_CHIP_KEY_ID, it.chipKeyId)
                }
            }

    override fun parseResult(resultCode: Int, intent: Intent?): TrustsyResult =
        if (resultCode == Activity.RESULT_OK) TrustsyResult.fromIntent(intent) else TrustsyResult.Cancelled

    internal companion object {
        const val EXTRA_API_BASE_URL = "com.trustsy.verify.API_BASE_URL"
        const val EXTRA_TOKEN = "com.trustsy.verify.TOKEN"
        const val EXTRA_SEALING_KEY = "com.trustsy.verify.SEALING_KEY"
        const val EXTRA_LANGUAGE = "com.trustsy.verify.LANGUAGE"
        const val EXTRA_ALLOW_INSECURE = "com.trustsy.verify.ALLOW_INSECURE"
        const val EXTRA_TERMINAL_ID = "com.trustsy.verify.TERMINAL_ID"
        const val EXTRA_CHIP_KEY = "com.trustsy.verify.CHIP_KEY"
        const val EXTRA_CHIP_KEY_ID = "com.trustsy.verify.CHIP_KEY_ID"

        fun sessionFrom(intent: Intent): TrustsyVerify.Session? {
            val base = intent.getStringExtra(EXTRA_API_BASE_URL) ?: return null
            val token = intent.getStringExtra(EXTRA_TOKEN) ?: return null
            return TrustsyVerify.Session(
                apiBaseUrl = base,
                token = token,
                sealingKey = intent.getStringExtra(EXTRA_SEALING_KEY).orEmpty(),
                language = intent.getStringExtra(EXTRA_LANGUAGE),
                allowInsecure = intent.getBooleanExtra(EXTRA_ALLOW_INSECURE, false),
                terminal = intent.getStringExtra(EXTRA_TERMINAL_ID)?.let { id ->
                    TrustsyVerify.Terminal(
                        terminalId = id,
                        chipKey = intent.getStringExtra(EXTRA_CHIP_KEY).orEmpty(),
                        chipKeyId = intent.getIntExtra(EXTRA_CHIP_KEY_ID, -1),
                    )
                },
            )
        }
    }
}
