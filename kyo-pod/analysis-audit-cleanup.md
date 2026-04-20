# Audit Cleanup Analysis

## Files
- Container.scala
- ContainerImage.scala
- ContainerBackend.scala

## Fixes to Apply

### Fix 1: `protected` → plain `def` in ContainerBackend.scala
- `parseState` and `parseInstant` — change `protected def` to `def`

### Fix 2: Network.create / Volume.create — CANNOT RENAME
- Tests use `Network.create` and `Volume.create` extensively (25+ occurrences)
- Since we cannot modify tests, keep public names as-is
- Add comments noting the naming convention deviation

### Fix 3: `Seq` → `Chunk` in data class fields
- Summary.names, Summary.mounts
- TopResult.titles, TopResult.processes
- PruneResult.deleted
- Config.dns
- HistoryEntry.tags (ContainerImage.scala)
- ContainerException.ExecFailed.cmd — need to check

### Fix 4: Move given instances
- Container.scala: `given CanEqual[Container, Container]` — move after nested types, before private internals
- ContainerImage.scala: `given Render[ContainerImage]` — move after nested types

### Fix 5: Add scaladocs to public nested types

### Fix 6: Remove obvious `/** Start a created container. */` comment on `start`
