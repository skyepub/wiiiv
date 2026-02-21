package io.wiiiv.plugins.spreadsheet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVParser
import org.apache.commons.csv.CSVPrinter
import java.io.File
import java.io.FileReader
import java.io.FileWriter
import java.nio.charset.Charset

/**
 * CSV 읽기/쓰기 핸들러 (Commons CSV 기반)
 */
object CsvHandler {

    /**
     * CSV 파일을 JSON 배열 문자열로 읽기
     */
    fun read(path: String, delimiter: Char, encoding: String, hasHeader: Boolean, maxRows: Int): String {
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("File not found: $path")

        val charset = Charset.forName(encoding)
        val format = CSVFormat.DEFAULT.builder()
            .setDelimiter(delimiter)
            .setHeader()
            .setSkipHeaderRecord(hasHeader)
            .build()

        FileReader(file, charset).use { reader ->
            val parser = CSVParser(reader, format)
            val headers = parser.headerNames
            val rows = mutableListOf<String>()
            var count = 0

            for (record in parser) {
                if (count >= maxRows) break
                val obj = headers.mapIndexed { idx, hdr ->
                    val value = record.get(idx)?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
                    "\"$hdr\":$value"
                }.joinToString(",")
                rows.add("{$obj}")
                count++
            }

            parser.close()
            return "[${rows.joinToString(",")}]"
        }
    }

    /**
     * JSON 배열을 CSV 파일로 쓰기
     */
    fun write(path: String, data: String, delimiter: Char, encoding: String, writeHeader: Boolean) {
        val parsed = Json.parseToJsonElement(data)
        val array = parsed.jsonArray

        if (array.isEmpty()) {
            File(path).writeText("", Charset.forName(encoding))
            return
        }

        val firstObj = array[0].jsonObject
        val headers = firstObj.keys.toList()

        val format = CSVFormat.DEFAULT.builder()
            .setDelimiter(delimiter)
            .build()

        FileWriter(File(path), Charset.forName(encoding)).use { writer ->
            val printer = CSVPrinter(writer, format)

            if (writeHeader) {
                printer.printRecord(headers)
            }

            for (element in array) {
                val obj = element.jsonObject
                val values = headers.map { hdr ->
                    val value = obj[hdr]
                    when {
                        value == null || value is JsonNull -> ""
                        value is JsonPrimitive -> value.content
                        else -> value.toString()
                    }
                }
                printer.printRecord(values)
            }

            printer.flush()
        }
    }
}
