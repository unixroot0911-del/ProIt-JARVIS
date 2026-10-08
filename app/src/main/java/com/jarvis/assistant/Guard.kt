package com.jarvis.assistant

/**
 * Safety layer. Full autonomy applies to everything EXCEPT payments and deletions,
 * which always need the user's explicit yes, whether Jarvis was asked by voice, text, Telegram or its own agent.
 */
object Guard {
    private val PROTECTED_ACTIONS = setOf("pay", "transfer", "purchase", "delete_file", "delete_account", "uninstall")

    private val RISKY_WORDS = listOf(
        "pay", "buy now", "purchase", "place order", "confirm order", "checkout", "check out",
        "send money", "transfer", "delete", "remove account", "uninstall", "factory reset", "erase",
        "ادفع", "دفع", "شراء", "اشتر", "حذف", "احذف", "تحويل", "حوّل", "مسح", "إلغاء الحساب"
    )

    fun needsConfirmation(actionType: String): Boolean = actionType.lowercase() in PROTECTED_ACTIONS

    /** Used by the screen agent: any control whose label looks like a payment or deletion needs a yes. */
    fun isRiskyText(label: String): Boolean {
        val s = label.lowercase()
        return RISKY_WORDS.any { s.contains(it) }
    }
}
