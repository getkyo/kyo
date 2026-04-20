# Analysis: Backend Configuration Tests for kyo-pod

## Goal
Add 10 comprehensive tests for connection/backend configurations in the existing "Backend" section of ContainerTest.scala.

## Current State
- `ContainerTest.scala` has a "Backend" section (lines 262-305) with 4 tests:
  1. auto-detect creates a working backend
  2. withBackend(_.UnixSocket) uses http backend
  3. withBackend(_.Shell) uses shell backend
  4. auto-detect aborts with BackendUnavailable when nothing works

## Tests to Add (inserted before closing `}` at line 305)

1. **auto-detect selects a working backend** - explicit AutoDetect() config
2. **auto-detect passes meter to backend** - AutoDetect with Meter, 4 concurrent execs
3. **Shell with explicit command path** - Shell(cmd) where cmd is "docker"/"podman"
4. **Shell with nonexistent command fails with BackendUnavailable** - Shell("nonexistent-runtime-xyz")
5. **UnixSocket with valid docker socket** - tagged httpBackendOnly, skips if no socket
6. **UnixSocket with nonexistent path fails with BackendUnavailable** - Path("/tmp/nonexistent-socket-xyz.sock")
7. **Meter limits concurrent operations** - Semaphore(2), 6 concurrent execs, verify max<=2
8. **nested withBackend overrides outer backend** - Shell wrapping UnixSocket
9. **connection refused to valid-looking socket gives clear error** - fake file as socket
10. **auto-detect prefers HTTP when socket is available** - tagged httpBackendOnly

## Key Patterns
- `in run { ... }` for test body
- `taggedAs httpBackendOnly` for HTTP-only tests
- `Container.withBackend(_.XYZ(..)) { ... }` for backend selection
- `Abort.run[ContainerException] { ... }.map { result => ... }` for error tests
- `ContainerRuntime.findSocket(runtime)` to detect available sockets
- Use `Fiber.init` for concurrent operations (not `Fiber.init(v)` — check API)

## Approach
- Add all 10 tests inside the existing "Backend" - { ... } block
- Insert before the closing `}` of the Backend section (line 305)
- Small edits, one or two tests at a time
