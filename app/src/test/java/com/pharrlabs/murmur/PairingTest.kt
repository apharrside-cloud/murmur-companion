package com.pharrlabs.murmur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class PairingTest {
    private fun code(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
    private val good = """{"v":1,"r":"https://box.example.ts.net:8445/","t":"abc123","m":"https://box.example.ts.net:8444","n":"Atlas"}"""

    @Test fun parsesTheBareCode() {
        val p = Pairing.parse(code(good))!!
        assertEquals("https://box.example.ts.net:8445", p.relay)
        assertEquals("abc123", p.token)
        assertEquals("https://box.example.ts.net:8444", p.murmur)
        assertEquals("Atlas", p.name)
    }

    @Test fun parsesALinkAndWrappedText() {
        assertEquals("abc123", Pairing.parse("murmurcompanion://pair?c=" + code(good))!!.token)
        assertEquals("abc123", Pairing.parse("  " + code(good).chunked(40).joinToString("\n") + "  ")!!.token)
    }

    @Test fun refusesWhatIsNotACode() {
        assertNull(Pairing.parse(""))
        assertNull(Pairing.parse("hello world this is not a code"))
        assertNull(Pairing.parse(code("""{"v":2,"r":"https://a.b","t":"x","m":"https://a.b"}""")))
        assertNull(Pairing.parse(code("""{"v":1,"r":"http://a.b","t":"x","m":"https://a.b"}""")))   // plain http
        assertNull(Pairing.parse(code("""{"v":1,"r":"https://a.b","t":"","m":"https://a.b"}""")))   // no token
        assertNull(Pairing.parse(code("not json at all but long enough to pass the length check")))
    }

    @Test fun nameDefaultsToHermes() {
        assertEquals("Hermes", Pairing.parse(code("""{"v":1,"r":"https://a.example.net","t":"tok","m":"https://b.example.net"}"""))!!.name)
    }
}
