/* Added for AEmulator Plus, 2026-11-16: unit tests for the HTTP debugger's rule engine (plain JVM, no Android). GPL-3.0; see LICENSE and NOTICE.md. */
package app.aemu.netdebug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

 
class RulesTest {
    private fun headers(vararg pairs: Pair<String, String>): Headers = Headers().also {
        for ((k, v) in pairs) it.add(k, v)
    }

    private fun req(
        scheme: String = "https", host: String = "www.example.com", port: Int = 443,
        method: String = "GET", path: String = "/", query: String? = null,
        headers: Headers = Headers(), body: String? = null,
    ): ReqView = ReqView(scheme, host, port, method, path, query, headers, body?.toByteArray(Charsets.UTF_8))

    @Test fun globsAndRegexes() {
        assertTrue(Glob.match("*.example.com", "WWW.Example.COM", true))
        assertFalse(Glob.match("*.example.com", "WWW.Example.COM", false))
        assertTrue(Glob.match("a?c", "abc", false))
        assertFalse(Glob.match("a?c", "abbc", false))
        assertTrue(Glob.match("*", "", false))
        assertTrue(Glob.matchOrRegex("re:/api/.*", "/api/v1", false))
        assertFalse(Glob.matchOrRegex("re:/api/", "/api/v1", false))  
        assertTrue(Glob.matchOrRegex("re:.*ANDROID.*", "Mozilla Android 10", true))
        assertFalse(Glob.matchOrRegex("re:ANDROID", "Mozilla Android 10", true))
    }

    @Test fun allMatchFieldsAreChecked() {
        val rs = RuleSet.parse("""{"rules":[{"id":"r","match":{"scheme":"https","host":"*.example.com|*.test.org","port":8443,
            "method":"GET|POST","path":"re:/api/.*","query":{"q":"*phone*"},"headers":{"User-Agent":"*Android*"},
            "body":"re:Email=.*"},"action":{"type":"mock","status":200,"body":"ok"}}]}""")
        val h = headers("user-agent" to "Mozilla/5.0 (Linux; Android 10)")
        val r = req(host="shop.example.com", port=8443, path="/api/v1", query="q=my%20phone&x=1", headers=h,
            body="Email=a@b.c&pass=secret")
        assertNotNull(rs.match(r))
        assertNotNull(rs.match(r.copyForTest(host="api.test.org")))
        assertNull(rs.match(r.copyForTest(host="shop.other.com")))
        assertNull(rs.match(r.copyForTest(port=443)))
        assertNull(rs.match(r.copyForTest(method="DELETE")))
        assertNull(rs.match(r.copyForTest(path="/other/v1")))
        assertNull(rs.match(r.copyForTest(query="q=laptop")))
        assertNull(rs.match(r.copyForTest(headers=headers("User-Agent" to "desktop browser"))))
        assertNull(rs.match(r.copyForTest(body="name=nobody")))
        assertEquals("r", rs.match(r)?.id)
        assertTrue(rs.needsRequestBody)
    }

     
    private fun ReqView.copyForTest(
        scheme: String = this.scheme, host: String = this.host, port: Int = this.port,
        method: String = this.method, path: String = this.path, query: String? = this.rawQuery,
        headers: Headers = this.headers, body: String? = this.bodyText,
    ): ReqView = ReqView(scheme, host, port, method, path, query, headers, body?.toByteArray(Charsets.UTF_8))

    @Test fun plainBodyIsContainsAndDisabledRulesAreSkipped() {
        val rs = RuleSet.parse("""{"rules":[
            {"id":"off","enabled":false,"match":{"body":"secret"},"action":{"type":"block"}},
            {"id":"on","match":{"body":"contains:secret"},"action":{"type":"block"}}]}""")
        assertNull(RuleSet.parse("""{"rules":[{"id":"off","enabled":false,"match":{"body":"secret"},"action":{"type":"block"}}]}""").match(req(body="secret")))
        assertNotNull(rs.match(req(body="the secret is here"))!!)
        assertEquals("on", rs.match(req(body="the secret is here"))?.id)
        assertNull(rs.match(req(body="nothing here")))
    }

    @Test fun firstMatchingRuleWins() {
        val rs = RuleSet.parse("""{"rules":[
            {"id":"first","match":{"host":"*"},"action":{"type":"passthrough"}},
            {"id":"second","match":{"host":"*"},"action":{"type":"mock","status":418}}]}""")
        assertEquals("first", rs.match(req())?.id)
        assertEquals(2, rs.rules.size)
    }

    @Test fun dnsAndTlsOverridesUseGlobs() {
        val rs = RuleSet.parse("""{"dns":[{"host":"*.gone.example","answer":"NXDOMAIN"},
            {"host":"api.dead.example","answer":"203.0.113.5"}],
            "tlsPassthrough":["*.pinned.example.com"]}""")
        assertEquals("NXDOMAIN", rs.dnsAnswer("sub.gone.example"))
        assertEquals("203.0.113.5", rs.dnsAnswer("API.DEAD.EXAMPLE"))
        assertNull(rs.dnsAnswer("www.example.com"))
        assertTrue(rs.passesTls("a.pinned.example.com"))
        assertFalse(rs.passesTls("pinned.example.com.evil.test"))
    }

    @Test fun templatesExpandRequestAndGenerateValues() {
        val r = req(host="www.example.com", path="/p", query="q=coffee", headers=headers("X-Token" to "abc"))
        val src = "${'$'}{host}|${'$'}{path}|${'$'}{query.q}|${'$'}{header.X-Token}|${'$'}{uuid}|${'$'}{random.hex:8}|${'$'}{nope}"
        val parts = Template.expand(src, r).split('|')
        assertEquals("www.example.com", parts[0])
        assertEquals("/p", parts[1])
        assertEquals("coffee", parts[2])
        assertEquals("abc", parts[3])
        assertTrue(parts[4].matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")))
        assertEquals(8, parts[5].length)
        assertTrue(parts[5].all { it in "0123456789abcdef" })
        assertEquals("${'$'}{nope}", parts[6])  
    }

    @Test fun headersAreCaseInsensitiveAndQueriesAreDecoded() {
        val h = headers("Content-Type" to "text/plain", "X-Test" to "one", "X-Test" to "two")
        assertEquals("one", h["x-test"])
        assertEquals(listOf("one", "two"), h.getAll("X-TEST"))
        h.set("x-test", "three")
        assertEquals(listOf("three"), h.getAll("X-Test"))
        h.remove("X-TEST")
        assertNull(h["x-test"])
        assertEquals(listOf("a" to "1", "b" to "hello world"), Http.parseQuery("a=1&b=hello+world"))
    }

    @Test fun jsonRoundTripKeepsRulesAndDns() {
        val rs = RuleSet.parse("""{"rules":[{"id":"gen204","match":{"path":"*generate_204"},"action":{"type":"mock","status":204}}],
            "dns":[{"host":"*.gone.example","answer":"NXDOMAIN"}],"tlsPassthrough":["*.pinned.example.com"]}""")
        val again = RuleSet.parse(Json.stringify(rs.toJson()))
        assertEquals(rs.rules.size, again.rules.size)
        assertEquals("gen204", again.rules[0].id)
        assertEquals("NXDOMAIN", again.dnsAnswer("x.gone.example"))
        assertTrue(again.passesTls("x.pinned.example.com"))
    }

    @Test fun everyPresetParsesAndDoesWhatItSays() {
        for (p in Presets.all) {
            val rs = RuleSet.parse(p.json)
            assertTrue("preset ${p.id} is empty", rs.rules.isNotEmpty() || rs.dns.isNotEmpty())
        }
        val ads = RuleSet.parse(Presets.all.first { it.id == "ads" }.json)
        assertEquals("NXDOMAIN", ads.dnsAnswer("pagead2.googlesyndication.com"))
        assertNotNull(ads.match(req(host="pagead2.googlesyndication.com", path="/ads")))
        assertNull(ads.match(req(host="www.example.com")))
        val offline = RuleSet.parse(Presets.all.first { it.id == "offline" }.json)
        val blocked = offline.match(req(host="anything.example"))!!
        assertTrue(blocked.action is Action.Block)
    }
}
