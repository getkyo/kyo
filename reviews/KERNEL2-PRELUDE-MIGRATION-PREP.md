# kyo-prelude to kernel2: migration prep

Main sources only, no tests (user scope). The build already points `kyo-prelude` at `kyo-kernel2`;
this document is the site inventory and the translation rules, prepared so the migration pass is
mechanical where it can be and named where it cannot.

## The load-bearing difference

Old-kernel `handleLoop` clauses are cont-style: the clause receives the continuation and drives
resumption (`[C] => (input, cont) => ... Loop.continue(cont(()))`). Kernel2's loop family is
answer-style: the clause receives only the input and returns the answer
(`[C] => input => ... Loop.continue(())`); the kernel applies the continuation. Kernel2's
cont-style family is `handleCont`, whose clause consumes the continuation itself.

## Translation rules

1. **`ArrowEffect.handle` → `ArrowEffect.handleCont`** (8 sites: Stream:585/607, Check:77/104,
   Emit:136/156, Batch:134, Choice:99). Same clause shape (`(input, cont) => ...`, cont applies
   the same); the done argument is `a => a` where old handle had none. Rename plus done-arg.
2. **Cont-style `handleLoop` with tail `cont(x)` → `handleLoopState`/`handleLoop` answer-style**
   (22 classified mechanical, plus most of the 17 flagged sites, which are the same shape split
   across lines). `Loop.continue(cont(x))` becomes `Loop.continue(x)`; effects wrapped around the
   outcome stay, since the kernel2 clause row admits them. Stateful sites additionally swap
   argument order: old `(input, state, cont)`, kernel2 `(state, input)`, and the final-state done
   clause moves to the `(state, a) => ...` argument.
3. **Genuinely non-linear cont uses → `handleCont`**. The flagged list to inspect by eye:
   Stream:247/271/295/316/337/358/529/557/629/646/659, Poll:148, Var:147, Check:89,
   Emit:95/118/176. A clause that drops the continuation (early termination: Stream.take,
   Poll on empty) or applies it under a constructed computation keeps the continuation in hand
   via handleCont; one that merely formats `Loop.continue(\n cont(x))` across lines is rule 2.
4. **`ArrowEffect.handleFirst` → a prelude-internal helper over `handleCont`** (15 sites:
   Poll:170/214/219, Stream:705+, the zip/merge weaving). Old signature:
   `handleFirst(tag, v)(handle: [C] => (I[C], O[C] => A < (E & S)) => B < S2, done: A => B < S2): B < (S & S2)`.
   The helper nests the region's value so the region ends at the first suspension:
   the body becomes `v.map(a => Kyo.lift(done(a)))` at answer type `B < S2`, and the clause
   returns `Kyo.lift(handle(input, cont))` immediately, so the handler never answers a second
   suspension: the continuation handed to `handle` is the raw region continuation, and the
   remainder computation it produces still carries `E` in its row exactly as the old signature
   says. The helper flattens the nested result once at the end. Sites then port unchanged.
   To validate first against kernel2's handleCont signature; if the row bookkeeping fights the
   nesting, the fallback is per-site handleCont rewrites at the 15 sites.
5. **`Local.initNoninheritable`** (Local:110): the `ContextEffect.Noninheritable` marker trait is
   gone; kernel2's `ContextEffect.handle` takes `fork = _ => Absent` for a binding that must not
   cross a fork. Rework the `NoninheritableState` trait and the handle call accordingly.
6. **`kyo.debug.Debug`**: deleted, not ported (user ruling; the locally pre-adapted Debug.scala
   presumed a `kyo.kernel.Observe` that was never built; its replacement is the Debugger seam
   plus the kyo-test failed-test trace).
7. **`Reducible`**: prelude-internal, depends only on `kyo.<`; moves untouched.
8. **Everything else** (`ContextEffect.suspend/suspendWith/handle`, `Effect.defer/catching`,
   `Loop.*` all arities, `Kyo.*` helpers, `Isolate`, Pending combinators including `.handle`):
   present in kernel2 under the same names and shapes.

## Suggested order

1. The handleFirst helper, compiled against kernel2 first (it is the one piece with design risk).
2. Rules 1 and 5 and 6 (renames, Local, Debug deletion).
3. Rule 2 across Var/Emit/Check/Poll/Memo (small files first), then Stream.
4. Rule 3 inspections folded in per file as encountered.
5. `kyo-preludeJVM/compile` error-burndown as the ground truth throughout; the static inventory
   above claims completeness, and the compiler adjudicates it.

## Site counts

| kind | count | disposition |
|---|---|---|
| ArrowEffect.handle | 8 | rename + done arg |
| handleLoop, tail cont | 22 | answer-style rewrite |
| handleLoop, flagged for inspection | 17 | rule 2 or rule 3 by eye |
| handleFirst | 15 | helper, sites unchanged |
