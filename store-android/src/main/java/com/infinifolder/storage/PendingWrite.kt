package com.infinifolder.storage

/** Staged outputs can discard bytes when the producer fails/cancels before closing. */
interface PendingWrite { fun abort() }
