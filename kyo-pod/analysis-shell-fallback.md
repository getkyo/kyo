# Analysis: Restore ShellBackend as fallback + smart socket detection

## Current State
- `BackendConfig` enum has `AutoDetect` and `UnixSocket(path)` — missing `Shell(command)`
- `ContainerBackend.detect()` only tries HTTP socket detection
- `HttpContainerBackend.detect()` checks static paths but doesn't try CLI commands to find sockets
- `ContainerTest.run` only resolves sockets statically, falls back to nothing
- `Test.scala` only checks socket existence, not CLI availability

## Changes Required

### 1. Container.scala
- Add `case Shell(command: String)` to `BackendConfig` enum
- Add `Shell` case to `resolveBackend`
- Add `ShellBackend` import

### 2. ContainerBackend.scala
- Update `detect()` to try HTTP first, fall back to ShellBackend
- Add `detectShell()` private method

### 3. HttpContainerBackend.scala
- Add CLI-based socket discovery after static path checks
- `docker context inspect --format '{{.Endpoints.docker.Host}}'`
- `podman info --format '{{.Host.RemoteSocket.Path}}'`

### 4. ContainerTest.scala
- Update `run` to try socket detection, fall back to `Shell(runtime)`
- Add `findSocket` helper

### 5. Test.scala
- Add `cliExists` helper
- Update `hasPodman` / `hasDocker` to also check CLI availability
