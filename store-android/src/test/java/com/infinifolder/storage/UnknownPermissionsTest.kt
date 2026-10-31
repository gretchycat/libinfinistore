package com.infinifolder.storage

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class UnknownPermissionsTest {
    @Test fun failedMetadataLookupDoesNotInventOwnerOrMode() {
        val file = File("/nonexistent-infinistore-permissions-test/absent")
        val permissions = PermissionHelper.getFilePermissions(file)
        assertNull(permissions.mode)
        assertNull(permissions.formatted)
        assertNull(permissions.owner)
        assertNull(permissions.group)
        assertEquals(file.canRead(), permissions.canRead)
        assertEquals(file.canWrite(), permissions.canWrite)
        assertEquals(file.canExecute(), permissions.canExecute)
    }
}
