package bt.net.pipeline;

import bt.magnet.UtMetadata;
import bt.net.Peer;
import bt.net.buffer.BorrowedBuffer;
import bt.net.buffer.BufferMutator;
import bt.net.buffer.IBufferManager;
import bt.net.crypto.CipherBufferMutator;
import bt.protocol.Handshake;
import bt.protocol.Piece;
import bt.metainfo.TorrentId;
import com.infinifolder.torrent.TorrentPeerActivity;
import bt.protocol.Message;
import bt.protocol.extended.ExtendedHandshake;
import bt.protocol.handler.MessageHandler;
import bt.service.IdentityService;
import com.infinifolder.storage.DiagnosticLog;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Adapt the already decrypted MSE prefix to Bt 1.10's ordinary inbound decoder.
 * Lives in this package because ChannelPipelineBuilder's constructor is package-private.
 */
public final class MseSafeChannelPipelineFactory implements IChannelPipelineFactory {
    private static final AtomicLong CONNECTION_IDS = new AtomicLong();
    private final IBufferManager buffers;
    private final IBufferedPieceRegistry pieces;
    private final IdentityService identity;
    private final TorrentPeerActivity activity;

    public MseSafeChannelPipelineFactory(IBufferManager buffers, IBufferedPieceRegistry pieces,
            IdentityService identity, TorrentPeerActivity activity) {
        this.buffers = buffers;
        this.pieces = pieces;
        this.identity = identity;
        this.activity = activity;
    }

    @Override public ChannelPipelineBuilder buildPipeline(Peer peer) {
        return new ChannelPipelineBuilder(peer) {
            @Override protected ChannelPipeline doBuild(Peer target, ByteChannel channel,
                    MessageHandler<Message> protocol, Optional<BorrowedBuffer<ByteBuffer>> inbound,
                    Optional<BorrowedBuffer<ByteBuffer>> outbound, List<BufferMutator> decoders,
                    List<BufferMutator> encoders) {
                BorrowedBuffer<ByteBuffer> in = inbound.orElseGet(buffers::borrowByteBuffer);
                BorrowedBuffer<ByteBuffer> out = outbound.orElseGet(buffers::borrowByteBuffer);
                int prefix;
                ByteBuffer bytes = in.lockAndGet();
                try { prefix = bytes.position(); } finally { in.unlock(); }
                long connection = CONNECTION_IDS.incrementAndGet();
                boolean encrypted = decoders.stream().anyMatch(d -> d instanceof CipherBufferMutator);
                List<BufferMutator> safeDecoders = new ArrayList<>();
                for (BufferMutator decoder : decoders) {
                    if (prefix > 0 && decoder instanceof CipherBufferMutator) {
                        // MSEHandshakeProcessor has advanced the cipher through these bytes
                        // and compacted their plaintext into the beginning of the buffer.
                        safeDecoders.add(new BufferMutator() {
                            private int skip = prefix;
                            @Override public void mutate(ByteBuffer buffer) {
                                int count = Math.min(skip, buffer.remaining());
                                buffer.position(buffer.position() + count);
                                skip -= count;
                                decoder.mutate(buffer);
                            }
                        });
                    } else safeDecoders.add(decoder);
                }
                record("pipeline", connection, "family=" + family(target) + " port=" + target.getPort()
                        + " encrypted=" + encrypted + " bufferedPrefix=" + prefix
                        + " skipSecondDecryption=" + (encrypted && prefix > 0));
                try {
                    return new DefaultChannelPipeline(target, protocol, in, out, safeDecoders, encoders, pieces) {
                        private int metadataEvents;
                        private volatile TorrentId torrentId;
                        @Override public Message decode() {
                            try {
                                Message message = super.decode();
                                if (message instanceof Handshake) {
                                    Handshake handshake = (Handshake) message;
                                    torrentId = handshake.getTorrentId();
                                    boolean self = identity.getLocalPeerId().equals(handshake.getPeerId());
                                    record("handshake", connection, "hash=" + handshake.getTorrentId()
                                            + " self=" + self + " extended=" + handshake.isReservedBitSet(43));
                                    if (self) throw new IllegalStateException("Rejected torrent self-connection");
                                }
                                logMetadata("received", message);
                                return message;
                            } catch (RuntimeException error) {
                                record("decode-failed", connection, "cause=" + error.getClass().getSimpleName());
                                throw error;
                            }
                        }
                        @Override public boolean encode(Message message) {
                            boolean accepted = super.encode(message);
                            if (accepted) {
                                if (message instanceof Handshake) torrentId = ((Handshake) message).getTorrentId();
                                if (message instanceof Piece && torrentId != null)
                                    activity.sentPiece(torrentId, connection, target);
                                logMetadata("sent", message);
                            }
                            return accepted;
                        }
                        private void logMetadata(String direction, Message message) {
                            if (message instanceof ExtendedHandshake && metadataEvents++ < 64) {
                                ExtendedHandshake handshake = (ExtendedHandshake) message;
                                record("extensions", connection, "direction=" + direction
                                        + " metadata=" + handshake.getSupportedMessageTypes().contains("ut_metadata")
                                        + " pex=" + handshake.getSupportedMessageTypes().contains("ut_pex"));
                            } else if (message instanceof UtMetadata && metadataEvents++ < 64) {
                                UtMetadata metadata = (UtMetadata) message;
                                record("metadata", connection, "direction=" + direction + " type=" + metadata.getType()
                                        + " piece=" + metadata.getPieceIndex() + " totalSize="
                                        + metadata.getTotalSize().orElse(-1));
                            }
                        }
                    };
                } catch (RuntimeException error) {
                    record("pipeline-failed", connection, "encrypted=" + encrypted + " bufferedPrefix=" + prefix
                            + " cause=" + error.getClass().getSimpleName());
                    throw error;
                }
            }
        };
    }
    private static String family(Peer peer) {
        return peer.getInetAddress().getAddress().length == 16 ? "ipv6" : "ipv4";
    }
    private static void record(String phase, long connection, String details) {
        DiagnosticLog.INSTANCE.record("torrent.peer." + phase, "connection=" + connection + " " + details, null);
    }
}
