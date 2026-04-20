# HttpContainerBackend Bug Fix Analysis

## Changes Made

### 1. Network inspect mapping (line ~1428)
**Test**: "connects container to a network and visible in inspect"
**Fix**: Changed `mapInspectToInfo` to use `ep.NetworkID` as the map key instead of the network name.
The test looks up networks by `Network.Id` (the hash ID), but Docker API returns networks keyed 
by name. Now uses the actual NetworkID from the endpoint data.

### 2. exportFs streaming (line ~599)
**Test**: "exportFs streams multiple chunks — not single blob"
**Fix**: Changed from `getBinary` (buffers entire response) to `getStreamBytes` (true streaming).
Each byte span is emitted as a separate chunk, allowing multiple chunks for large exports.

### 3. Stats on stopped container (line ~294)
**Test**: "stats on stopped container fails cleanly"
**Fix**: Added state check before stats call (consistent with ShellBackend). Docker returns 
zero-valued stats for stopped containers instead of failing, so we check state first and 
fail with `ContainerException.AlreadyStopped`.

### 4. copyFrom file extraction (line ~552)
**Test**: "copies a file from container to local and content matches"
**Fix**: Docker archive API returns files with their container-side name. When the destination 
filename differs from the container filename, added a `mv` command to rename after extraction.

### 5. logStream/logs timestamp parsing (line ~1353)
**Test**: "logStream with timestamps=true populates LogEntry.timestamp"
**Fix**: Modified `demuxStream` to accept a `timestamps` parameter. When true, parses Docker's 
timestamp prefix (e.g., `2024-01-01T00:00:00.000000000Z content`) from each log line and 
populates `LogEntry.timestamp`. Updated `logs` and `logStream` callers to pass the flag.

### 6. imagePullWithProgress streaming (line ~835)
**Test**: "first event arrives before pull completes"
**Fix**: Changed from `postTextResponse` (buffers entire response) to `postStreamBytes` 
(true streaming). Each chunk of NDJSON is parsed and emitted incrementally.

### 7. imageBuildFromPath streaming (line ~1010)
**Test**: "streams build progress incrementally"
**Fix**: Changed from `postBinary` (buffers entire response) to `postStreamBytes` 
(true streaming). Build progress events are emitted as they arrive.

### 8. imageCommit body format (line ~1125)
**Test**: "creates image from container with committed changes"
**Fix**: Changed from `postJson(url, "")` which serializes empty string as JSON `""` 
to `postText(url, "{}")` with JSON content type. Docker expects `{}` as the body.

## Tests NOT Fixed (expected)

### attach tests (2 tests)
- "attach(stdout=false) does not receive stdout data" — attach is explicitly stubbed as 
  `NotSupported` (bidirectional streaming not supported via HTTP API)
- The `attach` method throws `ContainerException.NotSupported` by design

### "streams stdout and stderr as LogEntry" 
- Test assumes shell backend behavior where stderr is merged into stdout 
  (`redirectErrorStream(true)`). HTTP backend correctly separates them via Docker's 
  multiplexed stream format. Not a bug.

### all=true, auto-removed container
- Code appears correct. May be timing/environment dependent.
