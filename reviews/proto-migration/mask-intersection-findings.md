# Mask over an intersection of effects: what actually happens, in both kernels

The requirement: `Mask[Ask & Say](v)` masks both effects with one mask, `run` re-exposes them,
and individual `Ask` and `Say` handlers outside `run` answer the re-raised operations.

Investigated by experiment against the reference (`kyo.Mask`), with the scratch reproduction at
`kyo-kernel/shared/src/test/scala/kyo/MaskUnionScratchTest.scala` (a dev artifact, to be folded
into the real corpora when the fix lands).

## The dispatch algebra, and who implements which

The region search direction decides everything here, and the two kernels disagree.

The reference searches with the handler on the left: `Stack.find` at
`kyo/kernel/internal/Stack.scala:233`, `h.tag <:< t` where `t` is the suspension's tag. A
handler answers an operation when the handler's tag is a subtype of the operation's. Under
kyo.Tag's standard algebra (intersection on the left takes the exists arm,
`Tag.scala:433-436`), an intersection handler tag is precisely the multi-effect handler:
`(Ask & Say) <:< Ask` holds, `(Ask & Say) <:< Say` holds, so one region tagged `Ask & Say`
catches operations of both effects. This is the deliberate mechanism, and the pinned test
"a union tag subsumes both effects the way regions are found"
(`kernel/ArrowEffectTest.scala:894-899`) is the same direction read from the union side.

The proto searches with the suspension on the left: `proto/kernel/internal/Eval.scala:136`,
`susp.tag.erased <:< handler.tag.erased`. Under that direction an intersection handler tag
takes the forall arm on the right and catches nothing: `Ask <:< (Ask & Say)` is false.

## The two kernels pin opposite sub-tag semantics

The divergence is not latent; each corpus pins its own direction, on the same scenario:

- Reference, "a supertype handler leaves a subtype effect in the row"
  (`kernel/ArrowEffectTest.scala:450-456`): a `Tag[Ask]` handler does NOT answer an
  `AskSub`-tagged operation, the result row keeps `AskSub`, and the doc comment states the
  rationale: the obligation stays a compile error rather than a suspension nothing answers.
  Type and runtime agree.
- Proto, "answers operations of a subtype effect" (`proto/kernel/ArrowEffectTest.scala:451-455`):
  a `Tag[Ask]` handler DOES answer the `AskSub` operation, while the result row still says
  `Int < AskSub`. The runtime discharges an obligation the type system still records as owed:
  the proto's pin is incoherent with its own row typing, and it silently deviated from the
  corpus its header claims to transcribe.

Consequence: the proto's dispatch direction must flip to the reference's
(`handler.tag <:< susp.tag`), and its corpus must restore the reference's two sub-tag pins.
This precedes any intersection-mask work in the proto: the intersection mechanism only exists
in the reference's direction.

## What `Mask[Ask & Say]` does in the reference today

1. It compiles, and the mask catches and tunnels both effects' operations. Verified by the
   scratch run: the failure happens after the tunnel, not at it.
2. With `Mask.run[Ask & Say]` (explicit): KyoBug. Inference solved apply's `E2` as
   `Ask & Say & ArrowEffect[-(String | Unit), +(Int & Unit)]`, accreting an ArrowEffect
   refinement to satisfy `E2 <: ArrowEffect[I, O]`. The tunneled operation is tagged
   `Mask[E2-with-junk]`, run's region is tagged `Mask[Ask & Say]`, `Mask[S]` is invariant, the
   tags disagree, and the operation crosses everything to the top.
3. With `Mask.run(v)` (inferred): the tags match, the tunnel lands, and the junk moves into the
   row instead: the result row keeps `ArrowEffect[[X] =>> Unit | String, [B] =>> Int & Unit]`
   as a member nothing can ever handle, so the computation never reduces to `Any` and `.eval`
   does not exist. Compile error at the use site.

## The structural gap: one tag cannot serve both ends of the tunnel

Even with inference fixed, the current clause re-raises the payload at the named tag:

```scala
ArrowEffect.suspend[C](tag, input)   // tag = Tag[E2], the mask's own tag
```

Under the reference's direction, catching and landing want opposite algebra:

- Catching wants the intersection: the mask's tag must be `<:<` each operation tag it catches,
  and `Ask & Say` is that tag.
- Landing wants the operation's own tag: a payload op tagged `Ask & Say` is answered only by a
  handler whose tag is `<:<` `Ask & Say`, which `Ask` alone is not (forall on the right). The
  individual outer handlers never see it.

For a single-effect mask the two coincide, which is why every existing test passes. For an
intersection they cannot: the tunnel loses the operation's identity, and no choice of `E2`
recovers it.

## The fix shape (for ruling, not yet implemented)

1. Proto: flip the dispatch direction to the reference's, restore the reference's sub-tag pins
   in the proto corpus, re-run everything. Independent of Mask and needed regardless.
2. Both kernels: the clause needs the caught operation's own tag, so the payload re-raises at
   it and lands exactly. The operation's tag is on the suspension node already; what is missing
   is surface: a `handleCont` variant whose clause receives the tag (or an equivalent
   primitive), and `Mask.apply` re-raising with it.
3. `Mask`'s own suspension should be tagged `Mask[E]` at the user-named `E` on both ends
   (apply and run), taking inference's `E2` out of the tag entirely, which kills the accretion
   failure by construction.

## Status

Nothing implemented; the scratch reproduction stands. The proto's `MaskTest` corpus is green
but tests only single-effect masks, which is the degenerate case where the directions agree.
