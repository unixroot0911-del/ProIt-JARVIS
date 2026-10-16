package com.jarvis.assistant

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** GitHub through its official API with the user's own token (kept encrypted). Writing needs approval. */
object Github {
    private const val API = "https://api.github.com"

    private fun call(p: Prefs, method: String, path: String, body: JSONObject? = null): Pair<Int, String> {
        if (p.githubToken.isEmpty()) throw java.io.IOException("No GitHub token is set. Add a personal access token in AXIOM settings (repo scope for private repositories).")
        val r = Net.get(API + path, 25000, 1_500_000, mapOf("Authorization" to "Bearer ${p.githubToken}", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28", "Content-Type" to "application/json"), method, body?.toString()?.toByteArray())
        return r.status to r.body
    }

    private fun check(r: Pair<Int, String>, what: String): String {
        if (r.first in 200..299) return r.second
        val msg = try { JSONObject(r.second).optString("message") } catch (e: Exception) { "" }
        throw java.io.IOException("GitHub $what failed (HTTP ${r.first}${if (msg.isNotBlank()) ": $msg" else ""})" + (if (r.first == 401) ". The token is wrong or expired." else if (r.first == 404) ". Check the name, and that the token can see it." else ""))
    }

    private fun repoOk(s: String) = Regex("^[\\w.-]+/[\\w.-]+$").matches(s.trim())

    fun register() {
        Tools.register(Tool("gh_repos", "List the user's GitHub repositories (most recently pushed first).", listOf(A("filter", "name contains", false)), Risk.READ, 60_000L, group = "github",
            run = { c, a ->
                val arr = withContext(Dispatchers.IO) { JSONArray(check(call(c.prefs, "GET", "/user/repos?per_page=60&sort=pushed"), "repository list")) }
                val f = a.optString("filter", "").lowercase()
                val l = (0 until arr.length()).map { arr.getJSONObject(it) }.filter { f.isEmpty() || it.optString("full_name").lowercase().contains(f) }
                ok(l.joinToString("\n") { "${it.optString("full_name")}${if (it.optBoolean("private")) " (private)" else ""} — ${Axiom.clip(it.optString("description", ""), 70)} [pushed ${it.optString("pushed_at").take(10)}]" }.ifEmpty { "No repositories." }, untrusted = true)
            }))
        Tools.register(Tool("gh_read", "Read a file or list a folder in a GitHub repository.", listOf(A("repo", "owner/name"), A("path", "file or folder path, empty for the root", false), A("ref", "branch or tag", false)), Risk.READ, 60_000L, group = "github",
            run = { c, a ->
                if (!repoOk(a.getString("repo"))) return@Tool fail("repo must look like owner/name.")
                val txt = withContext(Dispatchers.IO) { check(call(c.prefs, "GET", "/repos/${a.getString("repo")}/contents/${a.optString("path", "").trim('/')}" + (if (a.optString("ref", "").isNotBlank()) "?ref=${a.getString("ref")}" else "")), "read") }
                if (txt.trim().startsWith("[")) { val arr = JSONArray(txt); return@Tool ok((0 until arr.length()).joinToString("\n") { val o = arr.getJSONObject(it); "${o.optString("type")} ${o.optString("path")}" + (if (o.optString("type") == "file") " (${o.optLong("size")} B)" else "") }, untrusted = true) }
                val o = JSONObject(txt)
                if (o.optLong("size") > 1_000_000L) return@Tool fail("That file is larger than 1 MB.")
                val content = String(Base64.decode(o.optString("content"), Base64.DEFAULT), Charsets.UTF_8)
                ok(Shield.wrap("github ${a.getString("repo")}/${o.optString("path")}", content, 20000).text, untrusted = true)
            }))
        Tools.register(Tool("gh_search_code", "Search code on GitHub, optionally inside one repository.", listOf(A("query", "what to find"), A("repo", "owner/name", false)), Risk.READ, 60_000L, group = "github",
            run = { c, a ->
                val q = java.net.URLEncoder.encode(a.getString("query") + (if (a.optString("repo", "").isNotBlank()) " repo:${a.getString("repo")}" else " user:@me"), "UTF-8")
                val o = JSONObject(withContext(Dispatchers.IO) { check(call(c.prefs, "GET", "/search/code?per_page=15&q=$q"), "code search") })
                val items = o.optJSONArray("items") ?: JSONArray()
                ok((0 until items.length()).joinToString("\n") { val i = items.getJSONObject(it); "${i.getJSONObject("repository").optString("full_name")}: ${i.optString("path")}" }.ifEmpty { "No matches." }, untrusted = true)
            }))
        Tools.register(Tool("gh_issues", "List issues (or pull requests with kind=pulls) of a repository.", listOf(A("repo", "owner/name"), A("state", "open or closed, default open", false), A("kind", "issues or pulls", false)), Risk.READ, 60_000L, group = "github",
            run = { c, a ->
                if (!repoOk(a.getString("repo"))) return@Tool fail("repo must look like owner/name.")
                val arr = JSONArray(withContext(Dispatchers.IO) { check(call(c.prefs, "GET", "/repos/${a.getString("repo")}/${if (a.optString("kind") == "pulls") "pulls" else "issues"}?state=${a.optString("state", "open")}&per_page=25"), "list") })
                ok(Shield.wrap("github issues", (0 until arr.length()).joinToString("\n") { val o = arr.getJSONObject(it); "#${o.optInt("number")} ${o.optString("title")} (${o.optJSONObject("user")?.optString("login")}, ${o.optString("created_at").take(10)})" }.ifEmpty { "None." }, 8000).text, untrusted = true)
            }))
        Tools.register(Tool("gh_runs", "Status of the latest GitHub Actions runs of a repository (builds, tests).", listOf(A("repo", "owner/name")), Risk.READ, 60_000L, group = "github",
            run = { c, a ->
                if (!repoOk(a.getString("repo"))) return@Tool fail("repo must look like owner/name.")
                val o = JSONObject(withContext(Dispatchers.IO) { check(call(c.prefs, "GET", "/repos/${a.getString("repo")}/actions/runs?per_page=8"), "runs") })
                val arr = o.optJSONArray("workflow_runs") ?: JSONArray()
                ok((0 until arr.length()).joinToString("\n") { val r = arr.getJSONObject(it); "${r.optString("created_at").take(16)} ${r.optString("name")}: ${r.optString("status")}/${r.optString("conclusion", "-")} — ${Axiom.clip(r.optString("display_title"), 60)}" }.ifEmpty { "No runs." }, untrusted = true)
            }))
        Tools.register(Tool("gh_issue_create", "Open an issue in a repository.", listOf(A("repo", "owner/name"), A("title", "title"), A("body", "text", false)), Risk.EXTERNAL, 60_000L, group = "github",
            run = { c, a ->
                if (!repoOk(a.getString("repo"))) return@Tool fail("repo must look like owner/name.")
                val o = JSONObject(withContext(Dispatchers.IO) { check(call(c.prefs, "POST", "/repos/${a.getString("repo")}/issues", JSONObject().put("title", a.getString("title")).put("body", a.optString("body", ""))), "issue creation") })
                ok("Issue #${o.optInt("number")} created: ${o.optString("html_url")}")
            }))
        Tools.register(Tool("gh_put_file", "Create or update a file in a repository with a commit (text content, or a project file via from_path).",
            listOf(A("repo", "owner/name"), A("path", "path in the repository"), A("message", "commit message"), A("content", "the file text", false), A("from_path", "project file to upload instead of content", false), A("branch", "branch, default the repository's default", false)), Risk.EXTERNAL, 90_000L, group = "github",
            run = { c, a ->
                if (!repoOk(a.getString("repo"))) return@Tool fail("repo must look like owner/name.")
                val bytes = if (a.optString("from_path", "").isNotBlank()) (Workspace.readable(a.getString("from_path"), c.project)?.takeIf { it.isFile }?.readBytes() ?: return@Tool fail("File not found: ${a.getString("from_path")}")) else a.optString("content", "").toByteArray(Charsets.UTF_8)
                if (bytes.isEmpty()) return@Tool fail("There is nothing to upload (give content or from_path).")
                if (bytes.size > 20_000_000) return@Tool fail("The file is too large for this route.")
                val path = a.getString("path").trim('/'); val br = a.optString("branch", "")
                val sha = withContext(Dispatchers.IO) { val r = call(c.prefs, "GET", "/repos/${a.getString("repo")}/contents/$path" + (if (br.isNotBlank()) "?ref=$br" else "")); if (r.first == 200) JSONObject(r.second).optString("sha") else null }
                val body = JSONObject().put("message", a.getString("message")).put("content", Base64.encodeToString(bytes, Base64.NO_WRAP)); if (sha != null) body.put("sha", sha); if (br.isNotBlank()) body.put("branch", br)
                val o = JSONObject(withContext(Dispatchers.IO) { check(call(c.prefs, "PUT", "/repos/${a.getString("repo")}/contents/$path", body), "commit") })
                ok((if (sha != null) "Updated " else "Created ") + path + " in ${a.getString("repo")}: " + o.optJSONObject("commit")?.optString("html_url"))
            }))
    }
}
