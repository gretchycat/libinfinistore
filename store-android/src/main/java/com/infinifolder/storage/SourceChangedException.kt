package com.infinifolder.storage

/** A source revision changed; continuing a batch could mix incompatible snapshots. */
class SourceChangedException(message: String) : java.io.IOException(message)
