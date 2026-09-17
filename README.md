# NoteTag

Share a Joplin note or a link to NoteTag, hold an NFC tag against the back of the phone, and the
tag carries that note from then on: tap it later and it opens. That is the whole product — a
narrow note-and-link NFC utility, and deliberately nothing else.

## Identity

- Application id `com.loosecannon.notetag`, label `NoteTag`.
- NDEF external type `com.loosecannon.notetag:tag` — the only type NoteTag writes, and the only
  one its single `NDEF_DISCOVERED` filter matches.
- **No Android Application Record.** A NoteTag tag never carries an app to install.
- The deep-link scheme `notetag` is **reserved and undeclared**: no `VIEW` filter is registered at
  reconstruction, and the scheme is held for the roadmap below.
- The icon and theme use a blue palette derived from Joplin's, not its colours.

Each of those values is typed once, in `app/build.gradle.kts`; the manifest carries a placeholder
and `BuildConfig` carries the same strings, so the filter and the codec cannot drift apart.

## What a tag holds

One record, format v1 — `version | kind | flags | body` — in one of three kinds: `JOPLIN_NOTE`
(the note's 16 id bytes, 49 bytes as an NDEF message), `URI` (the link itself), or `LOCAL_REF` (a 16-byte
UUID that means nothing without this phone).

The writer decides on its own and tells you what it did: a Joplin note is written as the compact
`JOPLIN_NOTE`; any other link is written whole as a `URI` when the encoded message fits the tag's
measured `Ndef.maxSize`; only when it does not fit does the writer fall back to a `LOCAL_REF` and
keep the link in a small JSON map on the phone. **Tags written as a local reference only work on
this phone** — NoteTag says so before the write and again when it lands, and the mapping is saved
before any byte reaches the tag.

## What it does not do

- **No chooser.** The kind is the writer's decision, not a question put to you.
- **It does not write ServiceTag tags, and does not read them as damage.** A tag belonging to the
  sibling product is named as ServiceTag's, and overwriting it takes one explicit confirmation.
- **No export or import of the local map yet**, so a `LOCAL_REF` tag does not survive a move to a
  new phone. Back up the app if you rely on one. The map is excluded from Android's cloud backup
  and from device-to-device transfer by design (`res/xml/data_extraction_rules.xml`) until the
  recovery roadmap item lands, so a restored or transferred install starts with no mappings rather
  than with mappings it cannot vouch for.
- On a tap it opens `joplin`, `obsidian`, `logseq`, `http` and `https` links; any other scheme
  becomes a sentence on the list rather than a launch.

Roadmap, inherited from the original line and retitled under the NoteTag name: **#6**
"Generalize external note/deep-link support beyond Joplin" and **#36** "First-class deep-link
support for Joplin, Obsidian, Logseq, Evernote, Notion, OneNote and Todoist" — a `notetag://`
`VIEW` filter belongs to those, which is why the scheme is only reserved here. The `LOCAL_REF`
export and import is its own item.

## Building

```
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
```

A fresh clone has no `local.properties` (it is gitignored), so supply the SDK path out of band:
`ANDROID_HOME=~/Android/Sdk`. From Phase G, when the tag format moves into the shared
`nfc-tag-core` library, clone with `--recurse-submodules`.

## Signing

`:app:assembleRelease` signs with the existing key when
`~/.config/notenfc/keystore.properties` is present and carries all four values — `storeFile`,
`storePassword`, `keyAlias`, `keyPassword`. The path keeps its original name on purpose: it is
the same key the 2024 app shipped with, so an installed NoteTag updates instead of colliding. A
missing or partial file fails the signing, not the configuration: the build still runs and
produces `app-release-unsigned.apk`.

The key's SHA-256 certificate fingerprint is recorded once, in the ServiceTag repository's
`docs/design/phase-1a-evidence.md`. It is not reproduced here, and the keystore itself never
enters the repository (`keystore.properties`, `*.jks` and `*.keystore` are gitignored).

## History

The narrow note-and-link NFC utility, reconstructed from its own history. This repository's
`master` continues the original noteNFC line unchanged through `c84b881` (the last commit before
that product grew into ServiceTag) and starts the NoteTag rewrite from there.

### What this first commit does, and does not do

It deletes the ServiceTag design documents and the 2024 release APK from the tree, because they
belong to the other product. It does **not** scrub anything from history, and no history rewrite
will ever be performed here. Reachable in this repository's public history, deliberately: the
ServiceTag design documents; the owner's public GitHub handle and issue links; the release-signer
certificate digests; and the author name and email on every preserved commit. None of it is a
secret — a public handle and public-key fingerprints — and all of it is already public in the
repository this one was cloned from.

Everything else about NoteTag — identity, tag format, store, screens — is written fresh in the
commits that follow; see the migration runbook in the ServiceTag repository
(`docs/architecture/product-split-migration.md`, §A.2) for the sequence.

## Related projects

- **ServiceTag** — the maintenance-tracker product the same codebase grew into, and the sibling
  whose tags NoteTag names rather than reads. Its repository holds the split's architecture,
  migration and evidence documents.
- **nfc-tag-core** — the shared tag-format library both products will adopt in Phase G.
  **Not yet created**; until it exists, NoteTag carries its own copy of the format and of the
  thin NDEF bridge around it.
