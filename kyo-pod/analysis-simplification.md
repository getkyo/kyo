# kyo-pod Simplification Audit

## Findings

### DEAD_CODE

```
[DEAD_CODE] HttpContainerBackend.scala:118-124 — splitImageRef is defined but never called
  CURRENT: private method splitImageRef that splits image reference into (name, tag)
  PROPOSED: Delete the method
  BENEFIT: Remove unused code
```

```
[DEAD_CODE] HttpContainerBackend.scala:2035-2052 — PullProgressDto, BuildProgressDto, AuxDto are unused DTOs
  CURRENT: Three case classes defined but never referenced as types anywhere
  PROPOSED: Delete all three DTOs
  BENEFIT: Remove ~20 lines of dead code
```

```
[DEAD_CODE] HttpContainerBackend.scala:2064 — EmptyResponse is unused
  CURRENT: private case class EmptyResponse() derives Json — never referenced
  PROPOSED: Delete it
  BENEFIT: Remove dead code
```

```
[DEAD_CODE] HttpContainerBackend.scala:1778-1780 — UpdateResponse is unused
  CURRENT: private case class UpdateResponse(Warnings: Seq[String]) derives Json — never referenced
  PROPOSED: Delete it
  BENEFIT: Remove dead code
```

### DUPLICATION — Cross-file

```
[DUPLICATION] HttpContainerBackend.scala:1695-1704 + ShellBackend.scala:310-319 — parseState is duplicated
  CURRENT: Both backends independently implement identical state string -> State mapping.
           HTTP version also handles "exited" but not "stopped"; Shell handles both "exited"|"stopped".
  PROPOSED: Move to ContainerBackend companion object as a shared utility, add "stopped" to HTTP version
  BENEFIT: Single source of truth, fixes missing "stopped" handling in HTTP backend
```

```
[DUPLICATION] HttpContainerBackend.scala:1408-1410 + ShellBackend.scala:1992-1995 — parseInstant is duplicated
  CURRENT: Identical implementations: null/empty/"0001-01-01T00:00:00Z" -> Absent, else Instant.parse
  PROPOSED: Move to ContainerBackend companion object
  BENEFIT: Eliminates identical method in both files
```

```
[DUPLICATION] HttpContainerBackend.scala:1347-1381 + ShellBackend.scala:1806-1841 — registryAuthFromConfig is near-identical
  CURRENT: Both backends have the same logic: search config paths, read JSON, parse auths map.
           Only difference is Seq(...) vs Chunk.from(Seq(...)) for the path list (functionally identical).
  PROPOSED: Move to ContainerBackend companion object as a shared implementation
  BENEFIT: Eliminate ~35 duplicated lines per backend
```

```
[DUPLICATION] HttpContainerBackend.scala:463-511 + ShellBackend.scala:641-691 — createAttachSession is near-identical
  CURRENT: Both backends spawn a CLI subprocess with PipedOutputStream/PipedInputStream,
           wrap it as AttachSession with identical write/read/resize implementations.
           Only difference: HttpContainerBackend uses `cliCommand`, ShellBackend uses `cmd`.
  PROPOSED: Move to ContainerBackend as a shared method parameterized by CLI command name
  BENEFIT: Eliminate ~50 lines of duplicated attach session plumbing
```

```
[DUPLICATION] HttpContainerBackend.scala:1553-1650 + ShellBackend.scala:282-307 — mapInspectToInfo has duplicated sub-logic
  CURRENT: Both backends independently parse ports, mounts, env vars, network endpoints from inspect DTOs.
           The mapping logic (protocol string -> Protocol enum, mount type -> Mount ADT,
           "KEY=VALUE" -> Map, health status parsing) is structurally identical.
  PROPOSED: Extract shared mapping helpers: parseProtocol(String), parseMountDto(...), parseEnvStrings(Seq),
           parseHealthStatus(Option[String])
  BENEFIT: Each helper is 3-8 lines; together they reduce ~40 lines of parallel logic
```

### DUPLICATION — Within file

```
[DUPLICATION] ShellBackend.scala:750-766 + 768-778 — parseLogLines and parseLogLine duplicate logic
  CURRENT: parseLogLines splits text into lines, then applies the same timestamp-parsing logic
           that parseLogLine implements. parseLogLine is used in logStream, parseLogLines in logs.
  PROPOSED: Delete parseLogLines, implement logs() using parseLogLine:
           Chunk.from(raw.split("\n").filter(_.nonEmpty).map(line => parseLogLine(line, source, hasTimestamps)))
  BENEFIT: Remove 17 duplicated lines
```

```
[DUPLICATION] ShellBackend.scala:25-29 + HttpContainerBackend.scala:163-167 — RestartPolicy -> string mapping
  CURRENT: Both backends convert RestartPolicy ADT to string ("no", "always", "unless-stopped",
           "on-failure:N"). HTTP backend also does it in update() (line 753-758), totaling 3 copies.
  PROPOSED: Add a `cliName` method to RestartPolicy (like NetworkDriver.cliName already exists)
  BENEFIT: Eliminate 3 independent copies of the same match expression
```

```
[DUPLICATION] ShellBackend.scala:59-68 + HttpContainerBackend.scala:131-138 — Protocol -> string mapping
  CURRENT: Both backends convert Protocol enum to "tcp"/"udp"/"sctp" string independently,
           in multiple places (create, list, inspect).
  PROPOSED: Add a `cliName` method to Config.Protocol
  BENEFIT: Single definition used everywhere
```

### SIMPLIFICATION

```
[SIMPLIFICATION] HttpContainerBackend.scala:857-880 — imagePull uses CLI subprocess instead of HTTP API
  CURRENT: The HTTP backend's imagePull spawns a CLI subprocess (cliCommand + "pull") instead of
           using the Docker HTTP API POST /images/create. This is inconsistent with the backend's
           design and duplicates the Shell backend's approach.
  PROPOSED: Implement via HTTP API: POST /images/create?fromImage=...&tag=... with auth headers.
           The CLI fallback approach already exists in imagePullWithProgress where it makes sense
           (for streaming progress lines).
  BENEFIT: Consistent with the HTTP backend pattern; removes CLI dependency from HTTP backend
```

### NOT PROPOSED (would be over-abstraction)

- The exec command-building pattern (`Chunk("exec") ++ env ++ workDir ++ args`) appears in exec, execStream, execInteractive in both backends. However, the HTTP backend's exec() uses HTTP API (not CLI args), so only the stream/interactive variants share this. Extracting a helper would save ~6 lines per backend but add indirection for only 2-3 call sites each.

- The `Abort.runWith[CommandException](...) { case Result.Success/Failure/Panic }` pattern in ShellBackend repeats ~15 times. This is idiomatic Kyo error handling, not a code smell. Abstracting it would obscure intent.

## Summary

| Type | Count | Est. Lines Saved |
|------|-------|-----------------|
| DEAD_CODE | 5 | ~30 |
| DUPLICATION (cross-file) | 5 | ~180 |
| DUPLICATION (within-file) | 3 | ~30 |
| SIMPLIFICATION | 1 | ~15 |
| **Total** | **14** | **~255** |

Highest-impact changes: `createAttachSession` extraction, `registryAuthFromConfig` extraction, and adding `cliName` to `RestartPolicy`/`Protocol`.
