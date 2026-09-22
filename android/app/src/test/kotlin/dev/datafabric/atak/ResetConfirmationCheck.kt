package dev.arachne.atak

fun main() {
    check(!resetConfirmationAccepted(null))
    check(!resetConfirmationAccepted(""))
    check(!resetConfirmationAccepted("reset"))
    check(!resetConfirmationAccepted("RESET NOW"))
    check(resetConfirmationAccepted(" RESET "))
    println("ResetConfirmationCheck: 5 checks passed")
}
