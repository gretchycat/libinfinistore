package com.infinifolder.staging

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Durable identity and edit-state ledger. Private paths are cache locations, never resource identities. */
class StagingRepository(context: Context) : SQLiteOpenHelper(context, "staging.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE staged_file (cache_key TEXT PRIMARY KEY, provider_id TEXT NOT NULL, resource_id TEXT NOT NULL, display_path TEXT NOT NULL, local_path TEXT NOT NULL, source_revision TEXT, local_fingerprint TEXT, dirty INTEGER NOT NULL DEFAULT 0, open_sessions INTEGER NOT NULL DEFAULT 0, pending_writeback INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
}
