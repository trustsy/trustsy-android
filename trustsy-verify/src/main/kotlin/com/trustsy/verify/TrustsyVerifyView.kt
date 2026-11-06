package com.trustsy.verify

import android.content.Context
import android.util.AttributeSet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.AbstractComposeView

/**
 * [TrustsyVerifyFlow] as a classic Android [android.view.View], for hosts that
 * do not compile Compose themselves (the Flutter platform view, the React
 * Native view manager). The Compose compiler runs when THIS library is built;
 * the host only needs the runtime, which comes in as a dependency.
 *
 * ```kotlin
 * val view = TrustsyVerifyView(activity)
 * view.start(session) { result -> … }
 * container.addView(view)
 * ```
 *
 * The view must be attached inside a `ComponentActivity` (a
 * `FlutterFragmentActivity`, not a plain `FlutterActivity`): the flow requests
 * camera / location permissions and turns on NFC reader mode itself.
 * `onResult` is called exactly once per [start], on the main thread.
 */
class TrustsyVerifyView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AbstractComposeView(context, attrs, defStyleAttr) {

    private var session by mutableStateOf<TrustsyVerify.Session?>(null)
    private var onResult: ((TrustsyResult) -> Unit)? = null

    /** Begin (or restart, with a new session) the flow. */
    fun start(session: TrustsyVerify.Session, onResult: (TrustsyResult) -> Unit) {
        this.onResult = onResult
        this.session = session
    }

    @Composable
    override fun Content() {
        val current = session ?: return
        TrustsyVerifyFlow(current, onResult = { result -> onResult?.invoke(result) })
    }
}
