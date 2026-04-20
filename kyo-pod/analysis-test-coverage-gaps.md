# kyo-pod Test Coverage Audit

## Summary

The test suite (`ContainerTest.scala`, ~2793 lines) has **strong coverage** of the happy paths for container lifecycle, exec, logs, file ops, networks, volumes, and images. However, there are significant gaps in:
- `ContainerImage.parse` (zero test coverage)
- `initUnscoped` (zero test coverage)
- Backend utility/parsing functions (parseState, parseInstant, parseSizeString, etc.)
- Several ContainerException subtypes never triggered in tests
- Config edge cases around conflicting options
- Resource cleanup under interruption/cancellation scenarios

---

## Proposed Test Plan

### A. ContainerImage.parse (ZERO COVERAGE -- Critical Gap)

```
1. [PARSE] "ContainerImage.parse — simple name defaults to tag latest"
   TESTS: parse("alpine") => name=alpine, tag=Present("latest"), registry=Absent
   POTENTIAL BUG: Currently used internally in Container.Config.apply(String) and throughout backend code. Zero direct tests means regressions are invisible.
   PRIORITY: high

2. [PARSE] "ContainerImage.parse — name:tag"
   TESTS: parse("alpine:3.19") => name=alpine, tag=Present("3.19"), digest=Absent
   PRIORITY: high

3. [PARSE] "ContainerImage.parse — name with digest"
   TESTS: parse("myapp@sha256:abc123") => name=myapp, digest=Present("sha256:abc123"), tag=Absent
   POTENTIAL BUG: Digest.apply prepends "sha256:" if not present. parse passes raw digest string to Digest(). If input is "myapp@sha256:abc123", the digest becomes "sha256:sha256:abc123" -- double prefix bug.
   PRIORITY: high

4. [PARSE] "ContainerImage.parse — registry with port"
   TESTS: parse("localhost:5000/myapp:v1") => registry=Present("localhost:5000"), name=myapp, tag=Present("v1")
   POTENTIAL BUG: The colon in "localhost:5000" could be confused with a tag separator. The parser checks for "/" after the colon to distinguish, but edge cases around the logic need validation.
   PRIORITY: high

5. [PARSE] "ContainerImage.parse — registry/namespace/name:tag"
   TESTS: parse("ghcr.io/owner/repo:v1.2.3") => registry=Present("ghcr.io"), namespace=Present("owner"), name=repo, tag=Present("v1.2.3")
   PRIORITY: high

6. [PARSE] "ContainerImage.parse — deeply nested namespace a/b/c/d/image"
   TESTS: parse("registry.io/a/b/c/d/image:v1") => registry, namespace, and name are split correctly
   POTENTIAL BUG: The parser handles Seq(first, ns, more*) by joining `more` with "/". For a/b/c/d/image without a registry-like first segment, it may misparse.
   PRIORITY: medium

7. [PARSE] "ContainerImage.parse — empty string fails"
   TESTS: parse("") => Result.fail("Empty image reference")
   PRIORITY: high

8. [PARSE] "ContainerImage.parse — just a tag ':latest'"
   TESTS: parse(":latest") => should fail or produce unexpected result
   POTENTIAL BUG: This would split as beforeTag="" and tagPart=Present("latest"), then parts=Seq("") which becomes nameVal="". An image with empty name is nonsensical but not rejected.
   PRIORITY: medium

9. [PARSE] "ContainerImage.parse — digest only '@sha256:abc'"
   TESTS: parse("@sha256:abc") => beforeDigest="" gives parts=Seq("") => nameVal="" 
   POTENTIAL BUG: Empty name accepted without validation.
   PRIORITY: medium

10. [PARSE] "ContainerImage.parse — namespace without registry (library/alpine)"
    TESTS: parse("library/alpine") => namespace=Present("library"), name=alpine, tag=Present("latest")
    PRIORITY: medium

11. [PARSE] "ContainerImage.parse — Docker Hub predefined image reference roundtrip"
    TESTS: ContainerImage.Alpine.reference => parse back => identical structure
    PRIORITY: medium

12. [PARSE] "ContainerImage.withTag clears digest and vice versa"
    TESTS: image.withDigest(d).withTag("v1") => digest=Absent, tag=Present("v1")
    PRIORITY: medium
```

### B. Container.initUnscoped (ZERO COVERAGE)

```
13. [API] "initUnscoped creates and starts container without scope cleanup"
    TESTS: Container.initUnscoped(config) creates a running container; no auto-cleanup on scope close
    POTENTIAL BUG: initUnscoped does not register Scope.ensure, so the container leaks if caller forgets to stop/remove. The test verifies the container persists after scope exits.
    PRIORITY: high

14. [API] "initUnscoped — caller must manually stop and remove"
    TESTS: After initUnscoped, container remains after enclosing scope closes; explicit stop+remove works
    PRIORITY: high

15. [API] "initUnscoped runs health check"
    TESTS: initUnscoped with a health check runs it and waits
    POTENTIAL BUG: Looking at the code, initUnscoped calls runHealthCheck but if the health check fails, does the container get cleaned up? It doesn't have Scope.ensure, so a failing health check leaves a dangling container.
    PRIORITY: high
```

### C. ContainerException Subtypes — Untested Error Paths

```
16. [ERROR] "AlreadyRunning error when starting already-running container"
    TESTS: The AlreadyRunning exception type exists but is never triggered in tests. The start test uses postUnitAccept304 which silently accepts 304. Does any code path actually produce AlreadyRunning?
    POTENTIAL BUG: The AlreadyRunning exception is defined but appears unreachable -- start() uses postUnitAccept304 (HTTP) or runUnit (shell) which both accept the already-running case. This is dead code.
    PRIORITY: low

17. [ERROR] "StartFailed error"
    TESTS: StartFailed is defined but never produced by any backend code path
    POTENTIAL BUG: Dead exception subtype -- no code path creates it.
    PRIORITY: low

18. [ERROR] "Timeout exception triggered by long operation"
    TESTS: The Timeout exception exists but no code path creates it. ContainerException.Timeout is never constructed anywhere.
    POTENTIAL BUG: Dead exception subtype. Actual timeouts come from kyo's Async.timeout, which raises kyo.Timeout, not ContainerException.Timeout.
    PRIORITY: low

19. [ERROR] "PortConflict error when binding to already-used port"
    TESTS: PortConflict exists but is never constructed. Port conflicts surface as General errors.
    POTENTIAL BUG: The HTTP backend maps 409 to AlreadyExists, not PortConflict. Port conflicts from Docker would be a 500 error, mapped to General.
    PRIORITY: low

20. [ERROR] "VolumeInUse error surfaced correctly"
    TESTS: The VolumeInUse exception exists. The test "remove volume in use by stopped container fails" asserts failure but doesn't check the specific exception type.
    POTENTIAL BUG: The Shell backend would return a General error for volume-in-use, not VolumeInUse. The test should verify the exception type.
    PRIORITY: medium

21. [ERROR] "AuthenticationError triggered by push without credentials"
    TESTS: AuthenticationError exists but no test triggers it. No code path constructs it.
    POTENTIAL BUG: Dead exception subtype.
    PRIORITY: low

22. [ERROR] "ParseError triggered by malformed JSON from Docker"
    TESTS: ParseError exists and is used in stat(). No test triggers it with genuinely malformed data.
    PRIORITY: medium
```

### D. Backend Utility Functions (ZERO COVERAGE)

```
23. [BACKEND] "parseState maps all known Docker states"
    TESTS: Unit test parseState with "created", "running", "paused", "restarting", "removing", "exited", "stopped", "dead", and unknown strings
    POTENTIAL BUG: "stopped" maps to Stopped, but Docker API returns "exited". The fallback for unknown states silently returns Stopped, which could mask real issues.
    PRIORITY: medium

24. [BACKEND] "parseInstant handles None, empty string, and zero-value timestamp"
    TESTS: parseInstant(None), parseInstant(Some("")), parseInstant(Some("0001-01-01T00:00:00Z")) all return Absent
    PRIORITY: medium

25. [BACKEND] "parseSizeString handles all Docker size formats"
    TESTS: Unit test parseSizeString with GiB, MiB, KiB, GB, MB, kB, B, and plain numbers
    POTENTIAL BUG: ShellBackend.parseSizeString is private but critical for stats parsing. "3.5GiB" should return correct byte count.
    PRIORITY: medium

26. [BACKEND] "parsePercent handles edge cases"
    TESTS: parsePercent("0.50%"), parsePercent(""), parsePercent("--")
    PRIORITY: low

27. [BACKEND] "parseSlashPair with missing / malformed input"
    TESTS: parseSlashPair("100MiB / 1GiB"), parseSlashPair(""), parseSlashPair("no-slash-here")
    PRIORITY: low
```

### E. Config Edge Cases

```
28. [CONFIG] "autoRemove(true) with restartPolicy(Always) — conflicting options"
    TESTS: Docker rejects this combination. The test should verify that init fails with a clear error rather than hanging or producing a confusing message.
    POTENTIAL BUG: Docker API returns a 409 or 500 error for this, which gets mapped to AlreadyExists or General. The error message would be confusing.
    PRIORITY: medium

29. [CONFIG] "Config with port 0 for container port"
    TESTS: port(0, 8080) -- is a container port of 0 valid? Docker would reject it.
    PRIORITY: low

30. [CONFIG] "Config with negative memory limit"
    TESTS: Container.Config("alpine").memory(-1) -- Docker rejects negative values. Verify clear error.
    PRIORITY: low

31. [CONFIG] "Config with empty command"
    TESTS: Container.Config("alpine").command() -- empty args. Does Command("") crash?
    PRIORITY: medium

32. [CONFIG] "Config stopTimeout is used during scope cleanup"
    TESTS: Verify that Config.stopTimeout(10.seconds) actually waits 10s before force-kill on scope close. Currently only tested with 0.seconds.
    PRIORITY: medium

33. [CONFIG] "Config.hostname is reflected in container"
    TESTS: Container with .hostname("myhost"), verify via exec("hostname")
    PRIORITY: medium

34. [CONFIG] "Config.user sets container user"
    TESTS: Container with .user("nobody"), verify via exec("whoami")
    PRIORITY: medium

35. [CONFIG] "Config with memorySwap but no memory"
    TESTS: memorySwap without memory -- Docker requires memory to be set first. 
    POTENTIAL BUG: Docker silently ignores memorySwap if memory is not set. No validation in kyo-pod.
    PRIORITY: low
```

### F. Container.use Error Propagation

```
36. [API] "use propagates non-ContainerException errors from block"
    TESTS: Container.use(config) { c => throw new RuntimeException("boom") } -- does the container get cleaned up? Does the error propagate?
    POTENTIAL BUG: use calls Scope.run(init(config).map(f)). If f throws a non-Abort exception (Panic), Scope.run should still clean up, but this isn't tested.
    PRIORITY: medium

37. [API] "use with block that runs forever — fiber interruption triggers cleanup"
    TESTS: Fiber.init(Container.use(config) { c => Async.sleep(Duration.Infinity) }); fiber.interrupt; verify container is removed
    POTENTIAL BUG: Interruption during Async.sleep should trigger Scope.ensure, but this isn't tested.
    PRIORITY: high
```

### G. Stats on Paused Container

```
38. [EDGE] "stats on paused container returns valid data"
    TESTS: Pause a container, then call stats. Docker returns stats for paused containers (they still consume memory). The current code checks for Stopped/Dead but not Paused.
    POTENTIAL BUG: Paused containers should return valid stats since they still hold resources. This tests that the guard in stats() doesn't accidentally reject paused containers.
    PRIORITY: medium
```

### H. Log Edge Cases

```
39. [LOGS] "logs with since in the future returns empty"
    TESTS: c.logs(since = Instant.Max) -- should return empty since no logs are from the future
    PRIORITY: medium

40. [LOGS] "logs with until in the past returns empty"
    TESTS: c.logs(until = Instant.Epoch) -- should return empty
    PRIORITY: medium

41. [LOGS] "logStream on stopped container terminates"
    TESTS: Stop container, then try logStream -- should terminate cleanly without hanging
    POTENTIAL BUG: The shell backend spawns "docker logs --follow" which would exit when container stops, but the HTTP backend uses streaming GET which may hang.
    PRIORITY: medium
```

### I. Exec Edge Cases

```
42. [EXEC] "exec with command not found in container (exit code 127)"
    TESTS: c.exec("nonexistent-command-xyz") -- should get ExecFailed with exit code 127
    POTENTIAL BUG: ShellBackend explicitly checks exit code 127 and maps to ExecFailed. HttpContainerBackend returns ExecResult with exitCode=127 without raising ExecFailed. Inconsistent behavior between backends.
    PRIORITY: high

43. [EXEC] "exec with permission denied (exit code 126)"
    TESTS: Create a file in container, chmod 000, try to exec it -- exit code 126
    POTENTIAL BUG: Same inconsistency as above -- ShellBackend maps 126 to ExecFailed but HttpContainerBackend doesn't.
    PRIORITY: high

44. [EXEC] "execStream handles command not found"
    TESTS: execStream with nonexistent command -- should emit error or terminate
    PRIORITY: medium

45. [EXEC] "exec with binary output (null bytes in stdout)"
    TESTS: c.exec("dd", "if=/dev/zero", "bs=10", "count=1") -- stdout contains null bytes
    POTENTIAL BUG: stdout/stderr are decoded as UTF-8 strings. Null bytes in binary output may get corrupted.
    PRIORITY: low
```

### J. Network Operations Edge Cases

```
46. [NETWORK] "container connected to multiple networks — inspect shows all"
    TESTS: Connect container to 2 custom networks, verify inspect.networkSettings.networks has both
    PRIORITY: medium

47. [NETWORK] "network aliases work for DNS resolution"
    TESTS: Connect with alias "myservice", verify other container can resolve "myservice"
    PRIORITY: medium

48. [NETWORK] "disconnect from default bridge network"
    TESTS: Disconnect container from the default bridge -- should this fail or succeed?
    POTENTIAL BUG: Disconnecting from the only network may make the container unreachable.
    PRIORITY: low

49. [NETWORK] "Network.create with IPAM config (subnet/gateway)"
    TESTS: Create network with specific subnet, verify via inspect
    PRIORITY: medium
```

### K. Volume Edge Cases

```
50. [VOLUME] "Volume.create with driverOpts"
    TESTS: Create volume with driverOpts, verify options in inspect
    PRIORITY: low

51. [VOLUME] "Volume.remove force=true on in-use volume"
    TESTS: Remove a volume with force while a stopped container references it
    PRIORITY: medium

52. [VOLUME] "Volume.list with filters"
    TESTS: Create multiple volumes with labels, filter by label
    PRIORITY: low
```

### L. Image Operations Edge Cases

```
53. [IMAGE] "ContainerImage.ensure skips pull when image exists locally"
    TESTS: Verify ensure is fast (no network call) when image exists. The test "imagePull actually contacts registry when image exists" does this partially but could be more explicit.
    PRIORITY: low

54. [IMAGE] "ContainerImage.remove with noPrune=true"
    TESTS: Remove an image with noPrune=true, verify intermediate layers are preserved
    PRIORITY: low

55. [IMAGE] "ContainerImage.list with all=true includes intermediate layers"
    TESTS: Build image, list with all=true, verify intermediate layers appear
    PRIORITY: low

56. [IMAGE] "ContainerImage.build with buildArgs"
    TESTS: Build image with buildArgs, verify ARG is available in Dockerfile
    PRIORITY: medium

57. [IMAGE] "ContainerImage.commit with comment and author"
    TESTS: Commit with comment="test commit" and author="kyo", verify in image inspect
    PRIORITY: low
```

### M. Platform/Digest/Registry Opaque Types

```
58. [TYPES] "Platform.parse with 2 parts"
    TESTS: Platform.parse("linux/amd64") => Platform("linux", "amd64", Absent)
    PRIORITY: low

59. [TYPES] "Platform.parse with 3 parts (variant)"
    TESTS: Platform.parse("linux/arm/v7") => Platform("linux", "arm", Present("v7"))
    PRIORITY: low

60. [TYPES] "Platform.parse with invalid format"
    TESTS: Platform.parse("invalid") => Result.fail
    PRIORITY: low

61. [TYPES] "Digest.apply auto-prefixes sha256:"
    TESTS: Digest("abc123").value == "sha256:abc123"; Digest("sha256:abc123").value == "sha256:abc123"
    PRIORITY: medium

62. [TYPES] "Registry.apply with host and port"
    TESTS: Registry("myhost", 5000).value == "myhost:5000"
    PRIORITY: low
```

### N. HealthCheck Edge Cases

```
63. [HEALTH] "HealthCheck.httpGet passes when endpoint returns 200"
    TESTS: Start nginx or similar, use httpGet check
    PRIORITY: low (already tested indirectly with port check)

64. [HEALTH] "HealthCheck with schedule that takes 0 retries"
    TESTS: Schedule.fixed(100.millis).take(0) -- what happens when schedule is empty?
    POTENTIAL BUG: Retry with an empty schedule may behave unexpectedly -- could succeed immediately or fail immediately.
    PRIORITY: medium

65. [HEALTH] "HealthCheck.all with zero checks"
    TESTS: HealthCheck.all() -- empty composite check
    POTENTIAL BUG: all() creates a HealthCheck with defaultSchedule but no checks. check() calls Kyo.foreach(Seq.empty) which returns unit. This would always "pass" even for a dead container.
    PRIORITY: medium

66. [HEALTH] "HealthCheck.all — one failing check causes init to fail"
    TESTS: all(exec("true"), exec("false")) should fail init
    PRIORITY: medium
```

### O. Scope Cleanup Robustness

```
67. [SCOPE] "scope cleanup works when container crashes during use"
    TESTS: Container with command that crashes (segfault/OOM), verify scope cleanup doesn't hang
    PRIORITY: medium

68. [SCOPE] "initUnscoped health check failure leaks container"
    TESTS: initUnscoped with failing health check -- does the created container get cleaned up?
    POTENTIAL BUG: initUnscoped calls create, start, then runHealthCheck. If health check fails with Abort, the container is left running with no cleanup registered. This is a resource leak.
    PRIORITY: high

69. [SCOPE] "nested scope cleanup order — container before network"
    TESTS: Create network, then container connected to it. Scope cleanup removes container first, then network.
    POTENTIAL BUG: If cleanup removes network before disconnecting container, the network removal would fail.
    PRIORITY: medium
```

### P. Backend-Specific Behavior

```
70. [BACKEND] "HTTP backend mapHttpError correctly classifies 304 as AlreadyStopped"
    TESTS: Unit test or integration test triggering a 304 from the Docker API
    PRIORITY: low

71. [BACKEND] "HTTP backend URL encoding handles special characters in container names"
    TESTS: Container with name containing special characters, verify URL encoding doesn't break
    POTENTIAL BUG: The url() method uses java.net.URLEncoder for query params but container IDs and names in URL paths are not encoded. A container name with "/" would break the URL.
    PRIORITY: medium

72. [BACKEND] "Shell backend resolveHostPath handles macOS /tmp -> /private/tmp"
    TESTS: Tested indirectly by "bind mount from /tmp works on macOS" but no direct test of the resolveHostPath function
    PRIORITY: low

73. [BACKEND] "Shell backend mapError correctly identifies 'No such container' vs 'not found'"
    TESTS: The shell backend parses stderr strings to classify errors. Various Docker/Podman error message formats should be tested.
    PRIORITY: medium

74. [BACKEND] "HTTP backend demuxStream correctly handles Docker multiplexed output"
    TESTS: The stream demultiplexing logic (8-byte header: stream type + 4-byte length) is untested with known binary inputs.
    POTENTIAL BUG: Off-by-one errors in header parsing or incorrect handling of partial frames could corrupt log output.
    PRIORITY: medium
```

### Q. ContainerImage.reference Roundtrip

```
75. [ROUNDTRIP] "reference + parse roundtrip for all predefined images"
    TESTS: For each of Alpine, Ubuntu, BusyBox, Nginx, Postgres, Redis: parse(image.reference) == Result.succeed(image)
    POTENTIAL BUG: Predefined images use registry=Present(DockerHub) and namespace=Present("library"), but parse("docker.io/library/alpine:latest") should reconstruct those exactly.
    PRIORITY: high
```

### R. Concurrency and Interruption

```
76. [CONCURRENCY] "fiber interruption during exec cleans up subprocess"
    TESTS: Start a long-running exec, interrupt the fiber, verify no zombie process
    PRIORITY: medium

77. [CONCURRENCY] "fiber interruption during logStream terminates follow"
    TESTS: Start logStream, interrupt fiber, verify the "docker logs --follow" process terminates
    PRIORITY: medium

78. [CONCURRENCY] "parallel container init with same name — exactly one succeeds"
    TESTS: Two fibers both try Container.init(config.name("same-name")), exactly one gets AlreadyExists
    PRIORITY: medium
```

### S. WebSocket Attach (Always Fails)

```
79. [ATTACH] "attachWebSocket always fails with NotSupported"
    TESTS: container.attachWebSocket should fail with NotSupported
    PRIORITY: low
```

### T. Capability cliName Mapping

```
80. [TYPES] "Capability.cliName produces correct UPPER_SNAKE_CASE"
    TESTS: NetAdmin.cliName == "NET_ADMIN", SysPtrace.cliName == "SYS_PTRACE", Custom("MY_CAP").cliName == "MY_CAP"
    PRIORITY: low
```

---

## Priority Summary

| Priority | Count | Key Gaps |
|----------|-------|----------|
| High     | 14    | ContainerImage.parse (7), initUnscoped (3), exec 126/127 inconsistency (2), scope cleanup under interruption (1), reference roundtrip (1) |
| Medium   | 30    | Config edge cases, error typing, backend parsing, log edge cases, network multi-connect, health check edge cases |
| Low      | 16    | Dead exception types, opaque type constructors, Platform.parse, WebSocket |

## Most Likely to Expose Real Bugs

1. **ContainerImage.parse digest double-prefix** (#3): `Digest.apply` prepends "sha256:" unconditionally, but parse passes the full "sha256:abc123" string, potentially creating "sha256:sha256:abc123".

2. **initUnscoped health check failure leaks container** (#68): If health check fails after create+start, the container is left running with no Scope.ensure to clean it up.

3. **Exec exit code 126/127 inconsistency between backends** (#42, #43): ShellBackend raises ExecFailed for these codes; HttpContainerBackend returns them as ExecResult. Callers expecting consistent behavior across backends will be surprised.

4. **HealthCheck.all with zero checks always passes** (#65): `all()` with no checks always succeeds, even for crashed containers.

5. **autoRemove + restartPolicy=Always conflict** (#28): Docker rejects this combination but kyo-pod's Config builder allows it silently.

6. **ContainerImage.parse accepts empty name** (#8, #9): `:latest` and `@sha256:...` parse to images with empty names, which would fail when used but with confusing errors.

7. **Container name with "/" in HTTP backend URL** (#71): Container names containing "/" would break the HTTP API URL path construction.
