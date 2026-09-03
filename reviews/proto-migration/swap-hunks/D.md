# Swap-hunk minimization: `kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala`

Reference: `origin/main` (bd7f20b146).

## Diff line counts

`git --no-pager diff --numstat origin/main -- kyo-kernel/shared/src/main/scala/kyo/kernel/Pending.scala`

| state | added | removed |
|---|---|---|
| before (HEAD) | 200 | 247 |
| after (worktree) | 111 | 202 |

The removal count stays high because main's 73-line lift block (`lift`, `liftAnyVal`, `liftUnit`,
`abortCastUnit` + its macro impl, `liftPureFunction1..6` and their scaladoc) is deleted from this
file by design; those members live in `internal/Implicits.scala` on this branch.

## What was restored from main

Everything below is now byte-identical to main and no longer appears in the diff:

- Import block: back to `import kyo.*` / `import kyo.kernel.internal.*` (the per-symbol imports
  `kyo.Frame`, `kyo.Maybe`, `kyo.Render`, `kyo.kernel.Arrow`, `kyo.kernel.Arrow.Step`, the unused
  `kyo.kernel.Arrow.Transform`, and `language.implicitConversions` are gone). `Arrow.Step` is now
  written qualified at its single use site, so no extra import is needed.
- Extension parameter name `self` renamed back to `v` in all four extension groups.
- All ten `handle` overloads: scaladoc, parameter types (`f1: A < S => B`, not `(=> A < S) => B`),
  one-line vs multi-line signature layout, and the delegating bodies (`def handleN = v.handle(...)`
  instead of the expanded `def h1..hN` chains). The whole `handle` region is diff-free.
- `@nowarn("msg=anonymous")` moved from the `inline def` line onto the inner local def, which is
  main's placement (`@nowarn("msg=anonymous") def run[...]`), saving a line per combinator.
- `evalNow` scrutinee shape: `v match` with a `case v =>` rebinding, instead of `val v = self`,
  and no `end evalNow` marker (main has none).
- `eval` body shape: matches on `v` directly with a `case kyo:` / `case v =>` pair instead of
  binding `val v0 = v`; the stray blank line after the signature is gone.
- `fromKyo` moved back to main's position (after the `eval` extension, before the `given`) with
  main's modifiers, `inline`, and parameter name: `implicit private[kernel] inline def fromKyo[A, S](v: ...)`.
- `given Render` header and `def asString(...) = value match` on one line, main's binder name `sus`,
  main's blank line before `end `<``, and the missing trailing newline at EOF.
- Blank lines after the `extension [A, S](v: A < S)` and `extension [A, S, S2](v: A < S < S2)`
  headers removed (main has none).

## Remaining hunks

Categories: (a) design divergence with a comment at its site, (b) doc text updated because the
member's meaning changed, (c) change forced by (a).

| lines (current file) | what it is | cat | design item |
|---|---|---|---|
| (deletion only, at line 5) | `import scala.annotation.tailrec` dropped | c | `eval` no longer runs a `@tailrec` loop; it delegates to `Eval` |
| (deletion only, at line 6) | `import scala.quoted.Expr/Quotes/Type` dropped | c | the splice macros (`lift`, `abortCastUnit`) are not in this file |
| 38-42 | 4-line `// Diverges from main` comment + `opaque type <[+A, -S] = A \| Pending[A, S]` | a | the union's second arm is `Pending`; the comment also covers the combinators, `eval` and `flatten` |
| 44-46 | 2-line `// Not on main` comment + `object `<` extends Implicits:` | a | the lifts live in `internal.Implicits`, mixed into the companion |
| 61-77 | `map` signature (no `Safepoint ?=>`, no `Safepoint` evidence) and body (`Pending.DeferWith` + `Arrow`, safepoint slot polled inline) | a | combinator design, named by the comment at line 38 |
| 90-106 | `flatMap`, same shape as `map` | a | same |
| 116-132 | `andThen`, same shape as `map` | a | same |
| 140-156 | `unit`, same shape as `map` | a | same |
| 344-345 | `evalNow` cases: `case _: Pending[?, ?]` and `Nested.unnest(v)` in place of `KyoSuspend` / `unsafeGet` | c | `Pending` is the suspension family; `Nested.unnest` replaces the deleted `unsafeGet` |
| 350-353 | `// Not on main` comment + `chain`, replacing main's `unsafeGet` extension | a | `chain` applies an `Arrow` to a computation; `unsafeGet`'s role is `Nested.unnest` |
| 362 | `@nowarn("msg=anonymous")` on `flatten` | c | the new body allocates an anonymous `Arrow.Step` |
| 364-368 | `flatten`'s `arrow` step | a | flatten builds an `Arrow` step and defers through `Effect.defer` |
| 370-382 | `flatten`'s `run` body | a | same |
| 398-400 | `eval` body: `Pending` test, `Eval(...)`, `Nested.unnest` | a | named by the comment at line 38 |
| 404-405 | `// Diverges from main` comment + `fromKyo` taking `Pending[A, S]` | a | the union's second arm |
| 408-409 | `// Not on main` comment above `def asString` | a | explains the added `Nested` case below it |
| 411-413 | `Render` cases: `Pending` instead of `Kyo`, plus a `Nested` case | a + c | `Nested` is not a `Pending` here, so it needs its own case to render in main's `Kyo(...)` form |

No hunk remains that is not one of the above. Nothing was left in the diff that I judged to be
whitespace, naming, ordering, import style, or doc drift.

## Behavioral notes (not verified by a build; sbt was not run)

Three of the restorations change more than layout. Each was chosen because main's form is the
non-divergent form and nothing in the branch's design list covers it:

1. **`handle` parameter types.** The branch had `f1: (=> A < S) => B` on the arity 2..10 overloads;
   main has `f1: A < S => B`. Restoring main's type means `f1` receives the computation strictly.
   Main's own arity-2 body (`v.handle(f1)`) passes a strict `f1` where the arity-1 overload expects
   `(=> A < S) => B`, so this exact adaptation compiles on main. Building a `<` value is
   allocation, not effect execution, so strictness here does not run anything early.
2. **`fromKyo` visibility.** It was `implicit def` (public, not inline); it is now
   `implicit private[kernel] inline def`, main's form. Every reference to `Pending` in the repo is
   under `kyo.kernel` or `kyo.kernel.internal` (checked across all modules, main and test sources),
   both inside `private[kernel]`. Main pairs the same `private[kernel] inline` conversion with a
   `private[kyo] inline def deferInline` that returns a freshly allocated suspension as `A < S`,
   which is exactly the shape `Effect.deferInline` has on this branch.
3. **`@nowarn("msg=anonymous")` placement** on `map`/`flatMap`/`andThen`/`unit` moved from the
   `inline def` to the inner local def. That is main's placement for the same construct (an
   anonymous class inside a local def inside an inline method). It was left on `flatten` (main has
   none there) because `flatten` is not inline and the annotation is part of an already-divergent
   body; removing it was not worth risking `-Werror`.

## Renames skipped

None. Every rename stayed inside this file.
