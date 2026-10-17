package com.jarvis.assistant

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * AXIOM's eyes on its own work. A page it just wrote is loaded in a hidden browser, actually run (clicks, keys, scrolling),
 * measured (overflow, empty sections, tiny text, console errors, animation alive) and photographed. A reviewer model looks at
 * the photos and the measurements and says what is wrong, so the page is fixed before the user ever sees it.
 */
object Preview {
    class Report(val errors: List<String>, val metrics: JSONObject, val shots: List<String>, val shotFiles: List<File>) {
        val hasShots get() = shots.isNotEmpty()
    }

    private const val HOOK = "<script>window.__err=[];window.onerror=function(m,s,l){__err.push(String(m)+' (line '+l+')');};" +
        "window.addEventListener('unhandledrejection',function(e){__err.push('promise: '+(e.reason&&e.reason.message||e.reason));});" +
        "var __ce=console.error;console.error=function(){__err.push('console.error: '+[].slice.call(arguments).join(' '));__ce.apply(console,arguments)};</script>"

    private const val AUDIT = """(function(){
var r={};var de=document.documentElement;
r.title=document.title;r.lang=de.lang;r.dir=de.dir||document.dir||'';
r.width=window.innerWidth;r.height=de.scrollHeight;r.overflowX=de.scrollWidth>window.innerWidth+2;
var wide=[];document.querySelectorAll('body *').forEach(function(e){var b=e.getBoundingClientRect();if(b.right>window.innerWidth+2&&b.width>0&&wide.length<5)wide.push(e.tagName+'.'+(e.className||'').toString().slice(0,30));});r.wideElements=wide;
r.sections=document.querySelectorAll('section,article,main,header,footer,nav').length;
r.headings=document.querySelectorAll('h1,h2,h3').length;r.h1=document.querySelectorAll('h1').length;
r.links=document.querySelectorAll('a').length;r.deadLinks=document.querySelectorAll('a[href="#"],a:not([href])').length;
r.buttons=document.querySelectorAll('button,input[type=submit]').length;r.forms=document.querySelectorAll('form').length;
r.images=document.querySelectorAll('img').length;r.brokenImages=Array.prototype.filter.call(document.querySelectorAll('img'),function(i){return !i.complete||i.naturalWidth===0}).length;
r.textLength=(document.body.innerText||'').trim().length;
var small=0;document.querySelectorAll('p,li,span,a,button,label').forEach(function(e){var f=parseFloat(getComputedStyle(e).fontSize);if(f&&f<11&&(e.innerText||'').trim())small++;});r.tinyText=small;
var tiny=0;document.querySelectorAll('button,a,input,select').forEach(function(e){var b=e.getBoundingClientRect();if(b.width>0&&(b.height<30||b.width<30))tiny++;});r.smallTapTargets=tiny;
var lorem=(document.body.innerText||'').toLowerCase();r.placeholderText=/lorem ipsum|your text here|todo|coming soon/.test(lorem);
var cs=document.querySelectorAll('canvas');r.canvases=cs.length;
r.errors=window.__err||[];
return JSON.stringify(r);})()"""

    private const val CANVAS_SIG = """(function(){var c=document.querySelector('canvas');if(!c)return '';try{var x=c.getContext('2d');if(!x)return 'webgl';var d=x.getImageData(0,0,c.width,c.height).data;var h=0;for(var i=0;i<d.length;i+=97){h=(h*31+d[i])|0}return String(h)+':'+(d.length>0?1:0)}catch(e){return 'err '+e.message}})()"""

    private const val POKE = """(function(){var keys=['ArrowRight','ArrowUp','Space',' ','Enter','a','d','w'];keys.forEach(function(k){['keydown','keyup'].forEach(function(t){try{document.dispatchEvent(new KeyboardEvent(t,{key:k,code:k==='Space'?'Space':k,bubbles:true}))}catch(e){}})});
var el=document.elementFromPoint(window.innerWidth/2,window.innerHeight/2);if(el){['pointerdown','mousedown','touchstart','pointerup','mouseup','click'].forEach(function(t){try{el.dispatchEvent(new MouseEvent(t,{bubbles:true,clientX:window.innerWidth/2,clientY:window.innerHeight/2}))}catch(e){}})}
var b=document.querySelector('button,[role=button],.btn,.start,#start,#play');if(b){try{b.click()}catch(e){}}return 'poked'})()"""

    private fun unq(s: String?): String {
        if (s == null || s == "null") return ""
        return try { JSONObject("{\"v\":$s}").getString("v") } catch (e: Exception) { s.trim('"') }
    }

    private fun blank(b: Bitmap): Boolean {
        val c = b.getPixel(2, 2); var same = 0; var n = 0
        val sx = (b.width / 20).coerceAtLeast(1); val sy = (b.height / 20).coerceAtLeast(1)
        var x = 0; while (x < b.width) { var y = 0; while (y < b.height) { n++; if (b.getPixel(x, y) == c) same++; y += sy }; x += sx }
        return same * 100 / n > 99
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun inspect(html: String, saveTo: File?): Report? = withContext(Dispatchers.Main) {
        val dm = Axiom.ctx.resources.displayMetrics
        val wpx = (412 * dm.density).toInt(); val hpx = (860 * dm.density).toInt()
        val errs = ArrayList<String>()
        val loaded = CompletableDeferred<Unit>()
        var web: WebView? = null
        try {
            val w = WebView(Axiom.ctx); web = w
            w.settings.javaScriptEnabled = true; w.settings.domStorageEnabled = true
            w.settings.allowFileAccess = false; w.settings.allowContentAccess = false
            w.settings.mediaPlaybackRequiresUserGesture = false
            w.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                    if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR && errs.size < 8) errs.add("console: " + Axiom.clip(m.message(), 160) + " (line ${m.lineNumber()})")
                    return true
                }
            }
            w.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) { loaded.complete(Unit) }
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val u = request?.url?.toString() ?: return null
                    if (u.startsWith("https://axiom.local/")) return null
                    if (u.startsWith("data:") || u.startsWith("blob:")) return null
                    synchronized(errs) { if (errs.size < 8) errs.add("tries to load an outside resource: " + Axiom.clip(u, 80)) }
                    return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                }
            }
            w.measure(android.view.View.MeasureSpec.makeMeasureSpec(wpx, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(hpx, android.view.View.MeasureSpec.EXACTLY))
            w.layout(0, 0, wpx, hpx)
            val hooked = Regex("(?i)<head[^>]*>").find(html)?.let { html.substring(0, it.range.last + 1) + HOOK + html.substring(it.range.last + 1) } ?: (HOOK + html)
            w.loadDataWithBaseURL("https://axiom.local/preview/", hooked, "text/html", "utf-8", null)
            if (withTimeoutOrNull(15_000) { loaded.await() } == null) return@withContext Report(listOf("the page did not finish loading in 15 seconds"), JSONObject(), emptyList(), emptyList())
            suspend fun js(code: String): String { val d = CompletableDeferred<String>(); w.evaluateJavascript(code) { d.complete(unq(it)) }; return withTimeoutOrNull(8000) { d.await() } ?: "" }
            delay(1500)
            val sig1 = js(CANVAS_SIG)
            js(POKE); delay(1200)
            val sig2 = js(CANVAS_SIG)
            val metrics = try { JSONObject(js(AUDIT)) } catch (e: Exception) { JSONObject() }
            if (sig1.isNotEmpty()) metrics.put("canvasChangedAfterInput", sig1 != sig2).put("canvasState", if (sig1.startsWith("err")) sig1 else "readable")
            val pageErrs = metrics.optJSONArray("errors")
            if (pageErrs != null) for (i in 0 until pageErrs.length()) if (errs.size < 12) errs.add("runtime: " + Axiom.clip(pageErrs.optString(i), 160))

            // photographs: top, middle and bottom of the page (or the one screen of a game)
            val total = metrics.optInt("height", hpx).coerceIn(hpx, hpx * 8)
            val stops = if (total <= hpx * 1.2) listOf(0) else listOf(0, ((total - hpx) / 2.0 / dm.density).toInt(), ((total - hpx) / dm.density).toInt())
            val shots = ArrayList<String>(); val files = ArrayList<File>()
            for ((i, y) in stops.withIndex()) {
                js("window.scrollTo(0,$y)"); delay(500)
                val bmp = Bitmap.createBitmap(wpx, hpx, Bitmap.Config.ARGB_8888)
                w.draw(Canvas(bmp))
                if (!blank(bmp)) {
                    val small = Bitmap.createScaledBitmap(bmp, wpx * 2 / 3, hpx * 2 / 3, true)
                    val bo = ByteArrayOutputStream(); small.compress(Bitmap.CompressFormat.JPEG, 72, bo)
                    shots.add(Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP))
                    if (saveTo != null) try { val f = File(saveTo.parentFile, saveTo.nameWithoutExtension + ".preview${i + 1}.jpg"); f.writeBytes(bo.toByteArray()); files.add(f) } catch (e: Exception) { /* optional */ }
                    small.recycle()
                }
                bmp.recycle()
            }
            Report(errs.distinct(), metrics, shots, files)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { null }
        finally { try { web?.destroy() } catch (e: Exception) { /* gone */ } }
    }

    class Verdict(val score: Int, val issues: List<String>, val pass: Boolean)

    /** The reviewer: looks at the photos and the measurements and judges the work against the request, harshly and specifically. */
    suspend fun review(brain: Brain, request: String, html: String, rep: Report): Verdict {
        val game = rep.metrics.optInt("canvases") > 0 && rep.metrics.optInt("textLength") < 600
        val sys = "You are a strict senior QA engineer and art director reviewing work before it is given to a demanding client. " +
            "You get the client's request, measurements from really running the page, a list of runtime errors, and screenshots (top, middle, bottom of the page) when available. " +
            "Judge against what was ASKED, not against a generic page. Look for: missing sections or features the request implies; anything visibly broken, overlapping, cut off, unreadable or ugly; " +
            "poor spacing, hierarchy, contrast or colour harmony; text overflowing; empty or placeholder content; controls that do nothing; (games) nothing happening on the canvas or no response to input; " +
            "right-to-left or language problems; every runtime error. Be concrete: each issue says WHAT is wrong, WHERE, and the FIX. " +
            "Score 0-10 (10 = ships as is; 8 = good, minor polish only; below 8 = must be fixed). " +
            "Return ONE JSON object: {\"score\":n,\"issues\":[\"WHAT, WHERE, FIX\", ...at most 8, most important first]}."
        val user = "REQUEST:\n" + Axiom.clip(request, 1200) + "\n\nKIND: " + (if (game) "game/canvas app" else "website/app") +
            "\nMEASUREMENTS:\n" + rep.metrics.also { it.remove("errors") }.toString() +
            "\nRUNTIME ERRORS (" + rep.errors.size + "):\n" + (if (rep.errors.isEmpty()) "none" else rep.errors.joinToString("\n")) +
            "\nSCREENSHOTS: " + (if (rep.hasShots) "${rep.shots.size} attached (first one shown; others described by the measurements)" else "none could be captured; judge from the measurements and this code excerpt:\n" + Axiom.clip(html, 6000))
        val j = try {
            Router.runJson(brain, Router.T.VERIFY, Router.Opts(sys, user, image = rep.shots.firstOrNull(), json = true, maxOut = 1500, task = "review"))
        } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { return Verdict(if (rep.errors.isEmpty()) 8 else 4, rep.errors, rep.errors.isEmpty()) }
        val issues = ArrayList<String>(); val a = j.optJSONArray("issues")
        if (a != null) for (i in 0 until a.length()) issues.add(a.optString(i))
        issues.addAll(0, rep.errors.map { "Runtime error: $it" })
        val score = if (rep.errors.isNotEmpty()) minOf(j.optInt("score", 6), 6) else j.optInt("score", 6)
        return Verdict(score, issues.distinct().take(10), score >= 8 && rep.errors.isEmpty())
    }
}
