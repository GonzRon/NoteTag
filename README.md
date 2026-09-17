# NoteTag

The narrow note-and-link NFC utility, reconstructed from its own history. This repository's
`master` continues the original noteNFC line unchanged through `c84b881` (the last commit before
that product grew into ServiceTag) and starts the NoteTag rewrite from there.

## What this first commit does, and does not do

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
