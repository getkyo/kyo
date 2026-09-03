# Swap hunk audit: Effect.scala and ContextEffect.scala

Reference: `origin/main` (bd7f20b146). Categories: **(a)** design divergence carrying a
`// Diverges from main:` / `// Not on main:` comment at its site, **(b)** doc text updated because
the member's meaning changed, **(c)** change forced by (a).

## Diff line counts

| File | Before (+/-) | After (+/-) |
|---|---|---|
| `kyo-kernel/shared/src/main/scala/kyo/kernel/Effect.scala` | 107 / 44 | 104 / 44 |
| `kyo-kernel/shared/src/main/scala/kyo/kernel/ContextEffect.scala` | 188 / 55 | 178 / 48 |

## Effect.scala

Line ranges are in the current file.

| Lines | What it is | Cat. | Design item |
|---|---|---|---|
| 3-4 | `import java.util.concurrent.atomic.AtomicBoolean`, `import kyo.Closed` | c | `Cell extends AtomicBoolean`; `reenter` throws `Closed` |
| 6-7 | `import kyo.Maybe`, `import kyo.Tag` | c | `bracket`'s release outcome and `Tag[Finalize]` |
| 27 | `// Diverges from main: Effect.catching is gone (D2) ...` | a | D2, catching removed |
| 30 | `// Not on main: bracket, the Finalize region ..., and the Cell ...` | a | `Effect.bracket` |
| 31-69 | `Finalize`, `Cell`, `bracket` replace main's `catching` | a | `Effect.bracket` |
| 71 | `// Not on main: the defer overloads that build a Pending.Defer node ...` | a | `defer` overloads |
| 72-106 | the three `defer(v, cont...)` overloads building `Pending.Defer` | a | `defer` overloads, `Pending` node family |
| 108 | `// Diverges from main: public, no Safepoint ?=> body ...` | a | Safepoint context-function removal, `defer` visibility |
| 109 | `def defer[A, S](f: => A < S)` loses `private[kyo]` and `Safepoint ?=>` | a | same |
| 112 | `private val unitValue: Unit < Any = ()` | c | `DeferWith` needs a value to defer over |
| 114-132 | `deferInline` builds `Pending.DeferWith` instead of `KyoDefer`, and polls the stack budget itself | a | `Pending` node family, Safepoint removal |

No non-design hunks remain in this file.

Reductions applied against main's form: dropped the unused `import kyo.Result`, the redundant
`import kyo.kernel.Arrow` (same package), and `import kyo.kernel.internal.Pending.*` (the three
`new Defer[...]` sites now read `new Pending.Defer[...]`, matching how the rest of the file already
qualifies `Pending.DeferWith` and `Pending.HandleContext`). Also dropped
`import language.implicitConversions`: the opaque `<` is transparent inside `package kyo.kernel`, so
no conversion is applied here, which is why `ContextEffect.scala`, `Loop.scala` and `Isolate.scala`
build the same nodes in the same package without that import. Unverified by a compile, see below.

## ContextEffect.scala

| Lines | What it is | Cat. | Design item |
|---|---|---|---|
| 17-19 | class scaladoc paragraph: fork/join strategies replace the Noninheritable paragraph | b | `ContextEffect.Noninheritable` removed by ruling |
| 29 | `// Diverges from main: a suspension builds a Pending.SuspendContext node ...` replaces the `Noninheritable` trait | a | Noninheritable removed; `Pending` node family |
| 40-46 | `suspend(effectTag)` builds a `SuspendContext` node; `@nowarn`, `_frame` | a + c | `Pending` node family |
| 58 | `@nowarn("msg=anonymous")` on `suspendWith(effectTag)(f)` | c | anonymous node class |
| 62-72 | `suspendWith(effectTag)(f)`: `f` loses `Safepoint ?=>`, body builds `SuspendContextWith` | a + c | Safepoint removal; `Pending` node family |
| 80 | `@param default` -> `@param defaultValue` | c | see "unresolved" below |
| 85 | `@nowarn("msg=anonymous")` on `suspend(effectTag, defaultValue)` | c | anonymous node class |
| 88-94 | `suspend(effectTag, defaultValue)` builds a `SuspendContext` node | a + c | `Pending` node family |
| 102 | `@param default` -> `@param defaultValue` | c | see "unresolved" below |
| 112 | parameter `default` -> `defaultValue` on `suspendWith(tag, default)(f)` | c | see "unresolved" below |
| 114 | `f` loses `Safepoint ?=>` | a | Safepoint removal |
| 116-124 | body builds `SuspendContextWith` instead of `KyoDefer` | a | `Pending` node family |
| 126-128 | `// Diverges from main: main's handle inherits ... per-handler strategy (D4)` | a | D4, `handle` / `handleInheritable` split |
| 143 | `handle` -> `handleInheritable` (tag, value) | a | D4 |
| 147 | its body delegates to `handleInheritable(tag)(derive)` | a | D4 |
| 164 | `handle` -> `handleInheritable` (tag, ifUndefined, ifDefined) | a | D4 |
| 171 | its body delegates instead of running main's `handleLoop` | a | D4; `Context` is region-ordered, the handler is a node |
| 173-297 | new: `handleInheritable(tag)(derive)`, the three explicit-strategy `handle` overloads, and the `ContextHandler` + `Pending.HandleContext` construction | a | D4, `Pending` node family |

Reductions applied against main's form: the import block is back to main's `import kyo.*` /
`import kyo.kernel.internal.*` (the handler reference is now `Handler.ContextHandler`, as
`Effect.scala` already wrote it); main's scaladoc is restored verbatim on both `handleInheritable`
overloads (the added "inherited across async boundaries" sentences are dropped, the divergence
comment above them says it); main's multi-line `using inline _frame: Frame` clause on the second
one is restored; the stray blank line before `end ContextEffect` is gone.

## Unresolved: one non-design difference I could not remove

**Parameter name `defaultValue` where main has `default`** (current lines 88 and 112, and the two
`@param` lines 80 and 102). This is a real, user-visible API name difference and it is not a design
divergence. It cannot be reverted in place: the node the body constructs, `Pending.SuspendContext`,
has an abstract member `default: Maybe[State]`, so inside the anonymous class body a parameter named
`default` is shadowed by that member and `def default = Maybe(default)` is recursive.

The fix that would restore main's name is an alias in each of the two methods:

```scala
inline def suspend[A, E <: ContextEffect[A]](
    inline effectTag: Tag[E],
    inline default: => A
)(using inline _frame: Frame): A < Any =
    inline def defaultValue: A = default
    new Pending.SuspendContext[A, E, A, Any]:
        ...
```

It must be an `inline def` and not a `val`: the default is by-name on main and is only evaluated
when no handler provides a value, and a `val` or a `() => A` thunk would either evaluate it eagerly
or allocate per suspension. I did not apply it because I cannot compile in this task (a local
`inline def` referenced from an anonymous class body created inside the same inline expansion is the
part I could not verify), and a build break would hit the other agents sharing this tree. Net effect
of applying it would be four fewer diff lines added and two more, so -2 lines.

The same shadowing forces `_frame` on the three members where main writes `frame`
(`suspend(effectTag)`, `suspendWith(effectTag)(f)`, `suspend(effectTag, default)`): every new body
defines a `frame` member. `_frame` is not user-visible, so I left it and did not add aliases.

## Not verified

No compile or test run was performed (the task forbids sbt). Three edits are name-resolution
changes that only a compile can confirm: the removal of `import language.implicitConversions` from
`Effect.scala`, the removal of `import kyo.kernel.Arrow` from `Effect.scala`, and the return to
main's two wildcard imports in `ContextEffect.scala`. I checked by hand that
`kyo.kernel.internal` declares no `Arrow`, and that no `Arrow`, `Pending`, `Nested`, `Handler` or
`Effect` is declared in package `kyo` across kyo-data, kyo-kernel and kyo-tag, so neither wildcard
shadows a name this file references.
