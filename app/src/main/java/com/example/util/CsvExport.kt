package com.example.util

// Helper function to escape special characters for CSV values
fun escapeCsv(value: String): String {
    val containsSpecial = value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")
    return if (containsSpecial) {
        "\"" + value.replace("\"", "\"\"") + "\""
    } else {
        value
    }
}
