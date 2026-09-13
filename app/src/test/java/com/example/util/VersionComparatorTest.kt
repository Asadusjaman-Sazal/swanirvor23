package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionComparatorTest {

    @Test
    fun `detects a newer patch version`() {
        assertTrue(VersionComparator.isNewer("1.0.24", "1.0.23"))
    }

    @Test
    fun `detects a newer minor and major version`() {
        assertTrue(VersionComparator.isNewer("1.2.0", "1.1.9"))
        assertTrue(VersionComparator.isNewer("2.0.0", "1.9.9"))
    }

    @Test
    fun `does not flag the installed version or older versions`() {
        assertFalse(VersionComparator.isNewer("1.0.23", "1.0.23"))
        assertFalse(VersionComparator.isNewer("1.0.22", "1.0.23"))
    }

    @Test
    fun `strips a leading v from a GitHub release tag`() {
        assertFalse(VersionComparator.isNewer("v1.0.23", "1.0.23"))
        assertTrue(VersionComparator.isNewer("v1.0.24", "1.0.23"))
    }

    @Test
    fun `treats missing version parts as zero`() {
        assertFalse(VersionComparator.isNewer("1.0", "1.0.0"))
        assertTrue(VersionComparator.isNewer("1.1", "1.0.9"))
    }

    @Test
    fun `ignores pre-release and build suffixes`() {
        assertTrue(VersionComparator.isNewer("1.0.24-beta", "1.0.23"))
        assertFalse(VersionComparator.isNewer("1.0.24+build7", "1.0.24"))
    }

    @Test
    fun `never reports an unusable remote version as newer`() {
        assertFalse(VersionComparator.isNewer("", "1.0.23"))
        assertFalse(VersionComparator.isNewer("latest", "1.0.23"))
    }

    @Test
    fun `compare returns zero for equivalent versions written differently`() {
        assertEquals(0, VersionComparator.compare(" v1.0.0 ", "1.0"))
    }
}
