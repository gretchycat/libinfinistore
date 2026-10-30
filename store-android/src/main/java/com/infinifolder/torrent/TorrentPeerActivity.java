package com.infinifolder.torrent;

import bt.metainfo.TorrentId;
import bt.net.ConnectionKey;
import bt.net.Peer;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Per-runtime activity, counting only connected peers sent payload in the last ten seconds. */
public final class TorrentPeerActivity {
    private static final long WINDOW = TimeUnit.SECONDS.toNanos(10);
    private static final class Upload {
        final Peer peer;
        final long at;
        Upload(Peer peer, long at) { this.peer = peer; this.at = at; }
    }
    private final ConcurrentHashMap<TorrentId, ConcurrentHashMap<Long, Upload>> uploads = new ConcurrentHashMap<>();
    public void sentPiece(TorrentId torrent, long connection, Peer peer) {
        uploads.computeIfAbsent(torrent, ignored -> new ConcurrentHashMap<>())
                .put(connection, new Upload(peer, System.nanoTime()));
    }
    public int servingPeers(TorrentId torrent, Set<ConnectionKey> connected) {
        ConcurrentHashMap<Long, Upload> recent = uploads.get(torrent);
        if (recent == null) return 0;
        long oldest = System.nanoTime() - WINDOW;
        recent.entrySet().removeIf(entry -> entry.getValue().at < oldest);
        java.util.Set<Peer> serving = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        recent.values().forEach(upload -> {
            if (connected.stream().anyMatch(key -> key.getPeer() == upload.peer)) serving.add(upload.peer);
        });
        return serving.size();
    }
}
