package com.infinifolder.storage

import android.content.Context
import android.provider.DocumentsContract

/** Android provider root metadata only. No document opens; hosts must query on a worker with a deadline. */
data class DocumentRootInfo(val documentId: String, val title: String, val localOnly: Boolean?,
    val iconResourceName: String?) {
    companion object {
        /** Some providers deny root discovery; a saved tree remains usable independently. Worker-only. */
        fun query(context: Context, authority: String): List<DocumentRootInfo> = runCatching {
            context.contentResolver.query(DocumentsContract.buildRootsUri(authority), null, null, null, null)?.use { cursor ->
                val owner = runCatching { context.packageManager.resolveContentProvider(authority, 0)?.applicationInfo }.getOrNull()
                val resources = runCatching { owner?.let { context.packageManager.getResourcesForApplication(it) } }.getOrNull()
                val idColumn = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_DOCUMENT_ID)
                val titleColumn = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_TITLE)
                val flagsColumn = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_FLAGS)
                val iconColumn = cursor.getColumnIndex(DocumentsContract.Root.COLUMN_ICON)
                buildList {
                    if (idColumn < 0) return@buildList
                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idColumn) ?: continue
                        val local = if (flagsColumn < 0 || cursor.isNull(flagsColumn)) null else
                            cursor.getInt(flagsColumn) and DocumentsContract.Root.FLAG_LOCAL_ONLY != 0
                        val icon = if (iconColumn < 0 || cursor.isNull(iconColumn)) null else runCatching {
                            resources?.getResourceEntryName(cursor.getInt(iconColumn))
                        }.getOrNull()
                        add(DocumentRootInfo(id, if (titleColumn < 0) "" else cursor.getString(titleColumn).orEmpty(), local, icon))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }
}
