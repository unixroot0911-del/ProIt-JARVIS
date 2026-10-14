package com.jarvis.assistant

import java.io.File

/** Reads documents of many kinds into plain text so tools and agents can work with them. */
object Docs {
    fun readText(c: ToolCtx, f: File, maxChars: Int, page: Int): String {
        if (FileTools.isText(f)) {
            if (f.length() > 3_000_000L) return f.inputStream().bufferedReader().use { r -> val b = CharArray(maxChars); val n = r.read(b); String(b, 0, maxOf(n, 0)) + "\n[file is ${Workspace.human(f.length())}; only the start is shown]" }
            return f.readText()
        }
        return "[${f.name}: this kind of file (.${f.extension}) cannot be read as text yet]"
    }
}
