# Analysis: Fix 424 Ignored Tests on JS/Native

## Problem
- JS/Native `ContainerRuntime` stubs return false for everything
- `ContainerTest.tags` marks all 424 container tests as Ignored when runtime unavailable
- 2 tests use `pending` when socket not found (execInteractive, attach)

## Root Cause
ContainerRuntime on JS/Native is a dead stub. Docker/Podman ARE available on the machine.

## Plan

### Step 1: Native ContainerRuntime
Copy JVM implementation verbatim. `ProcessBuilder`, `Files`, `System` all work on Scala Native.

### Step 2: JS ContainerRuntime  
Rewrite using Node.js `child_process.execSync` and `fs.existsSync` (pattern from TlsTestHelper).

### Step 3: Remove Ignore mechanism
Remove the `tags` override in ContainerTest.scala.

### Step 4: Fix pending tests
Fix `findPodmanMachineSocket()` for macOS (or remove pending guard if socket available).

### Step 5: Verify 458/0/0/0 on all platforms
