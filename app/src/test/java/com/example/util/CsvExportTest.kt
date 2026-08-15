package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvExportTest {

    @Test
    fun `leaves plain values unchanged`() {
        assertEquals("Asad", escapeCsv("Asad"))
    }

    @Test
    fun `wraps values containing a comma in quotes`() {
        assertEquals("\"Last, First\"", escapeCsv("Last, First"))
    }

    @Test
    fun `doubles embedded quotes`() {
        assertEquals("\"Say \"\"Hi\"\"\"", escapeCsv("Say \"Hi\""))
    }

    @Test
    fun `wraps values containing a newline in quotes`() {
        assertEquals("\"line1\nline2\"", escapeCsv("line1\nline2"))
    }
}
