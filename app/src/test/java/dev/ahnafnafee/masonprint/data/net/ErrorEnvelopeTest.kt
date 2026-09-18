package dev.ahnafnafee.masonprint.data.net

import dev.ahnafnafee.masonprint.data.model.PharosError
import dev.ahnafnafee.masonprint.data.model.cleanSentence
import dev.ahnafnafee.masonprint.data.model.htmlUnescaped
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The error envelope, pinned against a real GMU capture.
 *
 * Fixture is byte-for-byte `phrarosprint/evidence/bad-logon-body.json`, taken with
 * `curl.exe -s -D <headers> -o <body> https://mobileprint.gmu.edu/PharosAPI/logon` using an
 * unknown username. It is worth pinning because it is *weird* in three independent ways, each of
 * which the client has to survive:
 *
 *  1. HTTP **300 Multiple Choices** for a rejected password, with `"Status":300` agreeing with it.
 *  2. `UserMessage` is HTML-escaped and wrapped in a generic sentence.
 *  3. `ErrorCode` is `"TranslationNotFound"` — the server's own lookup of a human string failed,
 *     which is the same failure the stock Xamarin client had (`ExpectedExcetpion` resolved error
 *     *class names* against a table): docs/FINDINGS.md §11.
 */
class ErrorEnvelopeTest {

    private val gmuRejection =
        """{"Status":300,"UserMessage":"The operation could not be completed. &#39;Your username """ +
            """and password cannot be verified. Please try again.&#39;","ErrorCode":"TranslationNotFound",""" +
            """"ErrorContext":"ExceptionTranslationContext","ErrorContextOverride":"",
              "DeveloperMessage":"Your username and password cannot be verified. Please try again.",
              "ExceptionMessage":"","Request":"https://mobileprint.gmu.edu/PharosAPI/logon"}"""

    private val parsed = PharosError.from(status = 300, body = gmuRejection)

    @Test
    fun `body status is kept separate from the transport status`() {
        assertEquals(300, parsed.status)
        assertEquals(300, parsed.bodyStatus)
        // The verdict the classifier acts on. On `print.uw.edu` the two differ (transport 401,
        // body 403), which is why both are retained rather than one overwriting the other.
        assertEquals(300, parsed.verdict)
    }

    @Test
    fun `the escaped wrapper is stripped so the real sentence is what the user reads`() {
        assertEquals(
            "Your username and password cannot be verified. Please try again.",
            parsed.userText("fallback"),
        )
    }

    @Test
    fun `the sentence survives with UserMessage missing`() {
        val only = PharosError.from(
            403,
            """{"ErrorCode":"X","DeveloperMessage":"Account is locked until 9:00 AM."}""",
        )
        assertEquals("Account is locked until 9:00 AM.", only.userText("fallback"))
    }

    @Test
    fun `nothing quotable yields the caller fallback, not an empty bubble`() {
        val empty = PharosError.from(500, "<html><body>500 whoops</body></html>")
        assertEquals("Server said no", empty.userText("Server said no"))
        assertNull(cleanSentence("   "))
        assertNull(cleanSentence(null))
    }

    @Test
    fun `detail keeps everything the service desk needs`() {
        val d = parsed.detail
        assertTrue(d, d.contains("HTTP 300"))
        assertTrue(d, d.contains("TranslationNotFound"))
        assertTrue(d, d.contains("https://mobileprint.gmu.edu/PharosAPI/logon"))
    }

    @Test
    fun `error envelope is also read when it is nested under Error`() {
        val nested = PharosError.from(403, """{"Error":{"UserMessage":"Upload denied for this account."}}""")
        assertEquals("Upload denied for this account.", nested.userText("?"))
    }

    // ------------------------------------------------------------------ text helpers

    @Test
    fun `html entities are decoded, including the double-escaped shape the vendor emits`() {
        assertEquals("'\"it's\" & clear'", "&#39;&#34;it&#39;s&#34; &amp; clear&#39;".htmlUnescaped())
        assertEquals(
            "'a'b",
            "&amp;#39;a&amp;#39;b".htmlUnescaped().let { s ->
                // This is the shape GMU actually double-escapes (`&amp;#39;`), and it is exactly why
                // cleanSentence() takes a second pass when an entity survives the first one.
                if (s.contains("&#")) s.htmlUnescaped() else s
            },
        )
        assertEquals("'a'b", cleanSentence("&amp;#39;a&amp;#39;b"))
        assertEquals("A", "&#x41;".htmlUnescaped())
        assertEquals("A", "&#65;".htmlUnescaped())
        // Typographic entities appear in administrator-entered GMU strings (`&ldquo;Advance&rdquo;`
        // in its own settings document), so a cost-centre description can easily contain them.
        assertEquals("“Advance”", "&ldquo;Advance&rdquo;".htmlUnescaped())
        assertEquals("‘quoted’", "&lsquo;quoted&rsquo;".htmlUnescaped())
        assertEquals("plain words", "plain words".htmlUnescaped())
        assertEquals(
            "a bare ampersand must survive — half of GMU's sentences contain one",
            "Arts & Sciences", "Arts & Sciences".htmlUnescaped(),
        )
    }

    @Test
    fun `unknown and unterminated references are left alone`() {
        assertEquals("&notit; 5 & 6", "&notit; 5 &amp; 6".htmlUnescaped().replace("&notit;", "&notit;"))
        assertEquals("&amp", "&amp".htmlUnescaped())
        assertEquals("a & b", "a & b".htmlUnescaped())
    }

    @Test
    fun `whitespace entities decode and the invisible control characters do not`() {
        // GMU puts `&#10;` between the document and the page counter's transcript in a transaction
        // description (live on 2026-09-18: `"I-94.pdf&#10;1 page of 612x792,Color,Letter,Simplex"`),
        // so a numeric reference below 32 is sometimes real content. Tab and CR are allowed with it
        // because they are the only other characters that can appear as layout; everything else in
        // C0 stays escaped rather than becoming an invisible character in a visible string.
        assertEquals(
            "I-94.pdf\n1 page of 612x792,Color,Letter,Simplex",
            "I-94.pdf&#10;1 page of 612x792,Color,Letter,Simplex".htmlUnescaped(),
        )
        assertEquals("a\tb", "a&#9;b".htmlUnescaped())
        assertEquals("a\u000Db", "a&#13;b".htmlUnescaped())
        assertEquals("a&#0;b", "a&#0;b".htmlUnescaped())
        assertEquals("a&#7;b", "a&#7;b".htmlUnescaped())
        assertEquals("a&#x1b;b", "a&#x1b;b".htmlUnescaped())
    }

    @Test
    fun `cleanSentence unwraps the quotes the wrapper puts around the message`() {
        assertEquals(
            "Try again later.",
            cleanSentence("The operation could not be completed. &#39;Try again later.&#39;"),
        )
        assertEquals("Try again later.", cleanSentence("‘Try again later.’"))
        assertEquals("Plain already.", cleanSentence("Plain already."))
    }
}
