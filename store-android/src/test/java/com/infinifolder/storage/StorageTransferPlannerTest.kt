package com.infinifolder.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageTransferPlannerTest {
    @Test fun nativeMoveWinsWhenSessionSupportsIt() {
        val caps = StorageTransferCapabilities(sameBackendSession = true, sameFilesystem = true,
            nativeMove = true, serverSideCopy = true, atomicRename = true)
        assertEquals(TransferStrategy.NATIVE_MOVE, StorageTransferPlanner.choose(caps, move = true))
    }

    @Test fun copyUsesServerSideCopyWhenAvailable() {
        val caps = StorageTransferCapabilities(sameBackendSession = true, sameFilesystem = false,
            serverSideCopy = true)
        assertEquals(TransferStrategy.SERVER_SIDE_COPY, StorageTransferPlanner.choose(caps, move = false))
    }

    @Test fun crossFilesystemMoveCopiesThenDeletes() {
        val caps = StorageTransferCapabilities(sameBackendSession = true, sameFilesystem = false,
            nativeMove = false, serverSideCopy = true)
        assertEquals(TransferStrategy.COPY_AND_DELETE, StorageTransferPlanner.choose(caps, move = true))
    }

    @Test fun schemeOrCapabilityAloneCannotClaimSharedSession() {
        val caps = StorageTransferCapabilities(sameBackendSession = false,
            nativeMove = true, serverSideCopy = true, atomicRename = true)
        assertEquals(TransferStrategy.COPY_AND_DELETE, StorageTransferPlanner.choose(caps, move = true))
        assertEquals(TransferStrategy.STREAMING_COPY, StorageTransferPlanner.choose(caps, move = false))
    }

    @Test fun knownFilesystemBoundaryDisablesNativeRename() {
        val caps = StorageTransferCapabilities(sameBackendSession = true, sameFilesystem = false,
            nativeMove = true, serverSideCopy = true)
        assertEquals(TransferStrategy.COPY_AND_DELETE, StorageTransferPlanner.choose(caps, move = true))
    }

    @Test fun unsupportedOperationsKeepExistingStreamingStrategies() {
        val caps = StorageTransferCapabilities()
        assertEquals(TransferStrategy.COPY_AND_DELETE, StorageTransferPlanner.choose(caps, move = true))
        assertEquals(TransferStrategy.STREAMING_COPY, StorageTransferPlanner.choose(caps, move = false))
    }
}
