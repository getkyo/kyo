# Analysis: Add Meter to ContainerBackend for concurrency limiting

## Changes

1. **ContainerBackend** — Add `meter: Meter` constructor parameter (default `Meter.Noop`)
2. **ShellBackend** — Accept `meter: Meter` param, pass to super, wrap `run` method with `meter.run`
3. **HttpContainerBackend** — Accept `meter: Meter` param, pass to super, wrap `withErrorMapping` with `meter.run`
4. **Container.BackendConfig** — Add `meter: Meter = Meter.Noop` to each case
5. **resolveBackend** — Pass `config.meter` to backend constructors
6. **ContainerTest** — Use `Meter.initSemaphore(8)` for Podman shell, remove BackendUnavailable workaround

## Effect type consideration

`Meter.run` adds `Async & Abort[Closed]` to the effect set. The ShellBackend `run` method already returns 
`String < (Async & Abort[ContainerException])`. `Abort[Closed]` is new — need to handle it by catching and 
converting to `ContainerException.General`.

Wait — looking at `Meter.Noop.run`: it just returns `v` with no additional effects. But the type signature 
still declares `A < (S & Async & Abort[Closed])`. So we'll need to handle `Abort[Closed]` in methods that
call `meter.run`.

Actually, looking more carefully: the existing methods already have `Async` in their effect set. We need to 
add `Abort[Closed]` handling. We can wrap the meter.run call and convert Closed to ContainerException.General.

Simpler approach: since `Meter.Noop.run` just returns `v` and we only ever use a non-Noop meter in tests,
we can handle Closed at the wrapping points by catching it.
