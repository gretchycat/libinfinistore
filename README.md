# libinfinistore

Runtime discovery: [handler schema, registration, linked dependencies and limits](CAPABILITIES.md).

Structural extraction from gretchycat/infinifolder at 9233495.
Implementation ownership and validation are recorded in SOURCE_PROVENANCE.md.

Build using JDK 17+ and Gradle 9.7.1. Android adapters need SDK 36/min API 26.
Run `gradle :store-android:testDebugUnitTest` and the relevant assemble task.

Source dependencies: libinfinistream. Clone them beside this repository (or set -PinfiniLibrariesDir). InfiniFolder pins the complete tested set as submodules; standalone builds use these same sources, not unpublished Maven binaries. Android codec/protocol backends remain in explicit Android modules.

Original-code licensing is pending owner selection; see LICENSE.md. No original implementation is rewritten or relicensed by this move.

## Current boundary

`AppDocumentSources.getProgramPackage` resolves a document provider's owning
package from Android's provider metadata, with discovery seeding a memory cache.
It does not infer package names from authorities or require access to a document
tree. Hosts can use that package's existing app-icon service for public storage
entries while retaining access badges for directories awaiting a grant.

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

SFTP positional reads borrow a reusable range channel separately from metadata.
Independent streams are bounded per session; cancellable channel-open waiters
close late confirmations. Read-only calls and channel setup can reconnect once,
including refusal while the session still reports connected. Connected retired
sessions retain active streams until their owners close them. Failed retries remain
explicit errors; write callbacks are not replayed. The shared RECONNECTING activity
signal lets hosts display connection recovery without parsing diagnostic logs.

File-operation transfers, cache staging, revision fingerprints and replacement
snapshots use Stream's bounded copy contract. Provider stream APIs remain supported
compatibility edges; their owning callers still close/flush them. Cancellation of a
replacement does not cancel its immediate rollback: restoration and durable recovery
recording run with an existing interrupt deferred, then reinstate that signal.
Recovery restoration publishes from the preserved file without a full-backup RAM copy.

Ringtone MIME/signature routing covers MMF/SMAF, GP3 score headers and 3GPP media
brands. DOS description/art extensions have explicit text mappings; local shared
content types use the same registry. Repeated valid ANSI CSI controls can identify
ANSI art independently of the filename. Visual preview MIME does not require a
host to open text as a bitmap.

Document icon fallbacks use document symbols for plain/source text, PDF, Word
and unknown files. Word has an explicit `.doc` MIME mapping; source/application
text MIME aliases also try `text-x-generic` in icon themes.

`MimeRegistry.associations()` exposes a canonical immutable snapshot of seed
extension associations for host-owned editable registries. User edits and icon
policy stay in the host; existing MIME lookup contracts are unchanged.

`.bin` defaults to `application/octet-stream`, so arbitrary binary data does not
inherit optical-disc artwork. Existing bounded magic detection can still identify
actual disc/filesystem images and other formats. Container probing remains
available for BIN files; specific user MIME associations remain authoritative.

A `.bin` extension alone is no longer an `ArchiveFormats` disc-image hint. Hosts
can send unidentified binary data to a byte explorer while retaining real
signature-based container detection. MPEG sniffing validates ID3/header fields;
text sniffing checks bounded UTF-8 validity and legacy text/art evidence before
accepting malformed high-byte samples. These are identification guards, not full
audio decoding or filesystem validation.

FLI/FLC/FLIC and MNG aliases resolve to `video/x-fli` and `video/x-mng`; bounded
magic detects their signatures, including an unclosed FLC header with a strong
first-frame signature. Canvas owns decoding and conservative header recovery.
The host can present these animation MIME types on its image surface; recognition
alone does not promise support for every encoded operation.

RIPscrip `.rip` graphics have the explicit `image/x-rip` association. Bounded text
magic recognizes strong RIP command prefixes; Canvas owns rendering and the host
chooses EGA/high-resolution presentation.

## Transfer metadata reporting

Metadata-preservation warnings identify the requested fields: permissions,
owner/group, modification/access times, or extended attributes/ACLs. Timestamp-only
metadata must not be reported as a permission failure. Repeated warnings are grouped
by field set, with per-file reasons retained in diagnostics. An unsupported metadata
import remains unsupported; no provider is assumed able to set fields it cannot
verify. Moves retain affected originals, and ordinary copies never delete them.
The `sourceRetained` diagnostic reflects both cases. UI message suppression belongs
to the host and must not bypass warning callbacks or preservation/source checks.

## Native byte differences and SMB diagnostics

`NativeRandomAccessProvider.openNativeSeekable` is an optional direct-I/O contract;
`StorageProvider.openNativeSeekable` preserves existing provider interfaces and
uses ordinary native seekable handles for providers without staged views. SFTP
implements the optional contract with its existing offset writer. Unsupported
permits an explicit fallback; permission/transport failures do not.

`BytePatchJournal` writes synced, atomically replaced difference files containing
source length and sorted offset/original/replacement bytes, bounded to 1 MiB of
changed bytes. It preflights and merges contiguous ranges with offset I/O,
verifies writes, attempts offset rollback on failure, and preserves resumable
pending state. `render` merges into a sequential output for an explicit budgeted
cache fallback; `export` writes/verifies a new resizable native target. Callers
retain the journal and merge marker until clearing them durably after success.
No UI or application initialization is needed. Full-source locking is not claimed.

SMB connection tests now establish reachability, connect the SMB share/session,
and actually list the configured folder. Failures report the reached stage and
server error instead of returning success with an access-denied warning. Probes
use isolated contexts so edited credentials are tested. Explicit authenticated
connections do not silently fall back to guest. `SmbConfig.rootPath` joins the
configured share and its initial subfolder consistently for tests and browsing.
The server's share/filesystem permissions still determine access.

## Android roots and observed target availability

`DocumentRootInfo.query(context, authority)` returns provider document IDs, titles,
optional local-only flags and optional icon resource names. It opens no document
and grants no permission. Hosts must query on workers with a deadline, since
third-party providers may block. Inaccessible/missing metadata returns an empty
list; unknown flags remain unknown. `AppDocumentSources` uses the same query.
This is platform metadata, independent of any host theme or application core.

`NetworkConnectionStatus.target(ResourceRef)` identifies a canonical remote path;
`state(Target)` combines connection state with exact target availability.
`accessible`/`inaccessible` record request-versioned browse/read outcomes, bounded
to 256 paths per connection. Permission/missing-target failures do not mark other
folders on a reachable server offline. DNS/socket/timeout failures remain
connection-wide; unsupported work and cancellation do not imply unavailability.
Neutral observations and buffered reads do not assert a reconnection. Existing
key-only APIs and listeners remain available. SMB tests publish their result
under the caller's connection ID, independently of the temporary probe context.
