package com.v2ray.ang.dto

data class ConfigResult(
    var status: Boolean,
    var guid: String? = null,
    var content: String = "",
    var errorMessage: String = "",
    /** True when [errorMessage] is a localized resource string meant for the screen. */
    var localizedError: Boolean = false,
)
