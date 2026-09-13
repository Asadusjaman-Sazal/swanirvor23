package com.example.data

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Parses the repository's public release pages into the pieces the update check needs.
 *
 * The REST API is not usable here: a token bundled in the APK would be extractable by anyone, and
 * anonymous API calls are limited to 60 per hour per source IP, which carrier NAT shares between many
 * devices — that surfaced as "Update check failed (HTTP 403)". The "/releases/latest" redirect and
 * the "/releases.atom" feed carry no such quota.
 *
 * These helpers work on plain URLs and strings, so they stay unit-testable on the JVM.
 */
object GitHubReleaseParser {

    // Comment: A release page URL ends in .../releases/tag/{tag}; the releases index (reached when the
    // repository has no published release) and any other page end differently.
    private const val TAG_PATH_SEGMENT = "tag"

    // Comment: GitHub writes this placeholder body for a release published without notes
    private const val NO_NOTES_PLACEHOLDER = "No content."

    private val ENTRY_REGEX = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL)

    // Comment: Block-level tags become newlines so a multi-paragraph changelog stays readable in the
    // UI, while the inline tags (links, bold) are stripped without breaking the surrounding sentence.
    private val BLOCK_TAG_REGEX = Regex("</?(p|div|li|ul|ol|br|h[1-6])\\b[^>]*>", RegexOption.IGNORE_CASE)
    private val ANY_TAG_REGEX = Regex("<[^>]*>")
    private val RUN_OF_SPACES_REGEX = Regex("[ \\t]+")
    private val BLANK_LINES_REGEX = Regex("\\n{3,}")

    /**
     * Reads the release tag from the URL the "/releases/latest" redirect ended on, or null when the
     * repository has no published release — GitHub then lands on ".../releases" instead of a tag page.
     */
    fun tagFromRedirectUrl(finalUrl: String): String? {
        val segments = finalUrl.toHttpUrlOrNull()?.pathSegments ?: return null
        if (segments.size < 2) return null
        if (segments[segments.size - 2] != TAG_PATH_SEGMENT) return null
        return segments.last().takeIf { it.isNotBlank() }
    }

    /**
     * Returns the release notes of [tag] from the releases Atom feed, or "" when the feed has no entry
     * for that tag (a tag pushed without a release, or a release published without notes).
     */
    fun notesFromAtomFeed(feedXml: String, tag: String): String {
        val entry = ENTRY_REGEX.findAll(feedXml)
            .map { it.groupValues[1] }
            .firstOrNull { entryTag(it) == tag } ?: return ""

        val notes = elementText(entry, "content")?.toPlainText().orEmpty()
        // Comment: Showing GitHub's "No content." placeholder would be worse than showing nothing, so
        // the UI's blank check can treat an empty body as "no notes" the same way.
        return notes.takeUnless { it.equals(NO_NOTES_PLACEHOLDER, ignoreCase = true) }.orEmpty()
    }

    // Comment: An entry's <title> is the release *name* ("Swanirvor-23 v1.0.27") rather than the tag,
    // so the tag comes from the entry <id> (tag:github.com,2008:Repository/<repo-id>/<tag>).
    private fun entryTag(entryXml: String): String? =
        elementText(entryXml, "id")?.substringAfterLast('/')?.trim()?.takeIf { it.isNotBlank() }

    /** Returns the text inside the first <[name]> element of [xml], or null when it is absent. */
    private fun elementText(xml: String, name: String): String? {
        val open = xml.indexOf("<$name")
        if (open == -1) return null
        val textStart = xml.indexOf('>', open)
        if (textStart == -1) return null
        val textEnd = xml.indexOf("</$name>", textStart)
        if (textEnd == -1) return null
        return xml.substring(textStart + 1, textEnd)
    }

    // Comment: The feed escapes the release body as HTML, so this peels off the XML layer first,
    // turns block tags into newlines, drops the remaining tags, and finally unescapes the entities the
    // body itself used (for example &amp; in "Report & Export").
    private fun String.toPlainText(): String =
        unescapeXmlEntities()
            .replace(BLOCK_TAG_REGEX, "\n")
            .replace(ANY_TAG_REGEX, " ")
            .unescapeHtmlEntities()
            .replace(RUN_OF_SPACES_REGEX, " ")
            .replace(BLANK_LINES_REGEX, "\n\n")
            .trim()

    private fun String.unescapeXmlEntities(): String = this
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")

    private fun String.unescapeHtmlEntities(): String = this
        .replace("&nbsp;", " ")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
