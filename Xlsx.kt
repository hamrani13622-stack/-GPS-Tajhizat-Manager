package ir.gps.tajhizat

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

/**
 * Small dependency-free XLSX reader/writer for the app's simple 11-column template.
 * Import supports sharedStrings and inline strings in the first worksheet.
 */
object Xlsx {
    val headers = listOf("کد تجهیز","نام تجهیز","نوع تجهیز","نام شهر","نشانی","Latitude","Longitude","وضعیت تجهیز","تاریخ آخرین بازدید","توضیحات","اقدامات انجام‌شده")
    private fun readEntry(zip: ZipInputStream, wanted: String): String? {
        var e = zip.nextEntry
        while (e != null) {
            if (e.name == wanted) return zip.readBytes().toString(Charsets.UTF_8)
            e = zip.nextEntry
        }
        return null
    }
    private fun entries(input: InputStream): Map<String, ByteArray> {
        val m = linkedMapOf<String, ByteArray>()
        ZipInputStream(input).use { z ->
            var e = z.nextEntry
            while (e != null) { if (!e.isDirectory) m[e.name] = z.readBytes(); e = z.nextEntry }
        }
        return m
    }
    private fun parseXml(xml: String, tag: String): List<String> {
        val result = mutableListOf<String>()
        val p = Xml.newPullParser()
        p.setInput(xml.reader())
        var inside = false
        val sb = StringBuilder()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name == tag) { inside = true; sb.setLength(0) }
            else if (inside && event == XmlPullParser.TEXT) sb.append(p.text)
            else if (inside && event == XmlPullParser.END_TAG && p.name == tag) { result += sb.toString(); inside = false }
            event = p.next()
        }
        return result
    }
    fun import(context: Context, uri: Uri, db: EquipmentDb, replaceExisting: Boolean): Pair<Int, Int> {
        val input = context.contentResolver.openInputStream(uri) ?: error("فایل باز نشد")
        val m = input.use { entries(it) }
        val shared = m["xl/sharedStrings.xml"]?.let { parseXml(it.toString(Charsets.UTF_8), "t") } ?: emptyList()
        val sheetName = m.keys.firstOrNull { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }
            ?: error("برگه اکسل پیدا نشد")
        val sheet = m[sheetName]?.toString(Charsets.UTF_8) ?: error("برگه اکسل خوانده نشد")
        val p = Xml.newPullParser()
        p.setInput(sheet.reader())
        val rows = mutableListOf<MutableMap<Int, String>>()
        var row: MutableMap<Int, String>? = null
        var cellRef = ""
        var cellType = ""
        var inV = false
        var inT = false
        val value = StringBuilder()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = mutableMapOf()
                    "c" -> {
                        cellRef = p.getAttributeValue(null, "r") ?: ""
                        cellType = p.getAttributeValue(null, "t") ?: ""
                        value.setLength(0)
                    }
                    "v" -> inV = true
                    "t" -> if (cellType == "inlineStr") inT = true
                }
                XmlPullParser.TEXT -> if (inV || inT) value.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val letters = cellRef.takeWhile { it.isLetter() }
                        val col = letters.fold(0) { a, ch -> a * 26 + (ch.uppercaseChar() - 'A' + 1) } - 1
                        val raw = value.toString()
                        val resolved = if (cellType == "s") raw.toIntOrNull()?.let { shared.getOrNull(it) } ?: "" else raw
                        if (col >= 0) row?.put(col, resolved)
                    }
                    "row" -> {
                        row?.let { rows.add(it) }
                        row = null
                    }
                }
            }
            event = p.next()
        }
        if (rows.isEmpty()) error("فایل اکسل خالی است یا قابل خواندن نیست")

        fun clean(s: String) = s.trim().replace('\u200c', ' ').replace(Regex("\\s+"), " ")
        fun norm(s: String) = clean(s).lowercase()
            .replace("ي", "ی").replace("ك", "ک")
            .replace("_", "").replace(" ", "").replace("-", "")
        val headerIndex = rows.indexOfFirst { r ->
            val values = r.values.map { norm(it) }
            values.any { it == "latitude" } && values.any { it == "longitude" }
        }
        if (headerIndex < 0) error("عنوان ستون‌های Latitude و Longitude پیدا نشد")
        val header = rows[headerIndex].entries.associate { norm(it.value) to it.key }
        fun col(vararg names: String): Int? = names.firstNotNullOfOrNull { header[norm(it)] }
        val codeCol = col("Name", "کد تجهیز", "کد", "شناسه") ?: error("ستون Name یا کد تجهیز پیدا نشد")
        val cityCol = col("شهر", "نام شهر", "City")
        val typeCol = col("نام تجهیز", "نوع تجهیز", "Type_Network")
        val valveCol = col("number_valve")
        val addressCol = col("Address_valve", "نشانی", "آدرس", "Address")
        val latCol = col("Latitude") ?: error("ستون Latitude پیدا نشد")
        val lonCol = col("Longitude") ?: error("ستون Longitude پیدا نشد")
        val photoCol = col("Has Photo")
        val zoneCol = col("zone")
        val placementCol = col("Placement_Valve", "محل نصب")
        val descriptionCol = col("Description", "توضیحات")
        val metadataNames = listOf("Easting", "Northing", "El-Height", "Size_Valve_St", "Size_Valve_Pe", "Material_Valve")
        val metadataCols = metadataNames.mapNotNull { name -> col(name)?.let { name to it } }
        var added = 0
        var skipped = 0
        rows.drop(headerIndex + 1).forEach { r ->
            fun s(i: Int?) = i?.let { clean(r[it].orEmpty()) }.orEmpty()
            val code = s(codeCol)
            if (code.isBlank()) { skipped++; return@forEach }
            val lat = s(latCol).replace(',', '.').toDoubleOrNull()
            val lon = s(lonCol).replace(',', '.').toDoubleOrNull()
            if (lat == null || lon == null || lat !in -90.0..90.0 || lon !in -180.0..180.0) {
                skipped++
                return@forEach
            }
            val details = mutableListOf<String>()
            if (s(zoneCol).isNotBlank()) details += "زون: ${s(zoneCol)}"
            metadataCols.forEach { (name, index) -> if (s(index).isNotBlank()) details += "$name: ${s(index)}" }
            val description = s(descriptionCol)
            if (description.isNotBlank() && description != ".") details.add(0, description)
            val equipment = Equipment(
                code = code,
                name = s(valveCol).ifBlank { code },
                type = s(typeCol),
                city = s(cityCol),
                address = s(addressCol),
                lat = lat,
                lon = lon,
                status = s(photoCol),
                visitDate = "",
                notes = details.joinToString(" | "),
                actions = s(placementCol)
            )
            db.upsert(equipment, replaceExisting)
            added++
        }
        if (added == 0 && skipped == 0) error("هیچ ردیف داده‌ای بعد از عنوان ستون‌ها پیدا نشد")
        return added to skipped
    }
    private fun esc(s:String)=s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;")
    fun export(out: OutputStream, rows: List<Equipment>) {
        val all = listOf(headers) + rows.map { listOf(it.code,it.name,it.type,it.city,it.address,it.lat.toString(),it.lon.toString(),it.status,it.visitDate,it.notes,it.actions) }
        fun col(n:Int):String { var x=n+1; var s=""; while(x>0){val r=(x-1)%26;s=('A'+r)+s;x=(x-1)/26};return s }
        val sheet = buildString {
            append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>""")
            all.forEachIndexed { ri, row ->
                append("<row r=\"${ri+1}\">")
                row.forEachIndexed { ci, v -> append("<c r=\"${col(ci)}${ri+1}\" t=\"inlineStr\"><is><t>${esc(v)}</t></is></c>") }
                append("</row>")
            }
            append("</sheetData></worksheet>")
        }
        ZipOutputStream(out).use { z ->
            fun entry(name:String, content:String) { z.putNextEntry(ZipEntry(name)); z.write(content.toByteArray(Charsets.UTF_8)); z.closeEntry() }
            entry("[Content_Types].xml","""<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            entry("_rels/.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            entry("xl/workbook.xml","""<?xml version="1.0" encoding="UTF-8"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="تجهیزات" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            entry("xl/_rels/workbook.xml.rels","""<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            entry("xl/worksheets/sheet1.xml",sheet)
        }
    }
}
