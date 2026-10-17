package com.jarvis.assistant

/** Registers every tool group once. New groups are added here as they are built. */
object Registry {
    fun registerAll() {
        CoreTools.register()
        DocTools.register()
        Web.register()
        Research.register()
        Watches.register()
        DataTools.register()
        Inbox.register()
        Workflows.register()
        UserTools.register()
        Mail.register()
        Github.register()
        Media.register()
        Content.register()
        Ops.register()
        Mcp.register()
        Connectors.register()
        Skills.register()
        Mods.register()
    }
}
