# libinfinistore

Structural extraction from gretchycat/infinifolder at 9233495.
Implementation ownership and validation are recorded in SOURCE_PROVENANCE.md.

Build using JDK 17+ and Gradle 9.7.1. Android adapters need SDK 36/min API 26.
Run `gradle :store-android:testDebugUnitTest` and the relevant assemble task.

Source dependencies: libinfinistream. Clone them beside this repository (or set -PinfiniLibrariesDir). InfiniFolder pins the complete tested set as submodules; standalone builds use these same sources, not unpublished Maven binaries. Android codec/protocol backends remain in explicit Android modules.

Original-code licensing is pending owner selection; see LICENSE.md. No original implementation is rewritten or relicensed by this move.

## Current boundary

`store-android` contains the existing resource/provider contracts and local/SAF/network/torrent implementations, guarded content replacement, cache and transfer operations. It depends only on Stream within the family, never Vault. Playlist-container parsing lives in Vault and delegates to Sound's small API. Generic legacy BlockDevice/cache/cancellation and archive URI/format-hint helpers remain here temporarily to avoid a Store/Vault cycle; reader/parser implementations are in Vault. Host StorageRuntime/RoutingStorageProvider and external-edit orchestration remain in InfiniFolder. Diagnostic publication receives a host-injected provider callback; the library never instantiates that runtime. ProviderStreams/BlockDeviceSource/StreamBlockDevice bridge sequential and positional access without hidden spooling or EOF changes in old APIs.

Protocol dependencies and permissions are preserved. This initial module is Android-specific, not a portable reimplementation of network providers. The next split can separate pure resource types without breaking callers.

`StreamFormatProbe` borrows a bounded positional ProbeReader for headers/nonzero signatures/footers, preserving the caller's cursor. It returns provisional metadata, candidates/confidence/evidence and mismatch warnings while retaining the original name/provenance. Sequential callers can inspect an already buffered prefix and replay it. Unknown/raw data is not classified from its extension. No parser is opened by inspection; existing format implementations perform validation at explicit execution.

RealMedia container extensions have explicit MIME entries: `.rm` is
`application/vnd.rn-realmedia`, `.rmvb` is `application/vnd.rn-realmedia-vbr`, and
`.ra` retains `audio/x-pn-realaudio`. Hosts choose the matching playback library.

When both native metadata lookups fail, `PermissionHelper` preserves effective
`canRead/canWrite/canExecute` checks and returns unknown POSIX mode/owner/group.
It no longer synthesizes permissions or assigns the app's identity to the file.
The new unknown-metadata case brings the standalone suite to seven passing tests.
Monkey's Audio `.ape` has an explicit `audio/x-ape` association for the registered
audio codecs; the MIME check brings the standalone suite to eight passing tests.

Winamp `.wsz` skins are classified as ZIP-packaged application resources, even
with a mismatched provider MIME type. The host owns install/apply actions; Vault
uses the common container mapping for archive browsing.

SFTP positional reads reopen a disconnected pooled session once when opening a
range channel. If a metadata operation discovers a dead session, the pool drops
it so the next operation establishes a fresh connection. A failed retry remains
an explicit read error; write callbacks are not replayed.

File-operation transfers, cache staging, revision fingerprints and replacement
snapshots use Stream's bounded copy contract. Provider stream APIs remain supported
compatibility edges; their owning callers still close/flush them. Cancellation of a
replacement does not cancel its immediate rollback: restoration and durable recovery
recording run with an existing interrupt deferred, then reinstate that signal.
Recovery restoration publishes from the preserved file without a full-backup RAM copy.
