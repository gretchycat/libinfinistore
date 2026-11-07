# Runtime capabilities — store

Updated 2026-11-04.

## Linked dependencies

Stream is an explicit API dependency. AndroidX, Commons Compress/XZ/Zstd, JSch, Commons Net, JCIFS, OkHttp and BitTorrent dependencies are declared by store-android. Store does not depend on Vault or the app.

Dependencies must be packaged/linked, not assumed to be present. Standalone source
builds resolve sibling repositories through includeBuild (or
`-PinfiniLibrariesDir=/path/to/libraries`). Android modules need SDK 36, min API 26.
A library dependency is allowed; an InfiniFolder application/UI dependency is not.

## Public discovery and execution

`StoreCapabilities.registry`, `StorageCapabilityHandler`, `StorageProviderRegistry.installDiscovered`; `StoreCapabilities.bind(actualProvider)` adds real resource-aware capability probes.

Use `RuntimeCapabilities.library("store")` for the common query, probe, callback and
revision API. The schema and registration contract are documented in the linked
libinfinistream repository's `CAPABILITIES.md`. Providers ship a standard
`META-INF/services/io.github.gretchycat.infinistream.CapabilityProvider` resource.
Constructing a provider must not connect, open input or initialize the application.

A protocol handler owns its source descriptor, URI rules, factory, connection fields and secure credential handling. Register its provider. Consumers enumerate registered factories; no new aggregator switch. Existing backend-specific connection UIs remain compatible.

Queries retain overlapping handlers. Availability is separate from metadata-only
identity. Resource probes are bounded/cancellable and can narrow operations; real
API execution still validates input. Use existing Stream sources, positional I/O,
owned outputs and typed processors; do not duplicate decoders in consumers.

## Limits

General protocol reports are CONDITIONAL. Bound provider probes inspect actual resource operation flags; a scheme match alone never grants writes. Unknown protocol secret fields require backend-owned secure credential storage. Registration withdrawal does not invalidate already opened provider/stream leases.

`store.magic` reports the explicit filename associations in
`MimeRegistry.declaredFormats()` together with bounded content identification.
Host Android intent registration consumes this descriptor through the runtime
capability catalog, rather than copying Store's association map. Other linked
handlers retain their own overlapping MIME and extension declarations.

Discovery is for installed classpath components, not a downloaded code loader.
Android/native display and live remote behavior require device checks in addition
to JVM tests. Descriptive catalog exports do not prove runtime support.

## Standalone build and tests

```sh
gradle :store-android:testDebugUnitTest :store-android:assembleDebug --offline
```

No browser process is required. Keep existing format regression suites; capability
tests invoke actual services/parsers rather than scripted success responses.

## Host audit record

InfiniFolder's 2026-11-04 runtime-discovery audit exercises this repository through
its standalone Gradle test task, and also builds the main and combined standalone
viewer APKs. The host `docs/runtime-capabilities.md` records exact commands, fresh
counts, failures repaired during the gate and unverified device paths. This is
separate from the historical extraction evidence in SOURCE_PROVENANCE.md.
