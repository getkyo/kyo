# Adjudication

`flags.sh 31a7b4bde9 -- kyo-kernel/shared/src/main/scala/kyo/proto` against `1f47d6f590` emits **88
rows**. Every id from F1 to F88 appears in exactly one group below, and the groups' ids union to all
88 with no id in two groups.

Verdict vocabulary is the skill's: a category from the cast ladder's closed set, a measurement, a
`moved` provenance naming where the code came from, or `REMOVE`.

This table replaces an earlier one that `kernel-discipline` blocked. What it got wrong is recorded at
the end, because a table that quietly improves teaches nothing.

## Relocated, with the changes named (`moved`)

**F1 to F43** (Eval.scala 50 to 229): the `Defer` and context arms, the three register-absorbing
branches, the foreign-crossing rebuild with its three node shapes, `reenter`, and the own-tag answer
path.

`moved`: this is the baseline's code for these arms. It is **not byte-identical**, and the five
differences are:

1. the loop's type parameters are `T, B, C, S2` where they were `A, B, C, S`, because the loop now
   returns the eval's answer `A < S` and those names shadowed it. Inner scopes shift `S2` to `S3` for
   the same reason. Every `@unchecked` pattern in the range is retyped by that rename and by nothing
   else.
2. the rebuild reads `handler` and `state` from the stack entry where it read `kyo.handler` and `st`
   from `region`'s closure.
3. `reenter`'s rows narrow: `Arrow[P, AX, EX & S]` becomes `Arrow[P, AX, EX]` and its result
   `D < (S & S2)` becomes `D < S3`. This follows from (2): the handler is read at row `Any`, so the
   region's `S` is no longer in scope and `EX & Any` is `EX`. The new result type is the more specific
   one, so every use site still typechecks, and it is asserted nowhere.
4. the three rebuilt nodes are typed at row `Any` rather than `S`, same cause as (3).
5. the arm's entry changes shape: the baseline's `case res: Kyo.Suspend[...] if !(tag <:< tag)` guard
   becomes an `if` over the absorbed `susp`, and the three shapes bind to a `val rebuilt` because the
   arm now reports the region's exit and pops before continuing. Comments are rewritten to describe
   the entry rather than the region frame.

The earlier table claimed a `git diff -w` showed only (1) and (2). That was false: it showed all five.
The claim now names them, and (3) is the one worth a reviewer's attention because it is a signature.

**F6, F9, F12, F23, F27, F31** (the six `new Kyo.Suspend…` allocations inside that range) are counted
in F1 to F43 and are `moved` with no change beyond the retyping: six sites before, six after.

## New: reading the innermost region (`cast`, `carrier`)

**F44, F45** (Eval.scala 242, 248), **F46 to F53** (260 to 273), **F63 to F66** (318 to 320).

```scala
val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
val state   = stack.state.asInstanceOf[VX]
val cont    = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]
```

`justified: erasure-forced`, the closed set's "array element re-typing at the storage boundary
(`Stack`)", which the cast ladder names with this carrier. Entries are strictly heterogeneous, one set
of types per open region, so the assertion cannot be carried in a signature.

Two of the three are narrower than they look. `Handler[E, A, B, -S, State]` and `Arrow[-A, +B, -S]`
are contravariant in the row, so asserting them at `Any` is the **subtype** end and conforms wherever
a row is required; the row is not claimed, it is widened out of the way. Row `Any` rather than
`Nothing` was a compile error first: at `Nothing` these are the supertype end and cannot be passed.
The genuine erasure assertion is `state`, `Any` to `VX`, the same shape as `Nested.unnest`'s.

The `carrier` rows in this set (F48, F51, F53, F64) are the `Any` **inside** those types, not a value.
**No value in this change is typed `Any`, `Any < Nothing`, or `Null`.**

**F16, F19, F20, F22, F26, F30, F34, F37, F39, F41, F43** are the same widening inside the relocated
range, counted in F1 to F43 above and repeated here only so the `carrier` class reconciles.

## New: the eval's answer at the boundary (`cast`)

**F13** (`if stack.isEmpty then susp.asInstanceOf[A < S]`), **F42** (`res.asInstanceOf[A < S]`).

`justified: representation assertion`. The machine has finished with no region left to complete, so
what it holds is the eval's answer. Both replace the baseline's `asInstanceOf[C < S]` at the same two
sites. They are the only two places the eval's own type is asserted, which is the payoff of the
signature change: the old shape needed one at every region boundary.

**F59** (`case suspend: Kyo.SuspendContextDefault[VX, CX, A, S] @unchecked`) is the baseline's own
boundary pattern, unmoved.

## New: the guard (`mutability`, `cast`)

**F54 to F58** (`var curr`, `var ctx`, `var out`, `var settled`, `while !settled`), **F60 to F62**
(`var ex`, `var unwinding`, `while unwinding`), **F67, F68** (`end while`, two false positives on the
keyword).

`justified`: interpreter mutability, the concession the skill calls "the accelerator's engine room",
and here it is what buys the property. Contract, all four parts:

- justification: the first shape of this guard had `recovered` call `run` from inside `run`'s catch,
  so every recovered region held frames until the eval settled and N throw-and-recover cycles cost
  O(N) stack. A loop is what makes declining and recovering both cost nothing.
- minimal scope: five locals in `apply`, none escaping, none read after the loop but `out`.
- protective measure: `out` is initialised from `v`, so no sentinel and no `Null`; `settled` is the
  only exit; the unwind's `ex` only ever moves forward to the failure a recover itself raised, which
  is what the nested per-region tries did.
- pinning test: `EvalTest` "regions that fail and recover in sequence cost no stack", 10000 cycles.

**F63 to F66** are the storage-boundary reads inside the unwind, adjudicated with that group above.

## New: the eval's own budget (`cast`)

Not flagged by the script, and recorded because `kernel-discipline`'s catalog covers what the script
cannot see. `ffc1819ecc` adds `Safepoint.get`, `save` and a `finally restore`; `1f47d6f590` adds
`Safepoint.reset(slot)` in the guard.

`justified: measurement`. A throw leaves every strict application between it and the guard without its
matching `exit`, so the budget leaks once per recovery. Measured over 200 to 12800 recoveries: flat
per-recovery cost with the reset, livelock past 800 without it, and the baseline livelocks past 400.
The guard is where the true recursion depth is known to be zero.

## New: the region stack (`new-type`, `mutability`, `allocation`, `carrier`)

**F70** (`final private[kyo] class Stack`)

`justified`: no existing type holds a growable heterogeneous sequence of open regions, and the entries
must be reachable by the eval while a region is open, which a value in the pending union is not. Its
columns stay erased rather than becoming a typed entry object because the cast ladder already permits
the storage-boundary read and a typed entry would allocate per region to buy what the ladder permits
for free. (The earlier table justified this as "the carrier the reviewer specified", which is who
asked rather than why.)

**F71, F74, F76, F78, F80** (the four column `var`s and `size`)

`justified`: interpreter mutability. Contract: the region chain is the only representation whose depth
is not the Java stack's; one instance per `Eval.apply`, reachable from nothing that leaves it; entries
written only by the eval's own arms and holding complete values; pinned by `ArrowEffectTest` "handles
nested per recursion step in bounded stack", `EvalTest` "a nested eval shares the thread's stack and
sees none of the outer regions", and the two multi-shot cases.

**F72, F75, F77, F79** (the four `new Array` in the constructor), **F84, F86, F87, F88** (the four in
`grow`)

`justified: measurement`. Four arrays per eval and a doubling copy on growth. The full benchmark class
on both legs shows no confirmed regression on any of 20 rows, the region-heavy ones included; the one
`-f 1` outlier does not reproduce at `-f 3` (49.346 ± 0.558 against 48.985 ± 0.416). See
`evidence.md`.

**F73, F81, F82, F83, F85** (`Array[Any]`, `state: Any`, the accessor rows)

`justified: erasure-forced`, the storage boundary itself. This is the column whose type genuinely
cannot be written, and it is why the reads above are asserted rather than checked.

## False positives, adjudicated as such

**F67, F68** (`end while`) and **F69** (`Handler.scala:22`, the word "while" inside a scaladoc
sentence). The script is recall-tuned and tolerates these by design; they are rows that must be given
a verdict, not defects.

## Nothing removed

No row's verdict is `REMOVE`. The constructs `rulings.md` names, an `Any` or `Null` value carrier, a
`var` outside the engine room, a new type without an argument, a placeholder body, banned vocabulary,
are absent from the diff rather than justified in it.

## What the earlier table got wrong

Recorded because the escape matters more than the correction:

- it claimed every row had a verdict and one (the state column's `var`) had none;
- eight allocation rows carried `measurement pending`, which is not an accepted verdict, and stayed
  that way after the benchmarks closed the question because only `evidence.md` was updated;
- two rows were verdicted `moved` against code that does not exist at the control commit;
- its `moved` verification sentence was false, and narrower than the claim it licensed: a grep for the
  `handler`/`state` reads cannot see a signature change;
- two entries adjudicated a different line than their id named;
- `Stack`'s verdict appealed to who asked for it rather than to a category.
