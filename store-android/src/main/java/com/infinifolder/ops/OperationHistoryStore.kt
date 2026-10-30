package com.infinifolder.ops

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import com.infinifolder.model.*
import com.infinifolder.storage.DiagnosticLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** App-private terminal history. All loading, serialization and fsync run on one
 * worker. Progress notifications only schedule work, and never write on the UI.
 */
internal class OperationHistoryStore(private val app: Context, private val coordinator: OperationCoordinator) {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "OperationHistory").apply { isDaemon = true }
    }
    private val file by lazy { AtomicFile(File(app.filesDir, "operations/history.json")) }
    private val dirty = AtomicBoolean(false)
    private val scheduled = AtomicBoolean(false)
    private var lastSaved: List<OperationSnapshot> = emptyList()

    fun start() {
        worker.execute {
            val restored = load()
            lastSaved = restored.map(::historical)
            coordinator.restoreHistory(restored)
            coordinator.addListener { changed() }
            DiagnosticLog.record("operation.history.restored", "records=${restored.size}")
        }
    }

    private fun changed() {
        dirty.set(true)
        if (scheduled.compareAndSet(false, true)) worker.execute {
            try {
                do {
                    dirty.set(false)
                    // Read current state on this worker; older callback payloads
                    // must not resurrect entries removed by another callback.
                    val current = coordinator.snapshots().filter { it.canRemoveFromHistory }.map(::historical)
                    if (current != lastSaved) {
                        try {
                            save(current)
                            lastSaved = current
                        } catch (error: Exception) {
                            DiagnosticLog.record("operation.history.save.failed", error = error)
                        }
                    }
                } while (dirty.get())
            } finally {
                scheduled.set(false)
                if (dirty.get()) changed()
            }
        }
    }

    private fun historical(op: OperationSnapshot) = op.copy(bytesPerSecond = 0,
        estimatedRemainingSeconds = null, etaIsVerification = false, torrentStats = null, indeterminate = false)

    private fun load(): List<OperationSnapshot> {
        val bytes = try { file.readFully() } catch (_: FileNotFoundException) { return emptyList() }
        catch (error: Exception) {
            preserveUnreadable(error)
            return emptyList()
        }
        return try {
            val root = JSONObject(bytes.toString(Charsets.UTF_8))
            require(root.getInt("version") == 1) { "Unsupported operation history version" }
            val rows = root.getJSONArray("operations")
            var unreadable = false
            val restored = (0 until rows.length()).mapNotNull { index ->
                try { decode(rows.getJSONObject(index)).takeIf { it.canRemoveFromHistory } }
                catch (error: Exception) {
                    unreadable = true
                    DiagnosticLog.record("operation.history.row.failed", "index=$index", error)
                    null
                }
            }
            if (unreadable) preserveUnreadable(null)
            restored
        } catch (error: Exception) {
            preserveUnreadable(error)
            emptyList()
        }
    }

    private fun preserveUnreadable(error: Exception?) {
        runCatching {
            if (file.baseFile.exists()) file.baseFile.copyTo(
                File(file.baseFile.parentFile, "history-unreadable-${System.currentTimeMillis()}.json"))
        }.onFailure { DiagnosticLog.record("operation.history.backup.failed", error = it) }
        DiagnosticLog.record("operation.history.load.failed", "Unreadable history retained when possible", error)
    }

    private fun save(records: List<OperationSnapshot>) {
        val rows = JSONArray()
        records.forEach { rows.put(encode(it)) }
        val bytes = JSONObject().put("version", 1).put("operations", rows).toString().toByteArray(Charsets.UTF_8)
        file.baseFile.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) error("Cannot create operation history directory") }
        val output = file.startWrite()
        try {
            output.write(bytes)
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
        DiagnosticLog.record("operation.history.saved", "records=${records.size}")
    }

    private fun encode(op: OperationSnapshot) = JSONObject()
        .put("id", op.id).put("label", op.label).put("state", op.state.name)
        .put("bytes", op.bytes).putOpt("totalBytes", op.totalBytes)
        .put("items", op.itemsProcessed).put("totalItems", op.totalItems)
        .putOpt("item", op.currentItem).put("started", op.startTimeMillis).put("updated", op.lastUpdateMillis)
        .putOpt("message", op.message).putOpt("fileBytes", op.currentItemBytes).putOpt("fileTotal", op.currentItemTotalBytes)
        .put("resumable", op.resumable).putOpt("remaining", op.remainingBytes)
        .putOpt("piecesChecked", op.torrentPiecesChecked).putOpt("piecesTotal", op.torrentPiecesTotal)
        .put("warnings", op.warningCount).putOpt("name", op.displayName).putOpt("destination", op.destination)
        .putOpt("resumeDestination", op.resumeDestination?.let(::encodeRef)).put("paused", op.pausedByUser)
        .putOpt("restart", op.restartTransfer?.takeIf { op.state != OperationState.COMPLETE }?.let { request ->
            val entries = JSONArray()
            request.entries.forEach { entries.put(encodeEntry(it)) }
            JSONObject().put("entries", entries).put("destination", request.destination).put("move", request.move)
                .put("links", request.createLinks).put("partialRecovery", request.partialRecovery)
        })

    private fun decode(row: JSONObject): OperationSnapshot {
        val restart = row.optJSONObject("restart")?.let { request ->
            val entries = request.getJSONArray("entries")
            TransferRestartRequest((0 until entries.length()).map { decodeEntry(entries.getJSONObject(it)) },
                request.getString("destination"), request.getBoolean("move"), request.optBoolean("links"), request.optBoolean("partialRecovery"))
        }
        return OperationSnapshot(id = row.getString("id"), label = row.getString("label"),
            state = OperationState.valueOf(row.getString("state")), bytes = row.optLong("bytes"), totalBytes = row.longOrNull("totalBytes"),
            itemsProcessed = row.optInt("items"), totalItems = row.optInt("totalItems"), currentItem = row.stringOrNull("item"),
            startTimeMillis = row.getLong("started"), lastUpdateMillis = row.optLong("updated", row.getLong("started")),
            message = row.stringOrNull("message"), currentItemBytes = row.longOrNull("fileBytes"), currentItemTotalBytes = row.longOrNull("fileTotal"),
            resumable = row.optBoolean("resumable"), remainingBytes = row.longOrNull("remaining"),
            torrentPiecesChecked = row.intOrNull("piecesChecked"), torrentPiecesTotal = row.intOrNull("piecesTotal"),
            warningCount = row.optInt("warnings"), displayName = row.stringOrNull("name"), destination = row.stringOrNull("destination"),
            resumeDestination = row.optJSONObject("resumeDestination")?.let(::decodeRef), restartTransfer = restart,
            pausedByUser = row.optBoolean("paused"), restoredFromHistory = true)
    }

    private fun encodeRef(ref: ResourceRef) = JSONObject().put("provider", ref.providerId).put("document", ref.documentId)
    private fun decodeRef(row: JSONObject) = ResourceRef(row.getString("provider"), row.getString("document"))

    private fun encodeEntry(entry: ResourceEntry): JSONObject {
        val m = entry.metadata
        val hints = JSONObject()
        m.operationHints.forEach { (capability, support) -> hints.put(capability.name, support.name) }
        return JSONObject().put("ref", encodeRef(entry.ref)).put("uri", entry.uri.toString())
            .put("name", m.name).put("mime", m.mimeType).putOpt("size", m.sizeBytes).putOpt("modified", m.modifiedAtMillis)
            .put("directory", m.isDirectory).put("hidden", m.isHidden).put("read", m.canRead).put("write", m.canWrite)
            .put("execute", m.canExecute).putOpt("mode", m.permissionsMode).putOpt("permissions", m.permissionsFormatted)
            .putOpt("owner", m.owner).putOpt("group", m.group).put("link", m.isSymbolicLink).putOpt("target", m.linkTarget)
            .put("broken", m.isBrokenLink).put("hints", hints).putOpt("readError", m.readError)
            .putOpt("recovery", m.recoveryNote).put("partial", m.partialRecoveryAvailable)
    }

    private fun decodeEntry(row: JSONObject): ResourceEntry {
        val hints = mutableMapOf<ResourceCapability, Support>()
        row.optJSONObject("hints")?.let { values ->
            values.keys().forEach { key ->
                runCatching { hints[ResourceCapability.valueOf(key)] = Support.valueOf(values.getString(key)) }
            }
        }
        return ResourceEntry(decodeRef(row.getJSONObject("ref")), ResourceMetadata(
            name = row.getString("name"), mimeType = row.getString("mime"), sizeBytes = row.longOrNull("size"), modifiedAtMillis = row.longOrNull("modified"),
            isDirectory = row.getBoolean("directory"), isHidden = row.optBoolean("hidden"), canRead = row.optBoolean("read", true),
            canWrite = row.optBoolean("write", true), canExecute = row.optBoolean("execute"), permissionsMode = row.intOrNull("mode"),
            permissionsFormatted = row.stringOrNull("permissions"), owner = row.stringOrNull("owner"), group = row.stringOrNull("group"),
            isSymbolicLink = row.optBoolean("link"), linkTarget = row.stringOrNull("target"), isBrokenLink = row.optBoolean("broken"),
            operationHints = hints, readError = row.stringOrNull("readError"), recoveryNote = row.stringOrNull("recovery"),
            partialRecoveryAvailable = row.optBoolean("partial")), Uri.parse(row.getString("uri")))
    }

    private fun JSONObject.stringOrNull(key: String) = if (isNull(key)) null else getString(key)
    private fun JSONObject.longOrNull(key: String) = if (isNull(key)) null else getLong(key)
    private fun JSONObject.intOrNull(key: String) = if (isNull(key)) null else getInt(key)
}
