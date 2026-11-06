package com.trustsy.verify

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.trustsy.verify.internal.flow.Screen
import com.trustsy.verify.internal.dipchip.ChipKey
import com.trustsy.verify.internal.chat.ChatClient
import com.trustsy.verify.internal.chat.ChatOverlay
import com.trustsy.verify.internal.net.ApiClient
import com.trustsy.verify.internal.net.SealedTransport
import com.trustsy.verify.internal.ui.DipChipTerminal
import com.trustsy.verify.internal.ui.FlowController
import com.trustsy.verify.internal.ui.FlowUi
import com.trustsy.verify.internal.ui.LocalHostContext
import com.trustsy.verify.internal.ui.StatusScreen
import com.trustsy.verify.internal.ui.CenteredSpinner
import com.trustsy.verify.internal.ui.colorSchemeFor
import com.trustsy.verify.internal.ui.screens.AccessRefusedScreen
import com.trustsy.verify.internal.ui.screens.StepScreen
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Locale

/**
 * The whole verification flow as one composable, for hosts that render it
 * inside their own UI (the Flutter and React Native wrappers do). It asks for
 * camera, location and NFC itself and calls [onResult] exactly once.
 *
 * It does not need to be the root of the activity, but it must be inside a
 * [androidx.activity.ComponentActivity] (permission requests and NFC reader
 * mode need one).
 */
@Composable
fun TrustsyVerifyFlow(
    session: TrustsyVerify.Session,
    onResult: (TrustsyResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hostContext = LocalContext.current
    val latestOnResult = rememberUpdatedState(onResult)
    val scope = rememberCoroutineScope()

    val keyConfig = session.keyConfig
    val terminal = session.terminal?.let { t -> ChipKey.parse(t.chipKey, t.chipKeyId)?.let { DipChipTerminal(t.terminalId, it) } }
    val refusal = when {
        !session.isUsable -> TrustsyResult.Error(TrustsyResult.INVALID_LINK, "not a usable session")
        keyConfig == null -> TrustsyResult.Error(TrustsyResult.INVALID_SEALING_KEY, "no pinned sealing key is usable")
        session.terminal != null && (terminal == null || session.terminal.terminalId.isBlank()) ->
            TrustsyResult.Error(TrustsyResult.INVALID_CHIP_KEY, "the terminal's chip key or id is not usable")
        else -> null
    }
    if (refusal != null || keyConfig == null) {
        LaunchedEffect(Unit) { latestOnResult.value(refusal ?: return@LaunchedEffect) }
        return
    }

    val transport = remember(session) {
        SealedTransport(
            session.origin, session.token, keyConfig, ApiClient.defaultHttp,
            userAgent = SealedTransport.userAgent(Build.MODEL, Build.VERSION.RELEASE, TrustsyVerify.SDK_VERSION),
        )
    }
    val controller = remember(session) {
        val api = ApiClient(transport, telemetry = ::deviceTelemetry)
        FlowController(api, scope, terminal) { latestOnResult.value(it) }.also { it.load() }
    }
    val chat = remember(session) { ChatClient(transport) }

    val ui = controller.ui
    val snapshot = (ui as? FlowUi.Step)?.snapshot
    val language = resolveLanguage(session.language ?: controller.linkLanguage, hostContext)
    val localized = remember(hostContext, language) { hostContext.localized(language) }

    CompositionLocalProvider(
        LocalHostContext provides hostContext,
        LocalContext provides localized,
        LocalConfiguration provides localized.resources.configuration,
    ) {
        MaterialTheme(colorScheme = colorSchemeFor(snapshot?.branding)) {
            Surface(modifier) {
                BackHandler { controller.leave() }
                Box(Modifier.background(MaterialTheme.colorScheme.background)) {
                    when (ui) {
                        FlowUi.Loading -> CenteredSpinner(stringResource(R.string.trustsy_loading))
                        is FlowUi.LoadFailed -> StatusScreen(
                            heading = stringResource(R.string.trustsy_error),
                            body = null,
                            action = stringResource(R.string.trustsy_retry),
                            onAction = controller::load,
                        )
                        is FlowUi.AccessRefused -> AccessRefusedScreen(ui.error, onRetry = controller::load)
                        is FlowUi.Step -> {
                            PollWhileWaiting(ui.screen, controller)
                            StepScreen(controller, ui.snapshot, ui.screen)
                        }
                    }
                    // The session chat with the reviewing team, over every step.
                    if (ui is FlowUi.Step) ChatOverlay(chat, session.origin)
                }
            }
        }
    }
}

/** Wait screens re-read the snapshot every 4 s while visible (contract §2). */
@Composable
private fun PollWhileWaiting(screen: Screen, controller: FlowController) {
    val waiting = screen == Screen.Review || screen == Screen.Checking || screen == Screen.Blocked
    if (!waiting) return
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(screen, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                delay(POLL_MS)
                controller.refresh()
            }
        }
    }
}

private const val POLL_MS = 4_000L

/** Explicit language, else the link's, else the device's when Thai, else English. */
private fun resolveLanguage(requested: String?, context: Context): String {
    val supported = setOf("en", "th")
    requested?.lowercase()?.takeIf { it in supported }?.let { return it }
    val device = context.resources.configuration.locales[0].language
    return if (device == "th") "th" else "en"
}

/**
 * The host context with only its RESOURCES re-localised. A wrapper, not
 * `createConfigurationContext` alone: Compose finds the activity-result
 * registry, the back dispatcher and the activity itself by walking
 * `LocalContext` up to an Activity, and a configuration context has no
 * Activity under it (that crashed the consent screen's permission request).
 */
private fun Context.localized(language: String): Context {
    val config = Configuration(resources.configuration)
    config.setLocale(Locale.forLanguageTag(language))
    val localizedResources = createConfigurationContext(config).resources
    return object : android.content.ContextWrapper(this) {
        override fun getResources(): android.content.res.Resources = localizedResources
    }
}

private fun deviceTelemetry() = buildJsonObject {
    putJsonObject("device") {
        put("platform", "android")
        put("model", Build.MODEL)
        put("os_version", Build.VERSION.RELEASE)
    }
    put("sdk", "trustsy-android/${TrustsyVerify.SDK_VERSION}")
}
