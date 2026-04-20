# Analysis: Remove raw Java Thread/ProcessBuilder/LinkedBlockingQueue

## Problem
`HttpContainerBackend.scala` uses raw Java concurrency primitives (Thread, ProcessBuilder, LinkedBlockingQueue) 
instead of Kyo's `Command` API. This is inconsistent with `ShellBackend` which uses Kyo primitives throughout.

## Raw Java Usage Found

### 1. `cliStreamLines` method (lines 1469-1510)
- Uses `java.lang.ProcessBuilder`, `java.util.concurrent.LinkedBlockingQueue`, daemon `Thread`
- Called by `imagePullWithProgress` and `imageBuildFromPath`
- **Action**: DELETE entirely, replace callers with Kyo `Command`+`Scope.run`+`proc.stdout.mapChunk`

### 2. `imagePull` method (lines 850-878)
- Uses `java.lang.ProcessBuilder` directly for `docker pull`
- **Action**: Replace with `Command(cliCommand, "pull", ...).text` pattern

## Methods Already Using Kyo Command API (no changes needed)
- `execStream` (line 400) - Already uses `Command` + `Scope.run` + `proc.stdout.mapChunk`
- `attach` / `createAttachSession` (line 443) - Already uses `Command` + `Process.Input`
- `copyTo` / `copyFrom` - Already uses `Command`

## Plan

### Step 1: Fix `imagePull` - replace ProcessBuilder with Kyo Command
Replace the raw ProcessBuilder with:
```scala
val pullCmd = Command((cliCommand +: args)*).redirectErrorStream(true)
Abort.runWith[CommandException](pullCmd.textWithExitCode) {
    case Result.Success((output, exitCode)) =>
        if exitCode == 0 then ()
        else if output.contains("not found") || output.contains("manifest unknown") then
            Abort.fail[ContainerException](imageNotFound(ref))
        else
            Abort.fail[ContainerException](ContainerException.General(...))
    case Result.Failure(cmdEx) => ...
    case Result.Panic(ex) => ...
}
```

### Step 2: Fix `imagePullWithProgress` - replace cliStreamLines with Kyo Command 
Follow ShellBackend's pattern exactly:
```scala
Scope.run {
    Abort.runWith[CommandException](pullCmd.spawn) {
        case Result.Success(proc) =>
            proc.stdout.mapChunk { bytes => ... }.emit
        case Result.Failure(cmdEx) => ...
        case Result.Panic(ex) => ...
    }
}
```

### Step 3: Fix `imageBuildFromPath` - same pattern as Step 2

### Step 4: Delete `cliStreamLines` helper entirely

### Step 5: Verify compilation

### Step 6: Run failing tests
