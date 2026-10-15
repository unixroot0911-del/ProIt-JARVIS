package com.jarvis.assistant

/** The semantic knowledge base. Filled in by the next build step; this version only answers "nothing relevant". */
object Knowledge {
    fun contextFor(goal: String, project: String): String = ""
}
