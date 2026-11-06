package com.trustsy.verify.internal.net

import kotlinx.coroutines.flow.MutableStateFlow

/** The token `TrustsyVerify.setPushToken` was given, read by every active flow. */
internal object PushPairing {
    val token = MutableStateFlow<String?>(null)
}
