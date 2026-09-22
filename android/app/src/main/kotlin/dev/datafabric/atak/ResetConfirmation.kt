package dev.arachne.atak

internal const val RESET_CONFIRMATION = "RESET"

internal fun resetConfirmationAccepted(value: CharSequence?): Boolean =
    value?.toString()?.trim() == RESET_CONFIRMATION
