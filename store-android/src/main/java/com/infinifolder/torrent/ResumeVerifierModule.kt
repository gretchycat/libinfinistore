package com.infinifolder.torrent

import bt.data.*
import bt.data.digest.Digester
import bt.runtime.Config
import com.google.inject.AbstractModule
import com.google.inject.Provides
import com.google.inject.Singleton
import java.util.BitSet

/** Only startup checks can reuse durable checkpoints; new pieces always get hashed. */
internal class ResumeVerifierModule(
    private val trusted: BitSet,
    private val onChecked: (Int, Boolean) -> Unit,
    private val available: BitSet? = null
) : AbstractModule() {
    override fun configure() = Unit

    @Provides @Singleton
    fun verifier(config: Config, digester: Digester): ChunkVerifier {
        val delegate = DefaultChunkVerifier(digester, config.numOfHashingThreads)
        return object : ChunkVerifier {
            override fun verify(chunks: List<ChunkDescriptor>, bitfield: LocalBitfield): Boolean {
                require(chunks.size == bitfield.piecesTotal)
                chunks.forEachIndexed { index, chunk ->
                    if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("Torrent verification cancelled")
                    // An unset available bit means no downloaded bytes are known here.
                    // Preallocation is not evidence of data and must not be hashed.
                    val valid = trusted.get(index) ||
                        ((available == null || available.get(index)) && delegate.verifyIfPresent(chunk))
                    onChecked(index, valid)
                    if (valid) bitfield.markLocalPieceVerified(index)
                }
                return bitfield.piecesRemaining == 0
            }
            override fun verify(chunk: ChunkDescriptor) = delegate.verify(chunk)
            override fun verifyIfPresent(chunk: ChunkDescriptor) = delegate.verifyIfPresent(chunk)
        }
    }
}
