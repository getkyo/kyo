# Followup Progress Tracker

14 phases, all initialised to `pending`. Update Status / Commit / Notes columns as work proceeds. See [execution-plan.md](./execution-plan.md) for each phase's spec and [analysis.md](./analysis.md) for the dependency DAG.

| # | Phase                                                                  | Status  | Commit | Notes |
|---|------------------------------------------------------------------------|---------|--------|-------|
| 1 | Strip Phase X references from production code (Item 4)                 | pending |        |       |
| 2 | Remove cast/Unsafe/@unchecked comments + drive-bys (Items 7+13)        | pending |        |       |
| 3 | Remove redundant trailing `()` after `discard` (Item 8)                | pending |        |       |
| 4 | TagMacro narrow TypeBounds peel to FromJavaObject (Items 1 + 17)       | pending |        | Item 17 leaves appended to existing TagTest.scala |
| 5 | Internal `Map[Int, String]` -> `Dict[Int, String]` (Item 11)           | pending |        |       |
| 6 | Givens take `using Frame` instead of `Frame.internal` (Item 9)         | pending |        |       |
| 7 | `.transform` delegation for arraySchema / arraySeqSchema / queueSchema / sortedSetSchema (Item 10) | pending |        |       |
| 8 | Delete KeyCodec, route Map[K, V] via Dict (Item 5)                     | pending |        |       |
| 9 | PlatformSchemas per-type cross-platform verification (Item 2)          | pending |        |       |
| 10| UnionMacro deduplicates SerializationMacro infrastructure (Item 14)    | pending |        |       |
| 11| Eliminate hardcoded type lists and the platform-symbol hook (Items 12 + 3) | pending |        | Absorbs old Phase 12 (PlatformSymbols deletion) |
| 12| Delete drift-guard infrastructure (Item 16)                            | pending |        |       |
| 13| Intersection type support `A & B` (Item 6)                             | pending |        | IntersectionMacro reuses SerializationMacro.caseClassWriteBody / caseClassReadBodyResolved |
| 14| Test renaming / folding (Item 15)                                      | pending |        |       |
