package com.trustsy.verify.internal.ui.screens

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trustsy.verify.R
import com.trustsy.verify.internal.flow.Country
import com.trustsy.verify.internal.flow.Phone
import com.trustsy.verify.internal.ui.LocalHostContext
import java.text.Collator
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** The dial-code registry, the web app's own file (copied at build time). */
internal object Countries {
    @Volatile private var cached: List<Country>? = null

    fun all(context: Context): List<Country> = cached ?: synchronized(this) {
        cached ?: context.assets.open("trustsy/dial_codes.json").bufferedReader().use { Phone.parseRegistry(it.readText()) }
            .also { cached = it }
    }

    fun name(country: Country, locale: Locale): String =
        runCatching { Locale.Builder().setRegion(country.code).build().getDisplayCountry(locale) }.getOrNull()
            ?.takeIf { it.isNotBlank() && it != country.code } ?: country.english
}

@Composable
internal fun TextField(label: String, value: String, onChange: (String) -> Unit, placeholder: String = "", keyboard: KeyboardType = KeyboardType.Text, enabled: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        enabled = enabled,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A read-only field that opens a picker. */
@Composable
private fun PickerField(label: String, value: String, placeholder: String, onOpen: () -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        enabled = false,
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
    )
}

/** ISO `YYYY-MM-DD`, chosen on a calendar. Dates are UTC midnights; no time zone shifts the day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateField(label: String, iso: String, placeholder: String, onChange: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    PickerField(label, iso, placeholder) { open = true }
    if (!open) return
    val state = rememberDatePickerState(initialSelectedDateMillis = isoToMillis(iso))
    DatePickerDialog(
        onDismissRequest = { open = false },
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onChange(millisToIso(it)) }
                open = false
            }) { Text(stringResource(R.string.trustsy_continue)) }
        },
        dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.trustsy_cancel)) } },
    ) { DatePicker(state = state) }
}

private fun utc(): Calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))

internal fun millisToIso(millis: Long): String {
    val c = utc().apply { timeInMillis = millis }
    return "%04d-%02d-%02d".format(Locale.ROOT, c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
}

private fun isoToMillis(iso: String): Long? {
    val m = Regex("^(\\d{4})-(\\d{2})-(\\d{2})$").matchEntire(iso) ?: return null
    val (y, mo, d) = m.destructured
    return utc().apply { clear(); set(y.toInt(), mo.toInt() - 1, d.toInt()) }.timeInMillis
}

/** An ISO alpha-2 country, from the registry, named in the UI language. */
@Composable
internal fun CountryField(label: String, code: String, placeholder: String, onChange: (String) -> Unit) {
    val context = LocalHostContext.current
    val locale = LocalConfiguration.current.locales[0]
    val countries = remember(context) { Countries.all(context) }
    var open by remember { mutableStateOf(false) }
    val current = countries.firstOrNull { it.code == code }
    PickerField(label, current?.let { Countries.name(it, locale) }.orEmpty(), placeholder) { open = true }
    if (open) {
        CountryPicker(countries, showDial = false, onDismiss = { open = false }) {
            onChange(it.code)
            open = false
        }
    }
}

/** Country dial code + national number → E.164 (web `PhoneInput`). */
@Composable
internal fun PhoneField(label: String, country: Country, national: String, placeholder: String, onCountry: (Country) -> Unit, onNational: (String) -> Unit, enabled: Boolean = true) {
    val context = LocalHostContext.current
    val locale = LocalConfiguration.current.locales[0]
    val countries = remember(context) { Countries.all(context) }
    var open by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = country.dial,
            onValueChange = {},
            readOnly = true,
            enabled = false,
            label = { Text(stringResource(R.string.trustsy_phone_country_title)) },
            singleLine = true,
            modifier = Modifier.width(112.dp).clickable(enabled = enabled) { open = true },
        )
        OutlinedTextField(
            value = national,
            onValueChange = onNational,
            label = { Text(label) },
            placeholder = { Text(placeholder) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            supportingText = { Text(stringResource(R.string.trustsy_phone_country, Countries.name(country, locale), country.dial)) },
            modifier = Modifier.weight(1f),
        )
    }
    if (open) {
        CountryPicker(countries, showDial = true, onDismiss = { open = false }) {
            onCountry(it)
            open = false
        }
    }
}

@Composable
private fun CountryPicker(countries: List<Country>, showDial: Boolean, onDismiss: () -> Unit, onPick: (Country) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    var query by remember { mutableStateOf("") }
    val sorted = remember(countries, locale) {
        val collator = Collator.getInstance(locale)
        countries.map { it to Countries.name(it, locale) }.sortedWith { a, b -> collator.compare(a.second, b.second) }
    }
    val q = query.trim().lowercase(locale)
    val digits = q.removePrefix("+")
    val shown = if (q.isEmpty()) sorted else sorted.filter { (c, name) ->
        name.lowercase(locale).contains(q) || c.english.lowercase().contains(q) || c.code.lowercase() == q ||
            (digits.isNotEmpty() && digits.all(Char::isDigit) && c.dial.removePrefix("+").startsWith(digits))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.trustsy_close)) } },
        title = { Text(stringResource(R.string.trustsy_phone_country_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(stringResource(R.string.trustsy_country_search), query, { query = it })
                if (shown.isEmpty()) Text(stringResource(R.string.trustsy_country_no_match))
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(shown, key = { it.first.code }) { (c, name) ->
                        Text(
                            if (showDial) "$name  ${c.dial}" else name,
                            modifier = Modifier.fillMaxWidth().clickable { onPick(c) }.padding(vertical = 12.dp),
                        )
                    }
                }
            }
        },
    )
}
