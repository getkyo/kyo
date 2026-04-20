# Resource Safety Fixes — Analysis

## Fix 1: Container.init — register Scope.ensure immediately after create
**Problem**: If `start(cid)` fails, the container from `create` is never cleaned up because `Scope.ensure` is registered after `start`.
**Fix**: Move `Scope.ensure` registration to immediately after `create`, before `start`.

## Fix 2: Container.initUnscoped — cleanup on failure after create
**Problem**: If `start` or `runHealthCheck` fails, the container is permanently leaked since there's no scope cleanup.
**Fix**: Wrap start + healthCheck in `Abort.run`, and on failure, best-effort remove the container.

## Fix 3: ShellBackend.createAttachSession — subprocess cleanup
**Analysis**: `Command.spawn` already registers `Scope.acquireRelease` that calls `destroyForcibly` on scope exit (line 70-73 of Command.scala). No additional cleanup needed.
**Conclusion**: Fix 3 is **not needed**.

## Plan
1. Apply Fix 1 to Container.init
2. Apply Fix 2 to Container.initUnscoped
3. Skip Fix 3 (already handled by Command.spawn)
4. Compile and verify
