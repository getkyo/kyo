# Prior art: a resource inside a continuation that is handed out

Companion to `handed-out-remainder-report.md`. It records how other effect systems and
streaming libraries treat a bracket or finalizer that sits inside a continuation which a handler
steps, hands out, drops, or replays. Sources are quoted or cited inline; the Unison sources were
read from the base library release 7.19.2 through the Share API.

## 1. Unison (abilities, multi-shot continuations)

Handlers have full control of the continuation: "A handler can choose to call the continuation or
not, or to call it multiple times" (language reference, abilities and ability handlers).

`Exception.bracket` is `catch` plus a re-raise, nothing more:

```unison
abilities.Exception.bracket provision final run =
  r = provision()
  finally (do final r) do run r

abilities.Exception.finally end ex =
  r = catch ex
  end()
  reraise r
```

Its doc carries the caveat, verbatim:

> 🚨 It is possible that `onComplete` never runs, if `op` or `make` use an ability that can
> terminate the computation, such as `Abort` or `Throw`. Using `bracket` with such computations
> may lead to resource leaks. It's best to convert those abilities to `Exception` before calling
> `bracket`, for example with `Abort.toException` and `Throw.toException`.

The base `Stream` is an ability (`structural ability Stream e where emit : e ->{Stream e} ()`),
and its combinators are exactly the shapes in question:

```unison
data.Stream.take! n s =
  h n = cases
    { emit a -> k } ->
      emit a
      if n > 0 then handle k() with h (n - 1) else None      -- k dropped at the limit
    { r }           -> Some r
  if n > 0 then handle s() with h (n - 1) else None

data.Stream.zipWith! f sa sb =
  readA sb = cases
    { emit a -> sa } -> handle sb() with readB a sa           -- sa stashed, re-entered later
    { r }            -> r
  readB a sa = cases
    { emit b -> sb } ->
      emit (f a b)
      handle sa() with readA sb
    { r }            -> r
  handle sa() with readA sb

data.Stream.splitAt n s =
  go acc n s = if n == 0 then (acc, s) else handle s() with h acc n
  h acc n = cases
    { emit a -> k } -> go (acc :+ a) (n - 1) k
    { r }           -> (acc, do r)
  go [] n s                                                   -- the rest is the continuation
```

So Unison's rule is: the finalizer is ordinary code inside the captured continuation. A remainder
handed out by `splitAt`, or stashed and re-entered by `zipWith!`, releases when it completes,
because `finally`'s `end()` is on its return path. A remainder dropped by `take!` never releases,
and the language documents that as a leak the user must avoid by turning non-resuming abilities
into `Exception` (unwinding). A continuation resumed twice runs `end()` twice. There is no scope
object, no backstop, no exactly-once guard, and nothing runs at a handler's exit.

## 2. Koka (`finally`, `initially`)

The Koka book, section "Initially and Finally", verbatim:

> With arbitrary effect handlers we need to be careful when interacting with external resources
> like files. Generally, operations can never resume (like exceptions), resume exactly once
> (giving the usual linear control flow), or resume more than once.

`finally` "takes as its first argument a function that is always executed when either returning
normally, or when unwinding for a non-resuming operation". A dropped continuation is unwound
through its `finally` frames, so the take-style drop releases. And then:

> The current definition is robust for operations that never resume, or operations that resume
> once, but there is still trouble when resuming more than once. If someone calls `choice` inside
> the action, the second time it resumes the file handle will be closed again which is probably
> not intended. There is active research into using the type system to statically prevent this
> from happening.

`initially` is the counting workaround for multi-shot: its callback runs the first time and again
"every time a particular resumption is resumed more than once", and the example keeps a counter so
`hclose` runs on the last resumption only. Same model as Unison for hand-out (custody with the
continuation), plus unwinding on drop, and the multi-shot case left to a manual counter.

## 3. OCaml 5 (one-shot continuations)

The manual, effects chapter: "every captured continuation must be resumed either with a continue
or discontinue exactly once"; a second use raises `Continuation_already_resumed`. `discontinue`
raises an exception into the continuation, so `Fun.protect ~finally` and friends on the captured
stack run. For a continuation nobody resumes: "One may install a finaliser on the captured
continuation to ensure that the resources are freed", with the recommendation "that the user take
care of resuming the continuation exactly once rather than relying on the finaliser". The
retrofitting paper gives the reason for one-shot: "the linearity discipline is easily broken if
continuations are allowed to resume more than once".

Guarantee by construction here is linearity: a handed-out continuation carries its finalizers and
must be continued or discontinued exactly once; multi-shot is excluded entirely.

## 4. Links, "Soundly Handling Linearity" (POPL 2024)

Abstract, verbatim: "Whereas linear type systems bake in the assumption that continuations are
invoked exactly once, effect handlers allow continuations to be discarded or invoked more than
once. This mismatch leads to soundness bugs in existing systems such as the programming language
Links ... We introduce control flow linearity as a means to ensure that continuations are used in
accordance with the linearity of any resources they capture, ruling out such soundness bugs."

Operations are classified control-flow-linear (continuation resumed exactly once) or
control-flow-unlimited, and "linear resources can only be used in the continuations of
control-flow-linear operations". A bracket between a `choice`-style operation and its handler is a
type error. This is the only system found that gives the multi-shot case a guarantee, and it does
so by refusing the program. Congard, Munch-Maccagnoni and Douence (2025, arXiv 2510.23517) take
the other route for exceptions: "adjoining default destruction actions to types, as inspired by
C++/Rust destructors", so dropping a linear value runs its destructor.

## 5. fs2 (scope tree, `StepLeg`, leases)

`internal/Scope.scala`: "When stream interpretation starts, one `root` scope is created. Scopes
are then created and closed based on the stream structure. Every time a `Pull` is converted to a
`Stream`, a scope is created." "A child scope never outlives its parent scope." "The stream
interpreter guarantees that resources allocated in a scope are always released when the scope
closes." PR #1574 made a scope open around each `bracket`, so "`bracket(r)(..) ++ s` now
guarantees that `r` is released when `s` is evaluated". The guide: "once and only once semantics
for resource cleanup actions introduced by the `Stream.bracket` function".

Stepping is where the design shows:

- `pull.uncons` returns the tail as `streamNoScope`: the tail keeps running in the scope that was
  current when it was stepped, and that scope's close is the backstop.
- `StepLeg` exists for interleaving: "unlike `uncons`, it keeps track of stream scope independently
  of the main scope of the stream. This assures, that after each next `stepLeg` each Stream `leg`
  keeps its scope when interpreting." `zipWith_` is written on `stepLeg` for both legs.
- `Pull.extendScopeTo(s)` leases the current scope's resources to a stream evaluated later:
  "preventing them from being finalized until after `s` completes execution, even if the returned
  pull is converted to a stream, compiled, and evaluated before `s` is compiled and evaluated".
  A crossing the scope tree cannot express is done by an explicit lease with a cancel.
- Issue #3478 is the failure mode when the remainder leaves the scope: "if you do
  `stream.pull.uncons` and then concurrently process the tail, things will blow up if `stream`
  happens to contain any kind of zipping", a scope lookup failure because the leg's scope was
  closed when the pull that stepped it ended.

So fs2 is custody with the remainder, tracked by a scope id the remainder carries, with the
enclosing scope (root at the end of `compile`) as the drain of anything not completed, and an
explicit lease for a remainder that must outlive the scope it was stepped in.

## 6. ZIO 2 streams (`Scope` in the type)

`ZStream.acquireReleaseWith` is `scoped(ZIO.acquireRelease(acquire)(release))`. Stepping requires
a `Scope` in the environment, by signature:

```scala
def toPull(implicit trace: Trace): ZIO[R with Scope, Nothing, ZIO[R, Option[E], Chunk[A]]]
def peel[...](sink: => ZSink[...]): ZIO[R1 with Scope, E1, (Z, ZStream[Any, E, A1])]
```

`peel`'s doc: "returns both the `Z` and the rest of the `ZStream` in a scope. Like all scoped
values, the provided stream is valid only within the scope." `ZChannel.toPullIn` registers the
executor's close as a finalizer of that scope (`scope0.addFinalizerExit(exit => exec.close(exit))`),
so whatever the stepped channel still holds is released when the scope closes. `zip` is
`combineChunks`, which forks both producers into a scope and hands chunks over through a handoff;
the resources of each side belong to that scope. The guarantee is by construction at the type
level: a remainder cannot exist without a `Scope` to belong to. Promptness is not guaranteed
(issue #7175: a flattened `acquireReleaseExitWith` releases after the next outer element rather
than right after the inner one).

## 7. Haskell (`conduit`, `pipes-safe`, `streaming`)

- `conduit` 1.3 removed finalizers from the conduit type. `bracketP` needs `MonadResource`, and the
  connect-and-resume operators (`$$+`, `$$++`, `$$+-`, the peel analog) carry the note: "In previous
  versions, this would cause finalizers to run. Since version 1.3.0, there are no finalizers in
  conduit." Resources belong to `ResourceT` and are released when `bracketP`'s inner conduit
  completes or when `runResourceT` ends.
- `pipes-safe`: `SafeT` registers finalizers; "All unreleased finalizers are called at the end of
  the `SafeT` block, even in the event of exceptions." `bracket` "also protects against premature
  termination", through that block end, not promptly. The 2013 Yesod post on the same problem:
  "there's no way to detect termination of a stream. As soon as one component of a pipeline
  terminates, the rest of the pipeline terminates also", so "the data producer is never given a
  chance to perform its own cleanup".
- `streaming` has no resource handling; `streaming-with` exists because "streaming doesn't
  support prompt finalisation", and it says this "is a limitation of the `Stream` type, not on how
  we choose to allocate and manage resources". Its idiom is the `with` callback: acquire outside,
  use the stream inside the callback, release after.

## 8. What this says about the five pins

Every system found keeps the resource with the remainder and drains at an enclosing boundary.
None releases at the exit of the handler that stepped the stream, which is what Q4 does. The
enclosing boundary is what differs:

| system | custody of a stepped remainder | backstop for a dropped remainder | multi-shot |
|---|---|---|---|
| Unison | the continuation | none, documented leak | `end()` runs per resumption |
| Koka | the continuation | unwind through `finally` on drop | double close, documented; `initially` counter |
| OCaml 5 | the continuation | `discontinue` (required), GC finaliser as last resort | forbidden |
| Links | the continuation | type error if the resource is captured by a droppable op | type error |
| fs2 | scope id carried by the leg | enclosing scope close, root at `compile` end; `extendScopeTo` lease to cross | n/a |
| ZIO 2 | the `Scope` required to step | that scope's close | n/a |
| conduit / pipes-safe | `ResourceT` / `SafeT` block | block end | n/a |

Mapped onto the report's directions:

- "Custody with the continuation, eval's end as backstop" (kernel2's rule) is Koka's and OCaml's
  model with the eval as the discontinue point, and fs2's model with the eval as the root scope.
  It is the common answer for zip, splitAt, and the peels.
- "A law on users" (Q4 kept, resource scoped outside the combinator) is the Haskell and ZIO answer:
  ZIO makes it a type (`Scope` in the row of `toPull` and `peel`), Haskell makes it a monad the
  pipeline runs in. In Kyo terms that is `Scope` in the row of `Emit.runFirst` and the combinators
  built on it, which is a guarantee by construction for `Scope`-rowed resources and leaves
  `Sync.ensure` with nothing to lift, as the report notes.
- The `Choice.runStream` pin is the multi-shot case. No system handles it by construction except
  Links, which rejects the program; Koka documents the double release and offers a counter. Kyo's
  bracket cell already makes the release exactly-once, which is stronger than Koka's `finally` but
  does not say which branch's completion is the last one; that pin needs its own ruling
  regardless of what happens to the hand-out lane.

## Sources

- Unison base 7.19.2 via `https://api.unison-lang.org/users/unison/projects/base/releases/7.19.2/definitions/by-name/<name>`:
  `abilities.Exception.bracket`, `abilities.Exception.bracket.doc`, `abilities.Exception.finally`,
  `data.Stream`, `data.Stream.take!`, `data.Stream.zipWith!`, `data.Stream.splitAt`.
- Unison language reference: https://www.unison-lang.org/docs/language-reference/abilities-and-ability-handlers/
- Koka book, "Initially and Finally", "Resuming more than once": https://koka-lang.github.io/koka/doc/book.html
- OCaml manual, effect handlers: https://ocaml.org/manual/5.5/effects.html ; Sivaramakrishnan et al., "Retrofitting Effect Handlers onto OCaml": https://arxiv.org/pdf/2104.00250
- Tang, Hillerström, Lindley, Morris, "Soundly Handling Linearity", POPL 2024: https://arxiv.org/abs/2307.09383 ; SIGPLAN blog summary: https://blog.sigplan.org/2024/08/12/soundly-handling-linearity/
- Congard, Munch-Maccagnoni, Douence, "Linear Effects, Exceptions, and Resource Safety": https://arxiv.org/abs/2510.23517
- fs2 `internal/Scope.scala`, `Stream.scala` (`StepLeg`, `zipWith_`, `ToPull.uncons`), `Pull.scala` (`extendScopeTo`, `stepLeg`) at `typelevel/fs2` main; PR #1574: https://github.com/typelevel/fs2/pull/1574 ; issue #3478: https://github.com/typelevel/fs2/issues/3478 ; issue #1678: https://github.com/typelevel/fs2/issues/1678 ; guide: https://fs2.io/#/guide?id=resource-acquisition
- ZIO `ZStream.scala`, `ZChannel.scala` at `zio/zio` series/2.x; docs: https://zio.dev/reference/stream/zstream/resourceful-streams/ ; issue #7175: https://github.com/zio/zio/issues/7175
- conduit 1.3.6.1 `Data.Conduit`: https://hackage-content.haskell.org/package/conduit-1.3.6.1/docs/Data-Conduit.html ; `Pipes.Safe`: https://hackage.haskell.org/package/pipes-safe/docs/Pipes-Safe.html ; streaming-with: https://github.com/haskell-streaming/streaming-with ; Snoyman, "The core flaw of pipes and conduit": https://www.yesodweb.com/blog/2013/10/core-flaw-pipes-conduit
