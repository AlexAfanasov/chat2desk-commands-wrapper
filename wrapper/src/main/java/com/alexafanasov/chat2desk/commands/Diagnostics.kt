package com.alexafanasov.chat2desk.commands

internal fun String?.presenceAndLength(label: String): String {
    val value = this.orEmpty()
    return label + "Present=${value.isNotBlank()}, ${label}Length=${value.length}"
}

internal fun String?.maskedPhone(): String {
    val digits = this.orEmpty().filter(Char::isDigit)
    return when {
        digits.isBlank() -> "-"
        digits.length <= PHONE_VISIBLE_DIGITS -> PHONE_MASK
        else -> "$PHONE_MASK${digits.takeLast(PHONE_VISIBLE_DIGITS)}"
    }
}

internal fun Chat2DeskCommandsConfig.logDiagnostic(message: String) {
    if (!diagnosticsEnabled) return
    diagnosticsHandler?.invoke(message)
}

private const val PHONE_VISIBLE_DIGITS = 4
private const val PHONE_MASK = "***"
