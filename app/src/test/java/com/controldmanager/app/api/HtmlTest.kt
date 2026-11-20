package com.controldmanager.app.api

import org.junit.Assert.assertEquals
import org.junit.Test

class HtmlTest {
    @Test fun namedEntities() {
        assertEquals("Kids & Teens", "Kids &amp; Teens".unescapeHtml())
        assertEquals("Bob's \"Special\" <Test>", "Bob&#39;s &quot;Special&quot; &lt;Test&gt;".unescapeHtml())
        assertEquals("Alice's – Rule", "Alice&apos;s &ndash; Rule".unescapeHtml())
    }

    @Test fun numericEntities() {
        assertEquals("A & B", "A &#38; B".unescapeHtml())
        assertEquals("A & B", "A &#x26; B".unescapeHtml())
        assertEquals("Fire 🔥", "Fire &#128293;".unescapeHtml())
    }

    @Test fun decodesOnceOnly() = assertEquals("&lt;b&gt;", "&amp;lt;b&amp;gt;".unescapeHtml())

    @Test fun leavesOtherTextAlone() {
        assertEquals("Hagezi – Amazon Tracker", "Hagezi – Amazon Tracker".unescapeHtml())
        assertEquals("R&D &unknown; &#0;", "R&D &unknown; &#0;".unescapeHtml())
    }
}
