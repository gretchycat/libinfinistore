# Dependencies and portability

The first extraction retains Android SDK/core, Commons Compress/XZ/zstd,
JSch, Commons Net, jcifs-ng, OkHttp, Bt and the Android-compatible Guice no_aop
artifact at the versions already used by InfiniFolder. Store uses Stream contracts;
playlist-container parsing and its Sound API dependency live in Vault. Artifact declarations are in store-android's
build.gradle.kts; transitive notices and license obligations still apply.

No bundled native binaries were added. zstd is the existing AAR dependency and
requires F-Droid source-build treatment. Original-code licensing is pending
owner selection; this repository does not claim distribution clearance.
Android Uri/SAF and storage services remain in store-android. Separating a pure
resource model module is a later compatibility checkpoint, not an implicit claim
that these Android implementations already run on desktop or iOS.
