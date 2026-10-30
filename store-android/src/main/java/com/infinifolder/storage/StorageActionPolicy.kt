package com.infinifolder.storage

import com.infinifolder.model.*
import java.io.IOException

enum class StorageAction(val capability: ResourceCapability, val label: String) {
    COPY(ResourceCapability.COPY, "Copy"),
    MOVE(ResourceCapability.MOVE, "Move"),
    RENAME(ResourceCapability.RENAME, "Rename"),
    DELETE(ResourceCapability.DELETE, "Delete"),
    CREATE(ResourceCapability.CREATE, "Create entries")
}

data class ActionDecision(val support: Support, val reason: String? = null) {
    val mayAttempt: Boolean get() = support != Support.UNSUPPORTED
    fun requireAllowed() {
        if (!mayAttempt) throw IOException(reason ?: "This action is unsupported")
    }
}

data class DestinationAssessment(
    val capabilities: ResourceCapabilities,
    val decision: ActionDecision,
    /** Immediate backing resource first, followed by enclosing containers. */
    val containers: Set<ResourceRef>
)

/** Shared eligibility rules. Inspection performs provider I/O and belongs on a worker.
 * Decisions are advisory snapshots: operations must inspect again before mutation.
 */
object StorageActionPolicy {
    fun selection(action: StorageAction, entries: List<ResourceEntry>,
                  capabilities: Map<ResourceRef, ResourceCapabilities> = emptyMap()): ActionDecision {
        if (entries.isEmpty()) return denied("No entries are selected.")
        val decisions = entries.map { this.action(action, it.metadata, capabilities[it.ref]) }
        // Copy keeps its skip-and-warn behavior for unreadable entries in a mixed selection.
        val eligible = if (action == StorageAction.COPY) decisions.filter { it.mayAttempt } else decisions
        if (eligible.isEmpty()) return decisions.first()
        eligible.firstOrNull { !it.mayAttempt }?.let { return it }
        return combined(*eligible.map { it.support }.toTypedArray())
    }

    fun action(action: StorageAction, metadata: ResourceMetadata, capabilities: ResourceCapabilities?): ActionDecision {
        if (action == StorageAction.MOVE && (metadata.isSymbolicLink || metadata.recoveryNote != null)) {
            return denied(if (metadata.isSymbolicLink)
                "Symbolic links can be copied by following their targets, or linked to; moving them is unsupported."
            else "Deleted-file candidates can only be recovered by copying to another location.")
        }
        if (action == StorageAction.CREATE && (!metadata.isDirectory || !metadata.canWrite))
            return denied("This location does not support creating entries.")
        val support = capabilities?.get(action.capability) ?: metadata.operationHints[action.capability] ?: Support.UNKNOWN
        if (support == Support.UNSUPPORTED) return denied("${action.label} is unsupported for '${metadata.name}'.")
        if ((action == StorageAction.COPY || action == StorageAction.MOVE) &&
            (!metadata.canRead || metadata.isBrokenLink || metadata.readError != null))
            return denied(metadata.readError ?: "'${metadata.name}' cannot be read.")
        return ActionDecision(support)
    }

    /** Container relocation needs both the shared mutation scope and matching source identities.
     * ACCEPT_MOVE alone cannot promise that an enclosing container can be committed safely.
     */
    fun destination(provider: StorageProvider, destination: ResourceRef,
                    sources: List<ResourceEntry> = emptyList(), move: Boolean = false): ProviderResult<DestinationAssessment> = try {
        when (val result = provider.capabilities(destination)) {
            is ProviderResult.Failure -> result
            is ProviderResult.Unsupported -> result
            is ProviderResult.Success -> {
                val capabilities = result.value
                val containers = linkedSetOf<ResourceRef>()
                var backing = provider.backingResource(destination)
                while (backing != null) {
                    if (!containers.add(backing) || containers.size > 16)
                        throw IOException("Invalid container dependency chain")
                    backing = provider.backingResource(backing)
                }
                val decision = when {
                    capabilities[ResourceCapability.CREATE] == Support.UNSUPPORTED ->
                        denied(result.notice ?: "This location does not support creating entries.")
                    move && containers.isNotEmpty() -> {
                        val sameContainer = sources.isNotEmpty() && containers.size == 1 && sources.all {
                            provider.backingResource(it.ref) == containers.first()
                        }
                        when {
                            !sameContainer -> denied("Moving into a container requires every source to be inside that same container. Use Copy for other sources; originals are retained.")
                            capabilities[ResourceCapability.ACCEPT_INTERNAL_MOVE] == Support.UNSUPPORTED ->
                                denied("Moving within this container is unsupported. Use Copy instead.")
                            else -> combined(capabilities[ResourceCapability.CREATE], capabilities[ResourceCapability.ACCEPT_INTERNAL_MOVE])
                        }
                    }
                    move && capabilities[ResourceCapability.ACCEPT_MOVE] == Support.UNSUPPORTED ->
                        denied("This location does not accept moved entries. Use Copy instead.")
                    move -> combined(capabilities[ResourceCapability.CREATE], capabilities[ResourceCapability.ACCEPT_MOVE])
                    else -> ActionDecision(capabilities[ResourceCapability.CREATE])
                }
                ProviderResult.Success(DestinationAssessment(capabilities, decision, containers), result.isLoading, result.notice)
            }
        }
    } catch (error: InterruptedException) {
        throw error
    } catch (error: Exception) {
        ProviderResult.Failure(error.message ?: "Cannot check destination", error)
    }

    /** Unknown device identities copy. Known protected sources also copy instead of moving. */
    fun preferMoveForDrop(provider: StorageProvider, sources: List<ResourceEntry>, destination: ResourceRef): Boolean {
        if (sources.isEmpty()) return false
        val identity = provider.deviceIdentity(destination) ?: return false
        return sources.all { source ->
            provider.deviceIdentity(source.ref) == identity &&
                when (val result = provider.capabilities(source.ref)) {
                    is ProviderResult.Success -> action(StorageAction.MOVE, source.metadata, result.value).mayAttempt
                    is ProviderResult.Failure -> throw IOException(result.message, result.cause)
                    is ProviderResult.Unsupported -> action(StorageAction.MOVE, source.metadata, null).mayAttempt
                }
        }
    }

    private fun denied(reason: String) = ActionDecision(Support.UNSUPPORTED, reason)
    private fun combined(vararg supports: Support) = ActionDecision(when {
        supports.all { it == Support.SUPPORTED } -> Support.SUPPORTED
        else -> Support.UNKNOWN
    })
}
