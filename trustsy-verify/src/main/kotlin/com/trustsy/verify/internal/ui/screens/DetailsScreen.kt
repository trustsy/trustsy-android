package com.trustsy.verify.internal.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import com.trustsy.verify.R
import com.trustsy.verify.internal.flow.Flow
import com.trustsy.verify.internal.flow.Phone
import com.trustsy.verify.internal.ui.ErrorText
import com.trustsy.verify.internal.ui.FlowController
import com.trustsy.verify.internal.ui.LocalHostContext
import com.trustsy.verify.internal.ui.PrimaryButton
import com.trustsy.verify.internal.ui.StepScaffold
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Self-asserted attributes (web `DetailsForm.tsx`). The field list mirrors the
 * server's allowlist (`collection_flow::ATTRIBUTE_FIELDS`); only non-empty
 * fields are sent, since the server refuses an empty payload.
 */
@Composable
internal fun DetailsScreen(controller: FlowController) {
    val context = LocalHostContext.current
    val values = remember { mutableStateMapOf<String, String>() }
    val countries = remember(context) { Countries.all(context) }
    var phoneCountry by remember {
        mutableStateOf(countries.firstOrNull { it.code == Phone.DEFAULT_COUNTRY } ?: countries.first())
    }
    var phoneNational by remember { mutableStateOf("") }

    fun filled(): Map<String, String> {
        val out = values.filterValues { it.isNotBlank() }.mapValues { it.value.trim() }.toMutableMap()
        Phone.toE164(phoneCountry.dial, phoneNational).takeIf { it.isNotEmpty() }?.let { out["phone"] = it }
        return out
    }

    StepScaffold(
        title = null,
        actions = {
            controller.actionError?.let { ErrorText(stringResource(R.string.trustsy_error)) }
            PrimaryButton(
                stringResource(R.string.trustsy_continue),
                onClick = { controller.event(Flow.DETAILS_EVENT, JsonObject(filled().mapValues { JsonPrimitive(it.value) })) },
                enabled = filled().isNotEmpty(),
                busy = controller.busy,
            )
        },
    ) {
        TextField(
            stringResource(R.string.trustsy_details_first_name), values["firstName"].orEmpty(),
            { values["firstName"] = it.take(128) }, stringResource(R.string.trustsy_details_placeholder_first_name),
        )
        TextField(
            stringResource(R.string.trustsy_details_last_name), values["lastName"].orEmpty(),
            { values["lastName"] = it.take(128) }, stringResource(R.string.trustsy_details_placeholder_last_name),
        )
        DateField(
            stringResource(R.string.trustsy_details_date_of_birth), values["dateOfBirth"].orEmpty(),
            stringResource(R.string.trustsy_details_placeholder_date_of_birth),
        ) { values["dateOfBirth"] = it }
        CountryField(
            stringResource(R.string.trustsy_details_nationality), values["nationality"].orEmpty(),
            stringResource(R.string.trustsy_details_placeholder_nationality),
        ) { values["nationality"] = it }
        PhoneField(
            label = stringResource(R.string.trustsy_details_phone),
            country = phoneCountry,
            national = phoneNational,
            placeholder = stringResource(R.string.trustsy_details_placeholder_phone),
            onCountry = { phoneCountry = it },
            onNational = { phoneNational = it.take(32) },
        )
        TextField(
            stringResource(R.string.trustsy_details_email), values["email"].orEmpty(),
            { values["email"] = it.take(254) }, stringResource(R.string.trustsy_details_placeholder_email),
            keyboard = KeyboardType.Email,
        )
    }
}
