
package ir.gps.tajhizat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class EquipmentDb(ctx: Context) :
    SQLiteOpenHelper(ctx, "equipment.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE equipment(
                code TEXT PRIMARY KEY,
                name TEXT,
                type TEXT,
                city TEXT,
                address TEXT,
                lat REAL,
                lon REAL,
                status TEXT,
                visit_date TEXT,
                notes TEXT,
                actions TEXT
            )"""
        )

        db.execSQL("CREATE INDEX idx_city ON equipment(city)")
        db.execSQL("CREATE INDEX idx_name ON equipment(name)")
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        // ساختار فعلی حفظ می‌شود تا اطلاعات قبلی حذف نشوند.
    }

    fun all(
        city: String = "",
        query: String = ""
    ): List<Equipment> {

        val out = mutableListOf<Equipment>()
        val clauses = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (city.isNotBlank() && city != "همه شهرها") {
            clauses += "city = ?"
            args += city
        }

        if (query.isNotBlank()) {
            clauses += """
                (
                    code LIKE ? OR
                    name LIKE ? OR
                    city LIKE ? OR
                    notes LIKE ? OR
                    address LIKE ? OR
                    type LIKE ?
                )
            """.trimIndent()

            repeat(6) {
                args += "%$query%"
            }
        }

        val where = if (clauses.isEmpty()) {
            ""
        } else {
            " WHERE " + clauses.joinToString(" AND ")
        }

        val sql = """
            SELECT code, name, type, city, address,
                   lat, lon, status, visit_date, notes, actions
            FROM equipment
            $where
            ORDER BY code
        """.trimIndent()

        readableDatabase.rawQuery(sql, args.toTypedArray()).use { c ->
            while (c.moveToNext()) {
                out += Equipment(
                    c.getString(0) ?: "",
                    c.getString(1) ?: "",
                    c.getString(2) ?: "",
                    c.getString(3) ?: "",
                    c.getString(4) ?: "",
                    c.getDouble(5),
                    c.getDouble(6),
                    c.getString(7) ?: "",
                    c.getString(8) ?: "",
                    c.getString(9) ?: "",
                    c.getString(10) ?: ""
                )
            }
        }

        return out
    }

    fun upsert(e: Equipment, replaceExisting: Boolean) {
        val values = ContentValues().apply {
            put("code", e.code)
            put("name", e.name)
            put("type", e.type)
            put("city", e.city)
            put("address", e.address)
            put("lat", e.lat)
            put("lon", e.lon)
            put("status", e.status)
            put("visit_date", e.visitDate)
            put("notes", e.notes)
            put("actions", e.actions)
        }

        writableDatabase.insertWithOnConflict(
            "equipment",
            null,
            values,
            if (replaceExisting) {
                SQLiteDatabase.CONFLICT_REPLACE
            } else {
                SQLiteDatabase.CONFLICT_IGNORE
            }
        )
    }

    fun delete(code: String) {
        writableDatabase.delete(
            "equipment",
            "code = ?",
            arrayOf(code)
        )
    }

    fun count(): Int {
        return readableDatabase.rawQuery(
            "SELECT COUNT(*) FROM equipment",
            null
        ).use { c ->
            if (c.moveToFirst()) c.getInt(0) else 0
        }
    }

    fun cities(): List<String> {
        return readableDatabase.rawQuery(
            """SELECT DISTINCT city
               FROM equipment
               WHERE city IS NOT NULL AND city <> ''
               ORDER BY city""",
            null
        ).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(c.getString(0))
                }
            }
        }
    }
}
