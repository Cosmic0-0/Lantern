# Getting the model onto the phone

The Phonon model (64.5 MB) and the Everly and Trevor voices (256 KB each) ship inside the APK as uncompressed assets. Phonon loads from a file path, so on first launch Lantern copies them to its files folder, checks size and SHA-256 against values recorded at build time, and copies again if either is wrong. This costs about 130 MB of storage while the APK is installed.

Rejected: downloading from upstream with a pinned checksum, which needs download, retry and checksum code and depends on the upstream address staying put. Also rejected: `adb push` as in the spikes, which is developer-only and lost on uninstall. You install over USB, so APK size barely matters.

## Your choice

**Decided 2026-11-09: ship in the APK, copy once with a checksum check.**
