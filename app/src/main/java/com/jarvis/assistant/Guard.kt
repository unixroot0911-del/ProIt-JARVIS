package com.jarvis.assistant

/**
 * Safety layer. Full autonomy applies to everything EXCEPT the categories below,
 * which always require the user's explicit confirmation (payments, deletions).
 */
object Guard {
    private val PROTECTED = setOf("pay", "transfer", "purchase", "delete_file", "delete_account", "uninstall")

    fun needsConfirmation(actionType: String): Boolean = actionType.lowercase() in PROTECTED
}
