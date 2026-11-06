package com.trustsy.verify.internal.ui

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trustsy.verify.internal.flow.Branding

/**
 * The host's own context, before the SDK re-localises it. Activity-bound work
 * (NFC reader mode, window brightness, settings intents) needs this one: the
 * localised context is not an Activity.
 */
internal val LocalHostContext = staticCompositionLocalOf<Context> { error("no host context") }

internal fun Context.findActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

internal fun Context.openAppSettings() {
    runCatching {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

internal fun Context.openUrl(url: String) {
    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** `#RRGGBB` / `#RRGGBBAA` (CSS order: alpha LAST) → Color; null if malformed. */
internal fun parseCssColor(value: String?): Color? {
    val hex = value?.trim()?.removePrefix("#") ?: return null
    if (!hex.matches(Regex("[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))) return null
    val r = hex.substring(0, 2).toInt(16)
    val g = hex.substring(2, 4).toInt(16)
    val b = hex.substring(4, 6).toInt(16)
    val a = if (hex.length == 8) hex.substring(6, 8).toInt(16) else 255
    return Color(r, g, b, a)
}

internal fun colorSchemeFor(branding: Branding?): ColorScheme {
    val colors = branding?.style?.colors
    val primary = parseCssColor(colors?.get("primary_button")) ?: Color(0xFF4F46E5)
    val onPrimary = parseCssColor(colors?.get("primary_button_text")) ?: Color.White
    return lightColorScheme(primary = primary, onPrimary = onPrimary)
}

/** A step: title, optional body, content, then the actions pinned under it. */
@Composable
internal fun StepScaffold(
    title: String?,
    body: String? = null,
    modifier: Modifier = Modifier,
    actions: @Composable ColumnScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Column(modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 16.dp)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            }
            if (body != null) Text(body, style = MaterialTheme.typography.bodyLarge)
            content()
        }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
    }
}

@Composable
internal fun PrimaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true, busy: Boolean = false) {
    Button(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        if (busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
        else Text(text)
    }
}

@Composable
internal fun SecondaryButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(12.dp),
    ) { Text(text) }
}

@Composable
internal fun LinkButton(text: String, onClick: () -> Unit) {
    TextButton(onClick = onClick) { Text(text) }
}

/** An inline problem, announced by screen readers. */
@Composable
internal fun ErrorText(text: String) {
    Text(
        text,
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
internal fun CenteredSpinner(label: String? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator()
            if (label != null) Text(label, textAlign = TextAlign.Center)
        }
    }
}

/** A heading + body status screen with one action. */
@Composable
internal fun StatusScreen(heading: String, body: String?, action: String?, onAction: () -> Unit, spinner: Boolean = false) {
    StepScaffold(
        title = null,
        actions = { if (action != null) SecondaryButton(action, onAction) },
    ) {
        Spacer(Modifier.height(48.dp))
        if (spinner) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(8.dp))
        }
        Text(
            heading,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (body != null) {
            Text(body, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
