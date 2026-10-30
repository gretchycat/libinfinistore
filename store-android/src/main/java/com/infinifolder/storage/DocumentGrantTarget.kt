package com.infinifolder.storage

/** Match provider identities exactly; document IDs may look like paths but remain opaque. */
fun matchesDocumentGrant(expectedAuthority: String?, expectedDocument: String?, authority: String?, document: String): Boolean =
    expectedAuthority != null && authority == expectedAuthority && (expectedDocument == null || document == expectedDocument)
