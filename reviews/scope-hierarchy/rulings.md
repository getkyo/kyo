# Rulings

Decisions the user has made. These bind. A proposal that contradicts one is wrong on sight and does
not get re-argued from recall.

## Hierarchy

- **Membership is managed at `derive`/`fork`/`join`, and nowhere else.** No `done`, no `release`, no
  kernel change. `2026-09-05`
- **`derive` links the nested run to its parent.** Linking happens in the hook, not by reading the
  enclosing scope beforehand. `2026-09-05`
- **The link lives in the parent's children set.** The child holds no parent field, so no mutable
  state on the node beyond the collections. `2026-09-05`
- **The `children` set stays.** It is what phase 1 reaches down through and phase 2 waits on.
  Collapsing it into a counter or into the finalizer queue is wrong: a counter cannot close children,
  only wait for them. `2026-09-05`
- **There is no separate "signals" structure.** An interrupt is an ordinary finalizer. `2026-09-05`
- **A nested `Scope.run` is a child exactly as a fork is**, when there is a parent. `2026-09-05`
- **Scope hierarchy only.** Do not reach for the fiber hierarchy to explain or drive any of this.
  `2026-09-05`

## Interruption

- "interruption shoudl stay as is: a signling mechanism. Coordination to wait for freeing resources
  can be done via hierarchical scopes." `2026-09-04`
- No `interruptAwait`, and no mechanism resembling it. `2026-09-04`

## Scope

- `Sync.ensure`, `Sync.acquireReleaseWith` and `Sync.run` stay public and unchanged. `2026-09-04`
- `Closeable` is deferred, not built. Membership is `Set[Finalizer]`. `2026-09-05`
- The spawn-window fix ships with no test that fails without it; a test hold point in production
  source was declined. `2026-09-05`

## Kernel

- `Maybe`, never `| Null`. `2026-09-04`
- The kernel bracket release takes `Maybe[Throwable]`. `2026-09-04`
- A forked region is deliberately not a lifecycle participant. Pinned by
  `IsolateTest` "an isolate cycle fires done once, for the region the user installed, with the joined
  state" and `BracketTest` "a bracket does not cross into an isolated child". Refutes any design that
  has a forked child report upward. `2026-09-05`

## Process

- Reproduce before you fix. A failing test comes first, and the user sees it. Violated twice on
  `2026-09-05`; both times the fix that followed was wrong.
- Use the Edit tool for file changes.
- Never create, open, close, comment on or merge a PR.
