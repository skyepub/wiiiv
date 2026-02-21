package io.wiiiv.plugins.spreadsheet

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.apache.poi.ss.usermodel.CellType
import org.apache.poi.ss.usermodel.DateUtil
import org.apache.poi.xssf.usermodel.XSSFWorkbook
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Excel 읽기/쓰기 핸들러 (POI 기반)
 */
object ExcelHandler {

    /**
     * Excel 파일을 JSON 배열 문자열로 읽기
     */
    fun read(path: String, sheet: String?, headerRow: Int, maxRows: Int): String {
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("File not found: $path")

        FileInputStream(file).use { fis ->
            val workbook = XSSFWorkbook(fis)
            val ws = if (sheet != null) workbook.getSheet(sheet) else workbook.getSheetAt(0)
                ?: throw IllegalArgumentException("Sheet not found: ${sheet ?: "index 0"}")

            val headerRowObj = ws.getRow(headerRow) ?: throw IllegalArgumentException("Header row $headerRow is empty")
            val headers = (0 until headerRowObj.lastCellNum).map { idx ->
                headerRowObj.getCell(idx)?.toString()?.trim() ?: "col_$idx"
            }

            val rows = mutableListOf<String>()
            val lastRow = minOf(ws.lastRowNum, headerRow + maxRows)
            for (i in (headerRow + 1)..lastRow) {
                val row = ws.getRow(i) ?: continue
                val obj = headers.mapIndexed { idx, hdr ->
                    val cell = row.getCell(idx)
                    val value = when {
                        cell == null -> "null"
                        cell.cellType == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell) ->
                            "\"${cell.dateCellValue}\""
                        cell.cellType == CellType.NUMERIC -> cell.numericCellValue.let {
                            if (it == it.toLong().toDouble()) it.toLong().toString() else it.toString()
                        }
                        cell.cellType == CellType.BOOLEAN -> cell.booleanCellValue.toString()
                        cell.cellType == CellType.BLANK -> "null"
                        else -> "\"${cell.toString().replace("\"", "\\\"")}\""
                    }
                    "\"$hdr\":$value"
                }.joinToString(",")
                rows.add("{$obj}")
            }

            workbook.close()
            return "[${rows.joinToString(",")}]"
        }
    }

    /**
     * JSON 배열을 Excel 파일로 쓰기
     */
    fun write(path: String, data: String, sheetName: String) {
        val parsed = Json.parseToJsonElement(data)
        val array = parsed.jsonArray

        val workbook = XSSFWorkbook()
        val sheet = workbook.createSheet(sheetName)

        if (array.isEmpty()) {
            FileOutputStream(File(path)).use { workbook.write(it) }
            workbook.close()
            return
        }

        val firstObj = array[0].jsonObject
        val headers = firstObj.keys.toList()

        val headerRow = sheet.createRow(0)
        headers.forEachIndexed { idx, hdr ->
            headerRow.createCell(idx).setCellValue(hdr)
        }

        array.forEachIndexed { rowIdx, element ->
            val obj = element.jsonObject
            val row = sheet.createRow(rowIdx + 1)
            headers.forEachIndexed { colIdx, hdr ->
                val cell = row.createCell(colIdx)
                val value = obj[hdr]
                when {
                    value == null || value is JsonNull -> {}
                    value is JsonPrimitive && value.isString ->
                        cell.setCellValue(value.content)
                    value is JsonPrimitive -> {
                        val num = value.content.toDoubleOrNull()
                        if (num != null) cell.setCellValue(num)
                        else cell.setCellValue(value.content as String)
                    }
                    else -> cell.setCellValue(value.toString() as String)
                }
            }
        }

        FileOutputStream(File(path)).use { workbook.write(it) }
        workbook.close()
    }
}
