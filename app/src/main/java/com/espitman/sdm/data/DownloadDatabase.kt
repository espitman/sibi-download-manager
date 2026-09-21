package com.espitman.sdm.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class DownloadDatabase(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createVersionOne(db)
        migrateOneToTwo(db)
        migrateTwoToThree(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        var version = oldVersion
        if (version == 1 && newVersion >= 2) {
            migrateOneToTwo(db)
            version = 2
        }
        if (version == 2 && newVersion >= 3) {
            migrateTwoToThree(db)
            version = 3
        }
        check(version == newVersion) { "Missing database migration from $version to $newVersion" }
    }

    companion object {
        const val DATABASE_NAME = "sdm-downloads.db"
        const val DATABASE_VERSION = 3

        internal fun createVersionOne(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE downloads (
                    id TEXT NOT NULL PRIMARY KEY,
                    url TEXT NOT NULL,
                    file_name TEXT NOT NULL,
                    mime_type TEXT,
                    destination_path TEXT,
                    total_bytes INTEGER,
                    downloaded_bytes INTEGER NOT NULL DEFAULT 0,
                    state TEXT NOT NULL,
                    error TEXT,
                    priority INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL,
                    started_at INTEGER,
                    completed_at INTEGER,
                    CHECK(downloaded_bytes >= 0),
                    CHECK(total_bytes IS NULL OR total_bytes >= downloaded_bytes)
                )
                """.trimIndent(),
            )
        }

        private fun migrateOneToTwo(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
            db.execSQL("CREATE INDEX index_downloads_state ON downloads(state)")
            db.execSQL("CREATE INDEX index_downloads_priority ON downloads(priority DESC, sort_order ASC)")
        }

        private fun migrateTwoToThree(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE downloads ADD COLUMN etag TEXT")
            db.execSQL("ALTER TABLE downloads ADD COLUMN last_modified TEXT")
        }
    }
}
