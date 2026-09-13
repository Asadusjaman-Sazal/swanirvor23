package com.example.util

/**
 * Compares dotted version strings such as "1.0.24" or GitHub release tags like "v1.0.24".
 * Only numeric parts are compared, so build/release suffixes never flip the result.
 */
object VersionComparator {

    // Comment: True when the candidate version is strictly newer than the installed one, so an
    // identical or older remote tag (e.g. after a manual APK install) never prompts an update.
    fun isNewer(candidate: String, installed: String): Boolean = compare(candidate, installed) > 0

    // Comment: Compare two versions part by part, treating missing parts as 0 so "1.1" beats "1.0.9"
    // and equals "1.1.0".
    fun compare(left: String, right: String): Int {
        val leftParts = parse(left)
        val rightParts = parse(right)
        val partCount = maxOf(leftParts.size, rightParts.size)
        for (index in 0 until partCount) {
            val leftPart = leftParts.getOrElse(index) { 0 }
            val rightPart = rightParts.getOrElse(index) { 0 }
            if (leftPart != rightPart) {
                return leftPart.compareTo(rightPart)
            }
        }
        return 0
    }

    // Comment: Strip a leading "v" tag prefix and keep only the leading numeric parts, so suffixes
    // such as "-beta" or "+build1" do not break the comparison.
    fun parse(version: String): List<Int> {
        return version.trim()
            .removePrefix("v")
            .removePrefix("V")
            .split('.', '-', '+', '_')
            .mapNotNull { it.trim().toIntOrNull() }
    }
}
