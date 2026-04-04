# Phase 3: Inspection, Stats, Exec, and Logs — Analysis

## Methods to implement (12 total)

### Inspection (7 methods)
1. **inspect(id)** — GET /containers/{id}/json → Container.Info
2. **state(id)** — GET /containers/{id}/json → extract State.Status → Container.State
3. **isHealthy(id)** — derived from state(), return state == Running
4. **stats(id)** — GET /containers/{id}/stats?stream=false → Container.Stats
5. **statsStream(id, interval)** — GET /containers/{id}/stats?stream=true → Stream[Stats] via NDJSON
6. **top(id, psArgs)** — GET /containers/{id}/top?ps_args={args} → TopResult
7. **changes(id)** — GET /containers/{id}/changes → Chunk[FilesystemChange]

### Exec (3 methods)
8. **exec(id, command)** — POST exec create + POST exec start + GET exec inspect → ExecResult
9. **execStream(id, command)** — same but stream output as Stream[LogEntry]
10. **execInteractive(id, command)** — NotSupported (like ShellBackend needs bidirectional)

### Logs (2 methods)
11. **logs(id, ...)** — GET /containers/{id}/logs → Chunk[LogEntry], demux multiplexed stream
12. **logStream(id, ...)** — GET /containers/{id}/logs?follow=true → Stream[LogEntry]

## DTOs needed

### For inspect
- InspectResponse — mirrors Docker inspect JSON (Id, Name, State, Config, etc.)
- InspectStateDto, InspectConfigDto, InspectNetworkSettingsDto, InspectMountDto, InspectPortMappingDto, InspectNetworkEndpointDto, InspectHealthDto

### For stats
- StatsResponse — raw Docker stats JSON (cpu_stats, precpu_stats, memory_stats, networks, blkio_stats, pids_stats)
- CpuStatsDto, CpuUsageDto, MemoryStatsDto, NetworkStatsDto, BlkioStatsDto, PidsStatsDto

### For top
- TopResponse — Titles + Processes

### For changes
- ChangeEntry — Path + Kind

### For exec
- ExecCreateRequest — Cmd, AttachStdout, AttachStderr
- ExecCreateResponse — Id
- ExecStartRequest — Detach
- ExecInspectResponse — ExitCode

## Multiplexed stream helper
- demuxStream(bytes: Chunk[Byte]): Chunk[LogEntry] — parse 8-byte header frames
- For logs, use getBinary to get raw bytes, then demux
- For logStream, need streaming binary — will use getText approach with NDJSON workaround or getBinary

## Plan
1. Add DTO case classes for inspect, stats, top, changes, exec
2. Implement demuxStream helper
3. Implement inspect → mapInspectToInfo
4. Implement state, isHealthy (simple derivations)
5. Implement stats, statsStream 
6. Implement top, changes
7. Implement exec, execStream, execInteractive
8. Implement logs, logStream
9. Compile after each group
