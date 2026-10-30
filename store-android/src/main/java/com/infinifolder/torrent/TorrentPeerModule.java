package com.infinifolder.torrent;

import bt.net.buffer.IBufferManager;
import bt.net.pipeline.IBufferedPieceRegistry;
import bt.net.pipeline.IChannelPipelineFactory;
import bt.net.pipeline.MseSafeChannelPipelineFactory;
import bt.service.IdentityService;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

/** Use a concrete Java provider to preserve Guice's exact binding keys. */
public final class TorrentPeerModule extends AbstractModule {
    @Override protected void configure() {}
    @Provides @Singleton
    public TorrentPeerActivity activity() { return new TorrentPeerActivity(); }

    @Provides @Singleton
    public IChannelPipelineFactory pipelines(IBufferManager buffers, IBufferedPieceRegistry pieces,
            IdentityService identity, TorrentPeerActivity activity) {
        return new MseSafeChannelPipelineFactory(buffers, pieces, identity, activity);
    }
}
