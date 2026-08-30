# Adjudication

`flags.sh 31a7b4bde9 -- kyo-kernel/shared/src/main/scala/kyo/proto` emits 76 rows. Every row has a
verdict below. Rows sharing one site and one reason are grouped, and each group lists its ids so the
count reconciles against the script's output.

Verdict vocabulary is the skill's: a category from the cast ladder's closed set, a measurement, a
`moved` provenance naming where the code came from, or `REMOVE`.

## Relocated without change (`moved`)

**F1 to F13, F19, F21 to F33, F35, F36, F38, F40, F42, F44, F45, F46, F49, F52, F56, F57, F63**
(Eval.scala 42 to 186, and the register-absorbing and rebuild blocks)

`moved`: the `Defer` and context arms, the three register-absorbing branches, and the whole
foreign-crossing rebuild including `reenter` are the baseline's own code. Two mechanical differences,
both forced and neither semantic:

- the loop's value and row type parameters are named `T` and `S2` rather than `A` and `S`, because
  the loop now returns the eval's answer `A < S` and those names had shadowed it. The `@unchecked`
  patterns are the baseline's, retyped by the rename.
- the rebuild reads `handler` and `state` from the stack entry instead of from `region`'s closure,
  which is the same two values by the same names.

`git diff -w --ignore-blank-lines` over these ranges shows only those two substitutions.

## New: reading the innermost region (`cast`)

**F15, F17, F18** (Eval.scala 116 to 118), **F47, F49** (256 to 257), **F54, F55, F56** (281 to 283)

```scala
val handler = stack.handler.asInstanceOf[Handler[EX, AX, Y, Any, VX]]
val state   = stack.state.asInstanceOf[VX]
val cont    = stack.cont.asInstanceOf[Arrow[Y, Any, Any]]
```

`justified: erasure-forced`, the closed set's "array element re-typing at the storage boundary
(`Stack`)", which the cast ladder names with this exact carrier. The entries are strictly
heterogeneous, one set of types per open region, so the assertion cannot be carried in a signature.

Two of the three are narrower than they look. `Handler[E, A, B, -S, State]` is contravariant in the
row and `Arrow[-A, +B, -S]` likewise, so asserting them at row `Any` is the **subtype** end and
conforms wherever a real row is wanted; the row is not being claimed, it is being widened out of the
way. The genuine erasure assertion is `state`, `Any` to `VX`, which is the same shape as
`Nested.unnest`'s and is what the storage boundary costs.

Row `Any` rather than `Nothing` is deliberate and was a compile error first: at `Nothing` these are
the supertype end and cannot be passed anywhere a row is required.

## New: the eval's answer at the boundary (`cast`)

**F14** (`if stack.isEmpty then susp.asInstanceOf[A < S]`), **F46** (`res.asInstanceOf[A < S]`)

`justified: representation assertion`. The machine has finished with no region left to complete, so
what it holds is the eval's answer. Both replace the baseline's `asInstanceOf[C < S]` at the same two
sites; the assertion is the baseline's, restated at the loop's corrected result type. They are also
the only two places the eval's own type is asserted, which is the point of the signature change: the
old shape needed one at every region boundary.

**F50** (`stack.cont.asInstanceOf[Arrow[Y, A, S]]`, in `recovered`)

`justified: erasure-forced`, storage boundary as above, asserted at the eval's answer type because a
recovered region's result flows to the eval's result through `run`.

## New: `Any` in a type position (`carrier`)

**F16, F34, F37, F39, F41, F43, F48, F51, F53** (Eval.scala 116, 194, 204, 209, 219, 221, 256, 259,
265)

`justified: erasure-forced`, the row half of the storage-boundary casts adjudicated above. Every one
is the `Any` inside `Handler[…, Any, …]`, `Arrow[Y, Any, Any]`, or `Nested.unnest[Y < Any]`, which is
the variance widening, not a carrier. **No value in this change is typed `Any`, `Any < Nothing`, or
`Null`**, which is the rulings entry these rows exist to make checkable.

**F20, F22, F26, F30** (`Y < Any` on the rebuilt node and its three shapes) are the same widening on
the relocated block, forced by reading the handler from the entry.

**F69, F70, F71, F73** (Stack.scala 28, 46, 47, 55): `Array[Any]` for the state column and `state:
Any` on its accessor. `justified: erasure-forced`, the storage boundary itself. This is the column
whose type genuinely cannot be written, and it is why the reads above are asserted rather than
checked.

## New: the region stack (`new-type`, `mutability`, `allocation`)

**F58** (`final private[kyo] class Stack`)

`justified`: the carrier the reviewer specified, and no existing type holds a growable heterogeneous
sequence of open regions. Entries stay columnar rather than becoming a typed entry object, because
the ladder already permits the storage-boundary cast and a typed entry would allocate per region to
buy what the ladder permits for free.

**F59, F62, F64, F66, F68** (the four column `var`s and `size`)

`justified`: interpreter mutability, the concession the skill lists as "the accelerator's engine
room". Contract, all four parts:

- justification: the region chain is the only thing whose depth was the Java stack's; a heap chain is
  what removes that.
- minimal scope: one instance per `Eval.apply`, created there and reachable from nothing that leaves
  the eval.
- protective measure: entries are written only by the eval's own arms, and what they hold are
  complete values, so an entry hands back exactly what the equation says it holds. A nested eval
  builds its own, so it answers for its own regions only.
- pinning tests, all existing and all run: `ArrowEffectTest` "handles nested per recursion step in
  bounded stack" (the defect); `EvalTest` "a nested eval shares the thread's stack and sees none of
  the outer regions" (per-eval scoping); `EvalTest` "the captured continuation is multi-shot" and
  "each shot of a multi-shot capture resumes from capture-time state" (a resumed shot re-installs
  from the node, not from a stack an earlier shot mutated); `PendingTest` region-crossing cases (the
  rebuild after relocation).

**F60, F63, F65, F67** (`new Array[…](8)` in the constructor) and **F72, F74, F75, F76** (the four
`new Array` in `grow`)

`justified: measurement pending`. Four arrays per eval and a doubling copy on growth. This is the one
group whose verdict is not yet a number: the benchmark rows are still to run, and the EVIDENCE phase
gate covers it. **This row set blocks the package until those numbers exist.**

**F6, F9, F12, F23, F27, F31** (`new Kyo.Suspend…` in the absorb and rebuild blocks)

`moved`: the baseline's own allocations, unchanged in count and shape.

## Nothing removed

No row's verdict is `REMOVE`. The constructs the rulings file names, an `Any` or `Null` carrier, a
`var` outside the engine room, a new type without an argument, a placeholder body, are absent from
the diff rather than justified in it.

## Open against the gate

One group, the `grow` and constructor allocations, carries `measurement pending`. Until the
benchmark rows exist this table does not satisfy the REVIEW gate, and the package cannot be proposed.
