# Phase 19a Decisions

## Section-name array index choice

`"TPARAMS_"` is inserted at index 6, between `"MEMBERS"` (index 5) and `"FILES"` (index 7). This matches the plan ordering and the logical grouping: TPARAMS_ belongs with per-symbol structural data (PARENTS, MEMBERS) before file-level metadata (FILES). The array is not order-sensitive at runtime; SnapshotWriter and SnapshotReader both use named lookup via `sectionMap.get(name)`. The index position in `sectionNames` is used only as documentation and for iteration order when writing, so placement between MEMBERS and FILES is purely conventional.

## Snapshot-reader compatibility verdict

The reader is fully forward-compatible with older minor versions:

- Both `deserialize` and `deserializeMapped` build a `sectionMap` keyed by section name. They only look up sections they know about by name. An unknown section name present in an older file is simply absent from the code-side lookups and silently ignored.
- The reader checks only `fileMajor != SnapshotFormat.majorVersion` (hard rejection). Minor version is read into `fileMinor` but is NOT compared against `SnapshotFormat.minorVersion` in any branch. Files written with minor=2 are accepted by a minor=3 reader; the TPARAMS_ section is absent from such files and treated as empty by the reader (the `case None` arm returns `Chunk.empty` / `Array.empty`).
- Conversely, a minor=2 reader encountering a minor=3 file will also succeed: the TPARAMS_ section entry appears in the section index but its name matches no lookup key in the old reader, so it is harmlessly skipped.

Verdict: no fixture bumps required. INV-003 (minor bump is add-only, readers skip unknown sections) holds.

## Fixture bump assessment

No existing test fixture snapshot bytes in source encode a minor version byte. `SnapshotRoundTripTest` writes and reads dynamically at test time using `SnapshotWriter.serialize` (which picks up the new `minorVersion = 3` at write time) and `SnapshotReader.read` (which accepts any major=1 file regardless of minor). No static byte arrays containing `minorVersion = 2` exist in the test corpus. No fixture changes are needed.
