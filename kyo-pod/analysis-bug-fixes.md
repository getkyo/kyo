# Bug Fix Analysis

## Bug 1: ContainerImage.parse accepts empty names
- **File**: `ContainerImage.scala` line 118
- **Fix**: After extracting `nameVal`, check `if nameVal.isEmpty then Result.fail("Empty image name")`
- **Tests**: Update tests 8 and 9 in `ContainerUnitTest.scala` to assert `result.isFailure`

## Bug 2: HTTP backend exec doesn't raise ExecFailed for exit codes 125/126/127
- **File**: `HttpContainerBackend.scala` lines 382-384
- **Fix**: After building ExecResult, check exit code 125/126/127 and raise ExecFailed matching ShellBackend
- ShellBackend behavior:
  - 125: maps to `mapError(stderr, ...)` (infrastructure error)
  - 126: `ExecFailed(id, Chunk.from(command.args), ExitCode.Failure(126), "Command cannot be invoked ...")`
  - 127: `ExecFailed(id, Chunk.from(command.args), ExitCode.Failure(127), "Command not found")`

## Bug 3: Test assertion for exec command-not-found
- **File**: `ContainerTest.scala` line 2814-2820
- **Current**: test expects `ExecResult` with exit code 127, but after Bug 2 fix, ExecFailed will be thrown
- **Fix**: Update test to catch ExecFailed instead of checking exit code directly

## Bug 4: HealthCheck.all with zero checks
- Per instructions: empty all() trivially passing is a valid interpretation. Just update comment.

## Additional: parseInstant null check
- Already takes `Option[String]`, no null check present. No fix needed.
