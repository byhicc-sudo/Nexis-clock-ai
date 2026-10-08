package com.nexis.ceo

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Item(
    val id: Long = 0,
    val kind: String,
    val title: String,
    val notes: String = "",
    val customer: String = "",
    val phone: String = "",
    val amount: Double = 0.0,
    val due: String = "",
    val status: String = "Açık",
    val priority: Int = 2,
    val created: String = today()
)

fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
fun money(v: Double): String = String.format(Locale("tr", "TR"), "%,.2f ₺", v)

class NexisDb(ctx: Context) : SQLiteOpenHelper(ctx, "nexis_ceo_v1.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE entries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL,
                title TEXT NOT NULL,
                notes TEXT NOT NULL DEFAULT '',
                customer TEXT NOT NULL DEFAULT '',
                phone TEXT NOT NULL DEFAULT '',
                amount REAL NOT NULL DEFAULT 0,
                due TEXT NOT NULL DEFAULT '',
                status TEXT NOT NULL DEFAULT 'Açık',
                priority INTEGER NOT NULL DEFAULT 2,
                created TEXT NOT NULL
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_entry_kind ON entries(kind)")
        db.execSQL("CREATE INDEX idx_entry_due ON entries(due)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}

    fun save(v: Item): Long {
        val cv = ContentValues().apply {
            put("kind", v.kind); put("title", v.title)
            put("notes", v.notes); put("customer", v.customer)
            put("phone", v.phone); put("amount", v.amount)
            put("due", v.due); put("status", v.status)
            put("priority", v.priority); put("created", v.created)
        }
        return if(v.id == 0L) writableDatabase.insertOrThrow("entries", null, cv)
        else {
            writableDatabase.update("entries", cv, "id=?", arrayOf(v.id.toString()))
            v.id
        }
    }
    fun all(kind: String? = null): List<Item> {
        val result = mutableListOf<Item>()
        val selection = if (kind == null) null else "kind=?"
        val args = if (kind == null) null else arrayOf(kind)
        readableDatabase.query("entries", null, selection, args, null, null,
            "CASE WHEN due='' THEN 1 ELSE 0 END, due ASC, priority DESC, id DESC").use { c ->
            while (c.moveToNext()) {
                fun str(s: String): String = c.getString(c.getColumnIndexOrThrow(s)) ?: ""
                result.add(Item(
                    id=c.getLong(c.getColumnIndexOrThrow("id")),
                    kind=str("kind"), title=str("title"), notes=str("notes"),
                    customer=str("customer"), phone=str("phone"),
                    amount=c.getDouble(c.getColumnIndexOrThrow("amount")),
                    due=str("due"), status=str("status"),
                    priority=c.getInt(c.getColumnIndexOrThrow("priority")), created=str("created")
                ))
            }
        }
        return result
    }
    fun remove(id: Long) {
        writableDatabase.delete("entries", "id=?", arrayOf(id.toString()))
    }
    fun setStatus(id: Long, status: String) {
        writableDatabase.update("entries", ContentValues().apply { put("status", status) },
            "id=?", arrayOf(id.toString()))
    }
    fun counts(): Map<String,Int> = all().groupingBy { it.kind }.eachCount()
    fun balance(): Double = all("Tahsilat").filter { it.status != "Tahsil edildi" }.sumOf { it.amount }
    fun overdue(): List<Item> = all().filter {
        it.due.isNotBlank() && it.due < today() && it.status !in listOf("Tamamlandı","Tahsil edildi","İptal")
    }
}
