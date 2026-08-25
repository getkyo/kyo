# Isolate Keep on the new kernel: what actually changed, and the fix shape

Status: resolved; the landed fix is section 8. Sections 1-7 are the investigation as it stood,
with the opus reviewer's corrections folded into sections 2 and 6.

## 1. Symptom

Repo-wide JVM Test/compile fails in two modules that define or need Async-Keep isolates:

- kyo-ai (main source, LLM.scala:668): `Async.timeoutWithError[.., LLM]` cannot materialize
  `Isolate[LLM, Sync, LLM]`. kyo-ai HAS `given Isolate[LLM, Async, LLM]` (LLM.scala:528); by Keep
  contravariance an Async-Keep instance cannot serve a Sync-Keep request (Sync is not <: Async).
- kyo-browser (20 sites, tests + demo): `Isolate[Browser, Sync, Browser]` fails to derive. The
  identical files are on origin/main unchanged and compile there, where the requests are
  `Isolate[Browser, Abort[E] & Async, Browser]`.

## 2. What did NOT change

Verified between kyo-kernel (old) and kyo-kernel2:

- The three phase signatures are byte-identical: `capture: A < (Remove & Keep & S)`,
  `isolate: Transform[A] < (Keep & S)`, `restore: A < (Restore & S)`.
- The single inline given is byte-identical, and deriveImpl's flatten, ContextEffect filter, and
  per-member summon are identical. The fold SEED is not: the old kernel seeds with `Identity` and
  short-circuits it away in `andThen`; kernel2 seeds with `Contextual` and deliberately never drops
  it, because the ambient-binding transport the old kernel did in runtime machinery (`runDetached`
  reading the live `Context`, `IOTask` installing `context.inherit` in the child) now lives inside
  the derived isolate as the `Contextual` crossing. Same semantics, relocated; `Contextual` is
  `Isolate[Any, Any, Any]`, the same type as `Identity`, so nothing in the derivation's TYPING
  moved.
- `Fiber.init/use/initUnscoped` take `Isolate[S, Sync, S2]` and answer at `Sync & S` on main too.

Nothing in Isolate's typing broke. The break is where kyo-core applies it.

## 3. What changed

- main: the 21 Async combinators declare `Isolate[S, Abort[E] & Async, S]` and OWN the crossing:
  `isolate.capture { state => Fiber.internal.race(iterable.map(isolate.isolate(state, _)))
     .map(f => isolate.restore(f.get)) }`.
  Fiber's internal spawners take no isolate; they spawn pre-isolated, effect-free bodies. The
  Sync-Keep spawn entry points and the Async-Keep combinators coexist because a combinator never
  routes its isolate through a spawn helper.
- branch (3bc9822e1d, "kernel2 fiber integration"): isolate application moved to the innermost
  spawn. `IOTask.apply[E, A, S, S2](isolate: Isolate[S, Abort[E] & Async, S2])(state, body)` does
  `boundary(isolate.isolate(state, body))(t => completeDiscard(Result.succeed(isolate.restore(t))))`
  and combinators delegate the WHOLE crossing to `Fiber.initUnscoped`. Because capture's row is
  `Remove & Keep & S` and initUnscoped answers at `Sync & S`, Keep had to be Sync, and that
  narrowing propagated to the 21 combinator signatures and ten new Fiber internals.

Note the restore placement is sound on the branch: `isolate.restore(t)` is stored as the fiber's
value computation (`Fiber[A, S] = IOPromiseBase[Any, A < (Async & S)]`), so it evaluates at the
join, in the joiner's context, same as main.

## 4. This was already litigated on the branch, and the resolution was wrong

- 213f9d0ca8 restored the wide Keep on the 21 + 10 sites, correctly observing "IOTask.apply, the
  innermost consumer, already takes Isolate[S, Abort[E] & Async, S2], so the loose Keep was never
  the obstacle", and left init/use/initUnscoped on Sync, "theirs on main too".
- ec384344d4 then widened the three spawn entry points as well ("carry one Keep down the whole
  spawn path"), self-declared "Unverified: not yet recompiled".
- 5e8bc7c922 reverted BOTH, arguing: capture returns `Remove & Keep & S`; init, initUnscoped,
  foreachIndexed, race, gather answer at `Sync & S`; `Sync & S <: S & Abort[E] & Async` requires
  `Sync <: Async`, false; five capture sites stopped typing. "The Keep is what keeps creating a
  fiber a Sync operation." Verdict: "kyo-ai's isolate is the thing that does not fit, and it gets
  fixed on its own side."

The revert's type analysis is correct for the code as it stood, but its verdict is not: kyo-ai's
isolate was never fixed on its own side (it cannot be: LLM's isolation is itself asynchronous, as
213's message states), kyo-browser broke the same way, and the tree has not test-compiled since.
An attempt in this session repeated 213+ec3's path (widen signatures, leave capture placement) and
hit exactly 5e8's wall: the internal spawners' capture sites and Sync rows.

## 5. The variable every attempt missed: capture placement, not Keep

All three attempts (213, ec3, this session's) fiddled with Keep while leaving capture inside the
spawn helpers. 5e8's constraint (spawns answer at Sync & S) and 213's observation (IOTask already
accepts the wide Keep) are simultaneously satisfiable, because main already does it: put capture
in the combinator, whose row carries Abort[E] & Async, and keep every spawn helper Sync by handing
it work that needs no capture.

Two implementations, equivalent in the public contract (the 21 signatures back to
`Isolate[S, Abort[E] & Async, S]`, matching main; init/use/initUnscoped stay Sync):

- (A) main's structure verbatim: combinators do capture + per-element `isolate.isolate(state, _)` +
  restore-at-join; Fiber internals lose their isolate params and spawn effect-free bodies through
  the unscoped path. Most faithful; partially reverts 3bc9822e1d's "task does the crossing".
- (B) keep task-side crossing: combinators do capture only, and pass (isolate, state) down to the
  internals, which stop capturing and call `IOTask(isolate)(state, v)` as they already do. The
  internals' signatures change from `using isolate` to explicit (isolate, state) parameters and
  their rows revert to Sync (no capture inside). Preserves 3bc9822e1d's design point: the task
  still applies isolate and restore, keeping the crossing lazy and the restore deferred into the
  fiber's value.

Either way the derivation, the macro, and the kernel are untouched; kyo-ai and kyo-browser compile
unmodified, as on main.

## 6. The first-parameter-group contract

Every isolate-taking method, on main and on the branch, places the isolate in the FIRST parameter
group, ahead of the computation:

```scala
def race[E, A, S](
    using isolate: Isolate[S, Abort[E] & Async, S]   // main
)(iterable: Iterable[A < (Abort[E] & Async & S)])(using frame: Frame): A < (Abort[E] & Async & S)
```

This placement makes the signature's Keep the admission policy for isolates: the given must
conform to it at implicit-resolution time, whatever the computation argument looks like. Verified
pieces of the mechanism:

- Keep is contravariant (`Isolate[Remove, -Keep, -Restore]`, identical in both kernels). A request
  with Keep = `Abort[E] & Async` and E free admits `LLM.isolate: Isolate[LLM, Async, LLM]` for any
  E (`Abort[E] & Async <: Async`), and admits
  `Browser.isolate.fresh: Isolate[Browser, Async & Abort[BrowserReadException], Any]` by bounding
  E with BrowserReadException. That bound is how the isolate's own failure mode is admitted into
  the combinator's row; BrowserIsolateTest's comments state it directly ("carries
  `Abort[BrowserConnectionException]` in its `Isolate.Keep` channel ... and surfaces").
- The E-bounding is co-determination by the constraint solver, not sequencing: the reviewer
  observed that the recorded failures print `Isolate[Browser, Sync, Browser]` with Remove and
  Restore already solved from the computation argument, so the typer does not seal parameter group
  one before considering later groups. No ordering claim is needed for the admission point to
  hold: with Keep HARDCODED to Sync in the signature, no inference from any argument could ever
  widen it, and every Async-Keep given fails conformance outright.

Consequence: hardcoding Keep = Sync at the 21 sites did not merely move a row. It rejects every
async isolate at the door (Sync is not <: Async, so contravariance excludes any Async-Keep
instance), which is exactly the two symptom classes in section 1, and no change to bodies or
helper plumbing can readmit them while the combinator signatures say Sync. A correct arrangement
must keep the signature Keep wide (with the site's free E) at the level where USER isolates are
supplied, the combinators.

## 7. What a reviewer should attack

- Is the claim in section 2 airtight: is there ANY Isolate-level semantic difference (Contextual
  crossing via Park, marks, the orphan drain) that makes main's combinator-side isolation unsound
  on kernel2, justifying 3bc9822e1d beyond taste?
- 5e8's sentence "widening would put Async in the row of every spawn, which is wrong on its own
  terms": does either proposal violate it? (Both keep the three spawn entry points at Sync.)
- (B)'s explicit (isolate, state) passing: does exposing a captured state as a plain parameter
  across the private[kyo] internals leak anything the using-clause form did not?
- The dead sentence in 5e8: "kyo-ai's isolate gets fixed on its own side" - is there a Sync-Keep
  rewrite of LLM's isolate that 213 missed, which would make module-side migration viable after all?
- Section 6's resolution-order claim: confirm against the typer's actual behavior for a leading
  using clause that the isolate's Keep bounds participate in solving the site's E before/while the
  computation argument is checked, and whether any call-site pattern (explicitly passed isolates,
  `Isolate.derive` at an abstract S) behaves differently under (B)'s explicit-parameter internals.

## 8. Resolution (supersedes proposals A and B)

The user identified the piece both proposals and all three Keep-war commits treated as fixed:
capture's declared row. The interface said `capture: A < (Remove & Keep & S)`, and that Keep is
the whole coupling: a Sync-rowed spawn helper running capture drags Keep into its row, so wide
Keep and Sync helpers could not coexist with capture inside the helpers, which is exactly where
the branch architecture put it.

The landed fix narrows capture to its honest contract:

```scala
def capture[A, S](f: State => A < S)(using Frame): A < (Remove & S)
```

capture reads the spawner's state through the very effects being isolated (Var.use, Env.use,
LLM.state, the bindings), so `Remove & S` is its natural row; the Keep allowance was unearned
generosity. With it gone, a `Sync & S`-rowed helper can run capture for ANY Keep, and the fix
becomes signatures plus two isolate-side adjustments:

- kernel2 `Isolate.capture` row narrowed to `Remove & S` (old kernel untouched).
- The 21 Async combinator signatures back to `Isolate[S, Abort[E] & Async, S]`; the Fiber private
  wrappers and internals widened the same way, their `Sync & S` rows and bodies unchanged; public
  `Fiber.init/use/initUnscoped` untouched at Sync Keep; `Fiber.internal.initUnscoped` added so
  mask/_timeout route their wide isolate to the task-side crossing.
- `LLM.isolate.capture` re-spelled `A < (LLM & S)` (its body never used Async).
- `Browser.isolate.clone` moves its snapshot from capture into the isolate phase (capture reads
  the parent tab like `fresh`; the snapshot's Async already fit via Remove = Browser, only its
  `Abort[BrowserReadException]` needed the move; the failure now lands in the fork that raised it).

The branch's task-side crossing (3bc9822e1d) is preserved end to end. Every capture in the repo
conforms to the narrowed row; clone's failure channel was the single exception.

Reviewer verdicts folded in above: section 2's deriveImpl wording corrected (the seed changed,
Identity to Contextual, the typing did not); section 6's ordering assertion replaced with the
solver's co-determination, which the recorded error text demonstrates.

Open item carried forward: `Fiber.internal.foreachIndexed` applies the isolate per item AND the
task wraps each worker in the same isolate via `IOTask(isolate)`; the reviewer flagged the outer
layer as a redundant crossing (its restore is dead code by construction). Not changed here;
needs its own decision.
