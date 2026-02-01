package de.dwienzek.fusionsolar

import java.nio.file.Path
import kotlin.io.path.appendText
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.exists

class CsvFile(
    private val path: Path,
    private val delimiter: String = ",",
    private val quote: String = "\"",
    private val lineSeparator: String = System.lineSeparator(),
) {
    init {
        require(!path.exists()) { "File already exists: $path" }
        path.parent?.createDirectories()
        path.createFile()
    }

    fun writeRow(values: List<Any?>) {
        val line =
            values.joinToString(delimiter) { value ->
                value?.let { escape(it.toString()) } ?: ""
            }

        path.appendText(line + lineSeparator)
    }

    fun writeRow(vararg values: Any?) = writeRow(values.toList())

    private fun escape(value: String): String {
        if (!needsQuoting(value)) {
            return value
        }

        val escaped = value.replace(quote, "$quote$quote")
        return "$quote$escaped$quote"
    }

    private fun needsQuoting(value: String): Boolean =
        value.contains(delimiter) ||
            value.contains(quote) ||
            value.contains('\n') ||
            value.contains('\r')
}
