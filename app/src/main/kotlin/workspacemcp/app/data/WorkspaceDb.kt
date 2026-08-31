package workspacemcp.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import org.json.JSONArray
import org.json.JSONObject

data class WorkspaceRecord(
    val id: String,
    val name: String,
    val root: String,
    val createdAt: Long,
    val updatedAt: Long,
    val lastAccessAt: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("root", root)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        put("lastAccessAt", lastAccessAt ?: JSONObject.NULL)
    }
}

/**
 * 工作区元数据存储 (SQLite), 对应原 App 的 Room WorkspaceDAO.
 * listFlow 借助版本号广播实现, 写入后 +1 触发重读.
 */
class WorkspaceDb(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    val dataVersion = MutableVersionCounter()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE (
                id TEXT PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                root TEXT NOT NULL UNIQUE,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                last_access_at INTEGER
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE")
        onCreate(db)
    }

    fun list(): List<WorkspaceRecord> = readableDatabase
        .query(TABLE, null, null, null, null, null, "$COL_CREATED_AT ASC")
        .use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toRecord())
            }
        }

    fun listFlow(): Flow<List<WorkspaceRecord>> = callbackFlow {
        val listener: () -> Unit = { trySend(list()) }
        listener()
        val handle = dataVersion.addListener(listener)
        awaitClose { handle.close() }
    }

    fun getById(id: String): WorkspaceRecord? = readableDatabase
        .query(TABLE, null, "$COL_ID = ?", arrayOf(id), null, null, null)
        .use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    fun getByName(name: String): WorkspaceRecord? = readableDatabase
        .query(TABLE, null, "$COL_NAME = ?", arrayOf(name), null, null, null)
        .use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    fun insert(record: WorkspaceRecord) {
        writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            record.toValues(),
            SQLiteDatabase.CONFLICT_ABORT,
        )
        dataVersion.bump()
    }

    fun update(record: WorkspaceRecord) {
        writableDatabase.update(
            TABLE,
            record.toValues(),
            "$COL_ID = ?",
            arrayOf(record.id),
        )
        dataVersion.bump()
    }

    fun touchAccess(id: String, timestamp: Long) {
        writableDatabase.execSQL(
            "UPDATE $TABLE SET $COL_LAST_ACCESS = ? WHERE $COL_ID = ?",
            arrayOf<Any>(timestamp, id),
        )
        dataVersion.bump()
    }

    fun delete(id: String) {
        writableDatabase.delete(TABLE, "$COL_ID = ?", arrayOf(id))
        dataVersion.bump()
    }

    private fun android.database.Cursor.toRecord(): WorkspaceRecord = WorkspaceRecord(
        id = getString(getColumnIndexOrThrow(COL_ID)),
        name = getString(getColumnIndexOrThrow(COL_NAME)),
        root = getString(getColumnIndexOrThrow(COL_ROOT)),
        createdAt = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
        updatedAt = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
        lastAccessAt = getColumnIndexOrThrow(COL_LAST_ACCESS)
            .takeIf { !isNull(it) }?.let { getLong(it) },
    )

    private fun WorkspaceRecord.toValues(): ContentValues = ContentValues().apply {
        put(COL_ID, id)
        put(COL_NAME, name)
        put(COL_ROOT, root)
        put(COL_CREATED_AT, createdAt)
        put(COL_UPDATED_AT, updatedAt)
        put(COL_LAST_ACCESS, lastAccessAt)
    }

    companion object {
        private const val DB_NAME = "workspaces.db"
        private const val DB_VERSION = 1
        private const val TABLE = "workspaces"
        private const val COL_ID = "id"
        private const val COL_NAME = "name"
        private const val COL_ROOT = "root"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"
        private const val COL_LAST_ACCESS = "last_access_at"
    }
}

fun List<WorkspaceRecord>.toJsonArray(): JSONArray = JSONArray().apply {
    forEach { put(it.toJson()) }
}

/** 简单版本号广播: 每次 bump 通知所有 listener 重读 */
class MutableVersionCounter {
    private val listeners = mutableSetOf<() -> Unit>()

    @Synchronized
    fun addListener(listener: () -> Unit): AutoCloseable {
        listeners += listener
        return object : AutoCloseable {
            override fun close() {
                synchronized(this@MutableVersionCounter) { listeners -= listener }
            }
        }
    }

    @Synchronized
    fun bump() {
        listeners.forEach { it() }
    }
}
