package com.infinifolder.torrent;

import bt.module.TrackerFactories;
import bt.runtime.Config;
import bt.service.IdentityService;
import bt.service.IRuntimeLifecycleBinder;
import bt.torrent.TorrentRegistry;
import bt.tracker.ITrackerService;
import bt.tracker.TrackerFactory;
import bt.tracker.TrackerService;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import java.util.HashMap;
import java.util.Map;

/** Keep Guice's exact Map<String, TrackerFactory> key free of Kotlin variance. */
public final class DualStackTrackerModule extends AbstractModule {
    @Override protected void configure() {}

    @Provides @Singleton
    public ITrackerService trackerService(
            @TrackerFactories Map<String, TrackerFactory> factories,
            Config config, IdentityService identity, TorrentRegistry registry,
            IRuntimeLifecycleBinder lifecycle) {
        DualStackUdpTrackerFactory udp = new DualStackUdpTrackerFactory(config, identity, registry);
        lifecycle.onShutdown("dual-stack-udp-trackers", udp::close);
        Map<String, TrackerFactory> combined = new HashMap<>(factories);
        combined.put("udp", udp);
        return new TrackerService(combined);
    }
}
