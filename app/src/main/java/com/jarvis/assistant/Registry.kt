package com.jarvis.assistant

/** Registers every tool group once. New groups are added here as they are built. */
object Registry {
    fun registerAll() {
        CoreTools.register()
        DocTools.register()
        Web.register()
        Research.register()
        Watches.register()
    }
}
