package com.example.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubReleaseParserTest {

    @Test
    fun `reads the tag from the url the latest release redirect lands on`() {
        assertEquals(
            "v1.0.27",
            GitHubReleaseParser.tagFromRedirectUrl(
                "https://github.com/Asadusjaman-Sazal/swanirvor23/releases/tag/v1.0.27"
            )
        )
    }

    @Test
    fun `ignores the query string and fragment on the release url`() {
        assertEquals(
            "v1.0.27",
            GitHubReleaseParser.tagFromRedirectUrl(
                "https://github.com/o/r/releases/tag/v1.0.27?expanded=true#assets"
            )
        )
    }

    @Test
    fun `returns null when the repository has no published release`() {
        // Comment: GitHub sends a repository without releases to the releases index, not a tag page.
        assertNull(GitHubReleaseParser.tagFromRedirectUrl("https://github.com/o/r/releases"))
    }

    @Test
    fun `returns null for a value that is not a release url`() {
        assertNull(GitHubReleaseParser.tagFromRedirectUrl(""))
        assertNull(GitHubReleaseParser.tagFromRedirectUrl("not a url"))
    }

    @Test
    fun `reads the notes of the requested tag as plain text`() {
        val feed = feedWith(
            entry(
                tag = "v1.0.27",
                releaseName = "Swanirvor-23 v1.0.27",
                escapedBody = "&lt;p&gt;&lt;strong&gt;Full Changelog&lt;/strong&gt;: " +
                    "&lt;a href=&quot;x&quot;&gt;&lt;tt&gt;v1.0.26...v1.0.27&lt;/tt&gt;&lt;/a&gt;&lt;/p&gt;"
            )
        )

        assertEquals(
            "Full Changelog : v1.0.26...v1.0.27",
            GitHubReleaseParser.notesFromAtomFeed(feed, "v1.0.27")
        )
    }

    @Test
    fun `matches the tag from the entry id rather than the release name`() {
        // Comment: The entry <title> is the release name ("Swanirvor-23 v1.0.26"), so a parser that
        // matched on the title would fail to find the release.
        val feed = feedWith(entry("v1.0.26", "Swanirvor-23 v1.0.26", "&lt;p&gt;Older&lt;/p&gt;"))

        assertEquals("Older", GitHubReleaseParser.notesFromAtomFeed(feed, "v1.0.26"))
    }

    @Test
    fun `picks the right entry out of several releases`() {
        val feed = feedWith(
            entry("v1.0.27", "Swanirvor-23 v1.0.27", "&lt;p&gt;New&lt;/p&gt;"),
            entry("v1.0.26", "Swanirvor-23 v1.0.26", "&lt;p&gt;Old&lt;/p&gt;")
        )

        assertEquals("New", GitHubReleaseParser.notesFromAtomFeed(feed, "v1.0.27"))
        assertEquals("Old", GitHubReleaseParser.notesFromAtomFeed(feed, "v1.0.26"))
    }

    @Test
    fun `returns empty notes for a tag that is not in the feed`() {
        val feed = feedWith(entry("v1.0.26", "Swanirvor-23 v1.0.26", "&lt;p&gt;Old&lt;/p&gt;"))

        assertEquals("", GitHubReleaseParser.notesFromAtomFeed(feed, "v1.0.27"))
    }

    @Test
    fun `returns empty notes for the placeholder body GitHub writes`() {
        // Comment: GitHub publishes "<content>No content.</content>" for a release without notes, and
        // that placeholder must not be shown to the user as if it were a changelog.
        val feed = feedWith(entry("v1.0.26", "Swanirvor-23 v1.0.26", "No content."))

        assertEquals("", GitHubReleaseParser.notesFromAtomFeed(feed, "v1.0.26"))
    }

    @Test
    fun `unescapes the entities the release body itself uses`() {
        val feed = feedWith(entry("v1.0.28", "Swanirvor-23 v1.0.28", "&lt;p&gt;Report &amp;amp; Export&lt;/p&gt;"))

        assertEquals("Report & Export", GitHubReleaseParser.notesFromAtomFeed(feed, "v1.0.28"))
    }

    // Comment: Mirrors the entry shape of https://github.com/<owner>/<repo>/releases.atom
    private fun entry(tag: String, releaseName: String, escapedBody: String): String = """
        <entry>
          <id>tag:github.com,2008:Repository/1290595422/$tag</id>
          <link rel="alternate" type="text/html" href="https://github.com/o/r/releases/tag/$tag"/>
          <title>$releaseName</title>
          <content type="html">$escapedBody</content>
        </entry>
    """.trimIndent()

    private fun feedWith(vararg entries: String): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <feed xmlns="http://www.w3.org/2005/Atom">
        ${entries.joinToString("\n")}
        </feed>
    """.trimIndent()
}
