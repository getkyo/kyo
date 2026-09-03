# Swap hunk audit: `kyo-kernel/shared/src/main/scala/kyo/kernel/Isolate.scala`

Reference: `origin/main` (`bd7f20b146`).

## Diff size

`git --no-pager diff --numstat origin/main -- kyo-kernel/shared/src/main/scala/kyo/kernel/Isolate.scala`

| | added | removed |
|---|---|---|
| before | 205 | 50 |
| after | 120 | 41 |

Hunk count went from 8 to 6.

## Remaining hunks

Line ranges are in the current file.

| lines | what it is | category |
|---|---|---|
| 6/7 boundary | `import scala.annotation.nowarn` deleted. Its only user was `runDetached`, which is gone. | (c), forced by the removal of `runDetached` |
| 141-143 | `nest` lifts the restored computation with `Nested.nest` instead of `Kyo.lift`, which the new kernel does not have. Two comment lines at the site. | (a), `Nested` node family |
| 159-169 | `run` over an already-captured state, and `apply`. Neither is on main; `apply` is what a caller crossing a boundary uses (`IsolateTest` line 528 and line 613). Three comment lines at the site. | (a), public surface kept from the branch |
| 202-212 | `andThen` loses main's `self eq Identity` / `next eq Identity` short circuit, since `Identity` is gone and `Contextual` is not a no-op. The anonymous class dedents by four because the `else` is gone; its body is main's text otherwise. Two comment lines at the site. | (a) for the short circuit, (c) for the dedent |
| 238-330 | `internal`: `Identity`, `runDetached` and `restoring` are gone; `Contextual` and its `Forked` handler wrapper are new. Five comment lines above `Contextual`, two above `Forked`. | (a), the stack-snapshot isolate |
| 398-399 | `deriveImpl` folds onto `Contextual`, where main folded onto `Identity`. One comment line at the site. | (a), same item as the row above |

Every hunk is a design divergence, a comment naming one, or a mechanical consequence of one. No hunk is whitespace, import order, member order, naming, or doc drift.

## What was reverted to main's form

- **Imports.** Back to main's block (`Isolate.internal.*`, `kyo.*`, `kyo.Ansi.*`, `kyo.kernel.internal.*`, `scala.quoted.*`), minus `scala.annotation.nowarn`. The branch had replaced it with eight single-symbol imports. `deriveImpl` is again referenced unqualified in `derive` and in the given, as on main.
- **`capture`'s signature.** Back to main's `A < (Remove & Keep & S)`; the branch had narrowed it to `A < (Remove & S)`. Widening the row is safe in both directions: `<` is contravariant in its effect parameter, so every implementation in the repo (`Var`, `Emit`, `Check`, `Browser`, `LLM`, the kernel tests) still conforms, and the only caller outside this file is `IsolateTest` line 492 on `Contextual`, where `Remove` and `Keep` are both `Any`.
- **`run`'s body.** Back to main's `capture(state => restore(isolate(state, v)))`; the branch had it delegate to the new two-argument `run`. The two are the same expression, and nothing in the repo overrides the two-argument `run`, so the delegation was diff noise rather than an extension point in use.
- **The `deriveImpl` error message.** Back to main's `new Isolate.Stateful[MyEffect, Any]`. The branch had corrected it to `new Isolate[MyEffect, Any, Any]`. Main's text is stale (no `Stateful` exists on main either), but the correction is not a design divergence of this kernel, so it does not belong in this diff. It should be fixed on main instead.
- **Scaladoc.** Every doc block is main's text verbatim. The branch had copied the abstract class's docs onto the members of the `andThen` anonymous class and onto `Contextual`; main documents neither, so they are gone. The two new members carry a `// Not on main:` comment rather than invented scaladoc.

## Notes

- Nothing here was compiled. The import block is the one change with a name-resolution question, since `kyo.*` now supplies `Arrow` (through `export kernel.Arrow`, which is not on main) alongside the same-package definition, and `kyo.kernel.internal.*` supplies `Pending` alongside `kyo.kernel`'s. Both were checked against Scala 3.8.3 with a standalone five-file reproduction of the same package, export, and opaque-type shape outside the repo; it compiled with no error, so the wildcard block resolves as main's did.
- Unverified observation, unchanged by this pass: `Contextual` is `private[kernel]`, and `deriveImpl` splices a reference to it into every `derive` and given call site, including ones outside `kyo.kernel` (`outsidekyo/KernelTest.scala` line 452 derives an isolate). If the branch already compiles that test, there is nothing here; flagging it only because the accessibility question is visible from the diff and I could not run a build.
