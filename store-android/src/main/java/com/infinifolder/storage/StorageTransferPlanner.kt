package com.infinifolder.storage

/** Capability-based strategy selection shared by transfer callers and tests. */
object StorageTransferPlanner {
    fun choose(capabilities: StorageTransferCapabilities, move: Boolean): TransferStrategy = when {
        move && capabilities.sameBackendSession && capabilities.nativeMove && capabilities.sameFilesystem != false -> TransferStrategy.NATIVE_MOVE
        capabilities.sameBackendSession && capabilities.serverSideCopy && !move -> TransferStrategy.SERVER_SIDE_COPY
        else -> if (move) TransferStrategy.COPY_AND_DELETE else TransferStrategy.STREAMING_COPY
    }
}
