# Phase 19b Decisions

## Serialization layout chosen

**PARENTS section**: Each symbol's parents are serialized as a list of symbol IDs. Only `Tasty.Type.Named(sym)` parents are serializable; all other parent types (complex types like `Applied`, `AndType`, etc.) are encoded as -1 and filtered out before writing. This means external parents (e.g. `java.lang.Object` from classfiles not in the snapshot) produce an empty or reduced list. The section is non-empty whenever any symbol has at least one `Named` parent that is also in the snapshot's symbol table.

**MEMBERS section**: Each symbol's `_declarations` entries are stored as symbol IDs (index into the snapshot's symbol list). All declaration symbols are always in the same snapshot, so the full list round-trips.

**TPARAMS_ section**: Each symbol's `_typeParams` entries are stored as symbol IDs. Same locality guarantee as MEMBERS.

**Binary layout** (all three sections share the same format via `serializeSymbolRelLists`): `[4-byte count][entries...]` where each entry is `[4-byte symIdx][4-byte refCount][refCount x 4-byte refIds]`. Entries with an empty filtered ref list are omitted; the reader falls back to `Chunk.empty` for symbols not present in the section.

## Stub removal scope

`stub("Symbol.body")` at `Tasty.scala:709` removed. The defensive guard checked `home.isAssigned` before calling `home.get().checkOpen`. This guard is unnecessary because `home.isAssigned` is invariant-true after `Classpath.open` returns: `assignHomes` in `ClasspathOrchestrator` assigns every symbol's `ClasspathRef` before transitioning to Ready state, and snapshot-loaded symbols have their refs assigned by the caller after `SnapshotReader.read` returns. The `stub` helper itself was also removed since no remaining call sites exist.

## Minor=2 forward-compat verification

Old snapshots at `minorVersion=2` (major=1) do not contain PARENTS, MEMBERS, or TPARAMS_ sections. The reader handles absence with `case _ => ()` followed by a `for sym <- allSymbols do if !sym._parents.isSet then sym._parents.set(Chunk.empty)` loop, which replicates the prior behavior. The `SnapshotReaderTest` verifies this path by constructing a synthetic minor=2 snapshot and confirming no `SnapshotVersionMismatch` is raised.

## jvmOnly tags applied

No jvmOnly tags were needed. The `deserializeRefLists` helper uses only `Array[Byte]` and `SnapshotFormat.readInt32LE` (pure shared code). The `deserializeMapped` path uses `copyViewRange` (also shared) to materialize section bytes before calling the shared helper. All four tests are in `shared/` and run on JVM, JS, and Native.
