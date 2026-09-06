# Aurora Pure privacy notice

[한국어](PRIVACY.ko.md)

Last updated: 2026-09-07

Aurora Pure operates no first-party advertising, behavioral analytics, usage telemetry, or automatic crash-reporting service. It does not offer personal Google account login.

## Data sent over the network

When the user explicitly searches, scans variants, or starts a download, the following information may be sent as needed to Google Play and the external anonymous credential service:

- a search term or requested package name;
- the selected Android API, CPU ABI, screen density, device-profile capabilities, and locale list used for Play delivery selection;
- language codes needed to request every split declared by the delivered app;
- the network address and ordinary protocol information visible to those services;
- credentials for an anonymous session that is not the user's personal Google account.

“Anonymous” means that the user does not enter a personal Google account. It does not mean that no communication with Google occurs. Operation and data processing by external services are also subject to their own policies.

Aurora Pure probes only after an explicit action. It does not send each keystroke for remote autocomplete, scan the installed-app list, schedule background catalog requests, or upload diagnostics automatically.

## Data kept locally

- Android reads the clipboard only after the user chooses **Paste**.
- A Play link shared to Android opens a reviewable app page and never starts a download automatically.
- Temporary APKs remain in app-private or CLI cache storage until verified, exported, cancelled, or cleared.
- Android settings and task records stay in Android app storage.
- CLI settings follow the platform configuration directory; history follows the platform data directory.
- CLI history includes package/version, output path and hash, size, completion time, and non-secret profile IDs.

Authentication tokens, anonymous account email addresses, cookies, signed delivery URLs, and internal session identifiers are not written to Android exports, `.apks` files, CLI configuration, or CLI history. CLI sessions exist in memory only.

## Exported files

A standalone result is the original `.apk`. A split result is an `.apks` ZIP containing root-level APK files only. Aurora Pure does not embed `download-info.json`, checksum text, account data, tokens, signed URLs, or tracking identifiers in that archive.

## Deletion and sharing

Android distinguishes **Remove record** from **Delete saved file**. Removing a record leaves the exported file untouched. Sharing is enabled only for a completed result and uses a `content://` URI with temporary read access.

The CLI `history --clear` command removes only its local history file. It does not delete downloaded APK or `.apks` files. Cache and partial-download removal is an explicit filesystem action by the user.

## Android permissions

Aurora Pure requests only the Android platform permissions for network access and network-state access. It does not request installation, all-app visibility, broad storage, location, contacts, camera, microphone, notifications, or foreground-service permissions. AndroidX contributes an app-ID-scoped `signature` permission for guarding non-exported compatibility broadcasts; it grants no platform capability and can be held only by apps signed with the same certificate.

If this notice appears inconsistent with actual behavior, report it through the repository's GitHub Issues. Remove tokens, cookies, signed URLs, and other secrets before attaching diagnostic material.
