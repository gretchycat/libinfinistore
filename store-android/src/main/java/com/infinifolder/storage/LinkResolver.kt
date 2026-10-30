package com.infinifolder.storage

import android.net.Uri
import com.infinifolder.model.*
import com.infinifolder.shortcut.ShortcutInfo
import java.io.IOException

enum class LinkKind { NATIVE, SHORTCUT }
data class ResolvedLink(val uri: Uri, val entry: ResourceEntry?, val kinds: List<LinkKind>, val name: String?, val mime: String?)

/** Shared classification and cycle-safe resolution; deleting a link never calls this resolver. */
object LinkResolver {
    fun kind(metadata: ResourceMetadata): LinkKind? = when {
        metadata.isSymbolicLink -> LinkKind.NATIVE
        !metadata.isDirectory && ShortcutInfo.isShortcut(metadata.name, metadata.mimeType) -> LinkKind.SHORTCUT
        else -> null
    }
    fun resolve(provider: StorageProvider, initial: ResourceEntry): ProviderResult<ResolvedLink> = try {
        var entry = initial
        val visited = mutableSetOf<ResourceRef>()
        val kinds = mutableListOf<LinkKind>()
        var result: ResolvedLink? = null
        repeat(16) {
            if (result == null) {
                if (!visited.add(entry.ref)) throw IOException("Cyclic link target")
                val kind = kind(entry.metadata)
                if (kind == null) result = ResolvedLink(entry.uri, entry, kinds.toList(), entry.metadata.name, entry.metadata.mimeType) else {
                    kinds += kind
                    val target = if (kind == LinkKind.NATIVE) {
                        when (val resolved = provider.resolveLink(entry.ref)) {
                            is ProviderResult.Success -> resolved.value
                            is ProviderResult.Failure -> throw IOException(resolved.message, resolved.cause)
                            is ProviderResult.Unsupported -> throw IOException("This backend cannot resolve native links")
                        }
                    } else {
                        val stream = when (val read = provider.openRead(entry.ref)) {
                            is ProviderResult.Success -> read.value
                            is ProviderResult.Failure -> throw IOException(read.message, read.cause)
                            is ProviderResult.Unsupported -> throw IOException("Shortcut cannot be read")
                        }
                        val bytes = stream.use { input ->
                            val output = java.io.ByteArrayOutputStream()
                            val buffer = ByteArray(8192)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                if (output.size() + n > 1024 * 1024) throw IOException("Shortcut file is too large")
                                output.write(buffer, 0, n)
                            }
                            output.toByteArray()
                        }
                        val info = ShortcutInfo.fromJson(String(bytes, Charsets.UTF_8))
                            ?: throw IOException("Unsupported link-file encoding (binary Windows LNK is not a JSON shortcut)")
                        val uri = Uri.parse(info.targetUri)
                        val ref = provider.refForUri(uri)
                        if (ref == null) {
                            result = ResolvedLink(uri, null, kinds.toList(), info.displayNameOverride, info.targetMimeType)
                        }
                        ref
                    }
                    if (target != null) {
                        entry = when (val stat = provider.stat(target)) {
                            is ProviderResult.Success -> stat.value
                            is ProviderResult.Failure -> throw IOException(stat.message, stat.cause)
                            is ProviderResult.Unsupported -> throw IOException("Link target metadata is unavailable")
                        }
                    }
                }
            }
        }
        ProviderResult.Success(result ?: throw IOException("Link nesting limit exceeded"))
    } catch (e: Exception) { ProviderResult.Failure(e.message ?: "Cannot resolve link", e) }
}
