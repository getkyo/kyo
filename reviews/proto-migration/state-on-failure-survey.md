# Survey: does state survive a recovered failure?

The question the exit law's failed-extent lane raises: when a failure is caught, do state and
context updates made inside the failed extent persist, or roll back? Surveyed across effect
systems, effect-handler languages, and this repository's own main branch.

## Family 1: semantics decided by handler or transformer nesting (both behaviors available)

**Haskell monad transformers.** The canonical case. `StateT s (ExceptT e m)` and
`ExceptT e (StateT s m)` run identical code to different answers: the effectful project's
transformers comparison demonstrates `StateT Int (ExceptT IO)` yielding `Right ((), 2)` where
`ExceptT (StateT Int IO)` yields `(Right (), 3)`, and names the hazard: "seemingly unrelated code
change, i.e. rearranging the order of monad transformers in the stack will lead to subtle change
of behavior in a completely different part of the application." State threaded inside the error
monad rolls back at a catch; state outside persists.

**Effect-handler languages (Koka, OCaml, Unison, Eff).** The same choice, spelled as handler
nesting: a state handler threads its state through resumptions, so aborting a continuation aborts
the state accumulated in it when the state handler sits outside the exception handler, and keeps
it when it sits inside. The higher-order-effects literature states it directly: one may "treat
exceptions in a transactional manner, where raising an exception represents aborting a
transaction and snapping back the state to what it was before entering a catch, which can be
achieved by reordering handlers." OCaml's manual frames handlers as generalizing exceptions
(discard the continuation) and state (resume it), with the interaction falling out of nesting.
Turbolift (Scala 3 algebraic effects) is in this family by construction: handlers apply in an
explicit chain (`handleWith(state).handleWith(error)` versus the reverse), each application
delimiting its effect's scope.

## Family 2: updates persist through a caught failure (mutable representation)

**ZIO `FiberRef`.** Fiber-local mutable state with copy-on-fork and merge-on-join semantics;
recovery combinators do not restore values, only scoped operators (`locally`) do, because the
cell is mutation outside the unwind.

**Cats Effect `IOLocal`.** Same shape: fiber-local mutable storage, updates visible for the rest
of the fiber regardless of `handleErrorWith`.

**PureLogic (Scala 3 capabilities).** `State(initial) { ... }` "creates a mutable variable scoped
to that block", ST-monad style, and `Abort[E]` "uses Scala 3's boundary/break mechanism, which
compiles down to a local throw/catch." A mutable variable plus a local throw means updates before
an abort persist through recovery; the docs do not state the interaction, it follows from the
representation.

**effectful (Haskell).** The notable member: it chose persistence deliberately, as a fix for
family 1's fragility: "State effects never lose updates and they're not affected by the order of
effects on the stack in any way."

**kyo, main branch, today.** Region state is held live in the mutable stack entry, and a caught
`Abort` does not touch the regions below it, so updates persist; `Var.isolate` (merge, discard,
update strategies) exists as the explicit opt-in for other semantics. The revealing artifact:
`AbortTest.scala` has a pin named "should not modify state on Abort failures" whose assertions
never inspect the mutated run's final state (the second assert opens a fresh `Var.run(42)`), so
the suite documents rollback intent over persist machinery. This is the oddness the transition
gets to resolve.

## Family 3: rollback by construction

**STM**, everywhere it appears, is the strong form: a failed transaction's writes are never
visible. **Scoped rebinding** constructs (Scheme's `parameterize`, JDK `ScopedValue`, Lisp
dynamic-binding stacks) restore on every exit, exceptional included, though they have no update
operation to dispute.

## Reading for the proto

1. Both defaults ship in serious systems. Family 2's members are persist-by-representation
   (mutation outside the unwind); family 1's members expose both and are notorious for the
   fragility of the choice riding an ordering; family 3 chooses rollback where the construct is
   explicitly transactional.
2. The proto's context is threaded state, so rollback on a failed extent is what its
   representation honestly means, the same way persistence is what FiberRef's representation
   honestly means. The old kernel's persistence was also representational, mutation in place,
   never a decision.
3. The proto's combined law reads as: derivation at installation, persistence for the extent on
   completion, rollback on failure. That is family 1's transactional corner chosen deliberately
   and uniformly, rather than left to an ordering, with kyo main's own test naming suggesting the
   intent was always this.

Sources:
- [effectful: transformers.md](https://github.com/haskell-effectful/effectful/blob/master/transformers.md)
- [School of Haskell: exceptions and monad transformers](https://www.schoolofhaskell.com/user/snoyberg/general-haskell/exceptions/exceptions-and-monad-transformers)
- [OCaml manual: effect handlers](https://ocaml.org/manual/5.4/effects.html)
- [Retrofitting Effect Handlers onto OCaml](https://arxiv.org/pdf/2104.00250)
- [Handling Higher-Order Effects](https://arxiv.org/pdf/2203.03288)
- [Koka book](https://koka-lang.github.io/koka/doc/book.html)
- [Unison: abilities and ability handlers](https://www.unison-lang.org/docs/language-reference/abilities-and-ability-handlers/)
- [Unison: error handling with abilities](https://www.unison-lang.org/docs/fundamentals/abilities/error-handling/)
- [ZIO: FiberRef](https://zio.dev/reference/state-management/fiberref/)
- [Cats Effect: IOLocal](https://typelevel.org/cats-effect/docs/core/io-local)
- [Introducing PureLogic](https://blog.pierre-ricadat.com/introducing-purelogic/)
- [PureLogic repository](https://github.com/ghostdogpr/purelogic)
- [Turbolift: overview](https://marcinzh.github.io/turbolift/overview.html)
- kyo `origin/main`: `kyo-prelude/shared/src/test/scala/kyo/AbortTest.scala:775-783`, `kyo-prelude/shared/src/test/scala/kyo/VarTest.scala:119-149`
