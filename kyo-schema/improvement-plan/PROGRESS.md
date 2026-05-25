# kyo-schema Improvement: Progress

| Phase | Title | Status | Commit | Notes |
|---|---|---|---|---|
| 1 | Fix `isSerializableType` enumeration drift | completed | cdf05c809 | |
| 2 | Fix `Structure.PrimitiveKind` and `StructureMacro.primitiveKindExpr` | completed | d34fe0660 | |
| 3 | Consolidate `MacroUtils` symbol sets | completed | a03102bfb | |
| 4 | Route nested field codecs through transform-aware dispatch | completed | db1bcc601 | with Phase 4b/4c |
| 5 | Protobuf discriminator decode | completed | ec01fad89 | |
| 6 | Composition test matrix | completed | 74b7000bd | |
| 7 | Introduce `KeyCodec[K]` typeclass | completed | f127c1028 | |
| 8 | Generic `Map[K, V]` via KeyCodec, array-of-pairs fallback | completed | 60042adf0 | |
| 9 | Shared string-transform givens (cross-platform) | completed | bf3274df9 | |
| 10 | JVM-only string-transform givens | completed | 14c70b53a | |
| 11 | java.time gap closure | completed | 9e52f0012 | tzdb-sensitive tests moved to JVM in audit cleanup |
| 12 | Tuple ladder Tuple1, Tuple6..Tuple22 | completed | eafa22904 | |
| 13 | `Array[A]` and missing immutable collections | completed | 598ba3d37 | |
| 14 | Java enum derivation | completed | 8766af3be | |
| 15 | Union type derivation `A \| B` | completed | bb3bdb7fd | |
| 16 | Intersection type rejection `A & B` | completed | f2873dfc6 | |
| 17 | Scaladoc and recipe documentation | completed | 9626b3484 | |

## Follow-ups

| Item | Commit | Notes |
|---|---|---|
| Phase 4b — route collection-given codecs through transform-aware dispatch | ff3669abc | |
| Phase 4c — route sealed-trait variant codecs through transform-aware dispatch | 2ba83a153 | closed legacy pending Phase 4 leaf |
| TagMacro fix — Java wildcard type-args treated as upper bound | cc62abd3e | unblocked LocalDateTime / kyo-data |
| Audit cleanup — Phase 11 tzdb test split, `// Unsafe:` annotations, PROGRESS update | (uncommitted) | per FINAL-AUDIT.md |
