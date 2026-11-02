# The lowering of `direct`, formally

This document states the lowering that kyo-direct's `direct` macro performs, as a translation from a typed source fragment to a Kyo
computation, with the typing that goes with it. It is the specification [Lower.scala](shared/src/main/scala/kyo/internal/Lower.scala)
implements and [DESIGN.md](DESIGN.md) explains; where the two disagree, this document is the one to correct first, and the test that
exposed the disagreement goes next to it. The `.kyo` language (kyo-lang) lowers through the same translation: its plugin plants
`now` around every pending expression of a definition body, and the body is lowered as a `direct` block whose pure case keeps its type.

## 1. Source terms

The translation works on the typed trees of a `direct` body after Scala's typer. The grammar below names the shapes it distinguishes;
`...` stands for any other typed term, which the translation treats as opaque.

```text
e, c, b ::= x                           reference, literal, this, stable path         (atoms)
          | now(e)                      a `.now` marker on e : A < S
          | later(e)                    a `.later` marker on e : A < S
          | { s* ; e }                  block
          | if c then e1 else e2
          | e1 && e2  |  e1 || e2       Boolean.&& and Boolean.|| (resolved symbols)
          | e match { case p_i if g_i => e_i }
          | while c do b
          | f(e1, ..., en)              application, strict or by-name parameters
          | e.m[T*](e*)*                method call: receiver, type arguments, argument lists
          | (x1, ..., xn) => e          lambda literal
          | { case p_i => e_i }         partial-function literal (a lambda whose body is a match)
          | direct { e }                a nested block, already lowered by the typer: a value of type A' < S'
          | ...

s ::= val x = e  |  e  |  def f(...) = e  |  other local definitions
```

Markers are recognized by symbol: `now` and `later` are the extension methods of `Direct.scala`; a method merely named `now` is an
atom. A nested `direct` is recognized by the symbol of the inlined call that produced it.

**Marker occurrence.** `M(e)` is the set of `now` markers in `e` that are not inside a nested `direct`. `pure(e)` holds when
`M(e) = ∅`. Pure here means "has nothing left to sequence"; a pure term may still have type `A' < S'` (a value that is a computation).

## 2. Erasure of `later`

Before anything else, every `later` is erased, in every position of the body, including inside lambdas, local definitions, by-name
arguments, and the surroundings of nested blocks:

```text
E[later(e)]  =  E[e]
E[e]         =  e with E applied to every subterm, otherwise
```

A `now` inside a `later` is rejected before erasure. After erasure the body contains no `later`, and the rules below never mention it.

## 3. The row

Each marker `now(e)` with `e : A < S_i` records `S_i`. The row of the body is

```text
S(body)  =  S_1 & ... & S_n      for the markers of M(body) in source order, after
                                 splitting each S_i on &, dropping Any, and dropping duplicates (=:=)
         =  Any                  when the markers remain but every S_i is Any
```

When `M(body) = ∅` there is no row and no lowering: `direct { body }` is `Kyo.lift[A, Any](body)`, of type `A < Any`. (kyo-lang keeps
`body` itself in this case, so a pure `.kyo` definition keeps its ordinary type.)

## 4. The translation

The translation is written `⟦e⟧ k`, where `k` is the continuation: a meta-level function from a pure term standing for the value of
`e` to the target term for the rest of the block. Every target term produced for a block has type `R < S`, where `S` is the block's row
and `R` is the type the continuation produces. The two continuations the rules introduce are

```text
end(x)        =  Kyo.lift[A, S](x)           the body's own continuation, R = A, the body's type
lift_T(x)     =  Kyo.lift[T, S](x)           the continuation of a joined branch of type T, R = T
```

and the one combinator is

```text
bind(c, x ↦ t)   =  c.map((x: A) => t)(using frame)     for c : A < S', S' <: S, and t : R < S
```

with `frame` built from the position of the marker or construct the bind stands for. `bind` is Kyo's monadic bind (`map` on `A < S`
flattens); `lift` is its unit. The rules are a total function on the shapes of section 1 by structural recursion, so the translation
terminates, and each rule emits its continuation exactly once.

### Atoms and pure terms

```text
(pure)    pure(e)
          ───────────────
          ⟦e⟧ k  =  k(e)
```

A term with nothing to sequence is passed to the continuation unchanged, whatever its shape.

### Sequencing

```text
(now)     ⟦now(e)⟧ k  =  ⟦e⟧ (c ↦ bind(c, x ↦ k(x)))       with x fresh, of e's exposed type A
```

The marker's own argument is lowered first, so `f(x.now).now` binds `x` and then the outer call.

### Blocks

```text
(block-end)   ⟦{ ; e }⟧ k                 =  ⟦e⟧ k
(block-val)   ⟦{ val x = e ; s* ; e' }⟧ k =  ⟦e⟧ (v ↦ { val x = v ; ⟦{ s* ; e' }⟧ k })       if ¬pure(e)
(block-stat)  ⟦{ e ; s* ; e' }⟧ k         =  ⟦e⟧ (_ ↦ ⟦{ s* ; e' }⟧ k)                       if ¬pure(e)
(block-keep)  ⟦{ s ; s* ; e' }⟧ k         =  { s ; ⟦{ s* ; e' }⟧ k }                        if pure(s)
```

A `val` keeps its symbol: it is redefined inside the continuation with the lowered right-hand side, so references to it need no
rewriting. A statement whose value is unused still lowers; its continuation ignores the value. A local definition with a marker in it
(`block-keep` does not apply) is rejected. A pattern `val` is lowered as the typer desugared it, a `match` (rule `match` below).

### Conditionals and Boolean operators

```text
(if-end)   k = end
           ⟦if c then a else b⟧ k  =  ⟦c⟧ (c' ↦ if c' then ⟦a⟧ k else ⟦b⟧ k)

(if-join)  k ≠ end,  T the type of the If node
           ⟦if c then a else b⟧ k  =  ⟦c⟧ (c' ↦ bind(if c' then ⟦a⟧ lift_T else ⟦b⟧ lift_T, x ↦ k(x)))

(and)      ⟦l && r⟧ k  =  ⟦l⟧ (l' ↦ bind(if l' then ⟦r⟧ lift_Boolean else Kyo.lift[Boolean, S](false), x ↦ k(x)))   if ¬pure(r)
(or)       ⟦l || r⟧ k  =  ⟦l⟧ (l' ↦ bind(if l' then Kyo.lift[Boolean, S](true) else ⟦r⟧ lift_Boolean, x ↦ k(x)))   if ¬pure(r)
```

Only the selected branch runs, and only the right operand that is needed. At the end of the body each branch ends the block itself;
anywhere else the branches join into one `T < S` that is bound once, so the continuation is never duplicated. A `&&` or `||` whose
right operand is pure is an ordinary application (rule `apply`).

### Pattern matching

```text
(match-end)   k = end
              ⟦e match { case p_i if g_i => e_i }⟧ k  =  ⟦e⟧ (s ↦ s match { case p_i if g_i => ⟦e_i⟧ k })

(match-join)  k ≠ end,  T the type of the Match node
              ⟦e match { case p_i if g_i => e_i }⟧ k  =  ⟦e⟧ (s ↦ bind(s match { case p_i if g_i => ⟦e_i⟧ lift_T }, x ↦ k(x)))
```

Patterns and their bound symbols are kept as they are. A guard `g_i` with a marker is rejected.

### Loops

```text
(while)   ⟦while c do b⟧ k  =  bind(Loop[Unit, Unit, S](())(_ => step), _ ↦ k(()))
          step             =  ⟦c⟧ (c' ↦ if c' then ⟦b⟧ (_ ↦ Loop.continue[Unit]) else Loop.done[Unit])
```

No value is threaded through the loop, since `var` is excluded: every state the loop observes lives behind an effect. `Loop` is
stack safe, so a loop of any length lowers to a constant number of frames.

### Applications

For an application chain `h(a_1, ..., a_n)` where `h` is the callee (itself possibly an application, a type application, or a
selection), the receiver lowers first, then the arguments in source order:

```text
(apply)   ⟦h(a_1, ..., a_n)⟧ k  =  ⟦h⟧_callee (h' ↦ ⟦a_1, ..., a_n⟧_args (a' ↦ k(h'(a'_1, ..., a'_n))))

(args)    ⟦a_1, ..., a_n⟧_args k  processes the arguments left to right:
            ¬pure(a_i)                                   ⟦a_i⟧ (v ↦ continue with a'_i = v)
            pure(a_i), strict, a marker follows a_i,     { val t = a_i ; continue with a'_i = t }      (a fresh local)
              and a_i is not an atom
            pure(a_i) otherwise                          continue with a'_i = a_i
            by-name a_i with ¬pure(a_i)                  rejected (see counterparts for the exception)

(callee)  ⟦e.m⟧_callee k   =  ⟦e⟧ (e' ↦ k(e'.m))                               if ¬pure(e)
          ⟦e.m⟧_callee k   =  { val t = e ; k(t.m) }                            if pure(e), a marker follows, e not an atom
          ⟦h[T*]⟧_callee k =  ⟦h⟧_callee (h' ↦ k(h'[T*]))
          ⟦h⟧_callee k     =  k(h)                                               otherwise
```

"A marker follows" means a later argument of the same chain has a marker. Binding the pure terms that precede it keeps Scala's
left-to-right evaluation order: `combine(pure(), e.now)` evaluates `pure()` before `e`. Atoms (references, literals, stable paths,
lambda literals, and the `new` of a constructor call, which is no value until applied) are never bound. Ascriptions, named arguments,
and repeated arguments lower their inner term the same way.

### Counterparts

A call `r.m[T*](a*)*` whose receiver `r` is a collection (`IterableOps[A, CC, CC[A]]`), a `WithFilter[A, CC]`, `Maybe`, `Result`,
`Option`, `Try`, `Either`, or `Either.LeftProjection`, whose method is that type's own and is in the counterpart table (see
[Counterparts.scala](shared/src/main/scala/kyo/internal/Counterparts.scala)), and one of whose callbacks or by-name arguments has a
marker, lowers to the Kyo operation `op` paired with `m`:

```text
(counterpart)
  ⟦r.m[T*](a*)*⟧ k  =  ⟦r⟧ (r' ↦ ⟦a*⟧_cargs (a'* ↦ bind(adapt_m(Kyo.op[R*, T*, S](r')(a'*)*(using frame)), x ↦ k(x))))

  ⟦a⟧_cargs for each argument, left to right, keeping the call's argument lists:
    (x_1, ..., x_n) => e           the lambda rule below
    by-name a                      ⟦a⟧ lift_T, a computation T < S passed to the operation's by-name parameter
    strict a                       ⟦a⟧ (v ↦ continue with v)

  R*  the receiver's type arguments as the operation takes them: CC and A for a collection or a filter, the container's own otherwise
  T*  the method's own type arguments, as the typer inferred them at the call
```

`adapt_m` is the identity except for the four methods whose result shape differs: `find`, `collectFirst`, and `maxByOption` answer an
`Option` where Kyo answers a `Maybe` (`.map(_.toOption)`), and `withFilter` answers a `WithFilter` over the filtered collection
(`.map(_.withFilter(_ => true))`). `maxByOption`'s `Ordering` joins the frame in the using clause.

A callback becomes a lambda returning a computation:

```text
(lambda)   ⟦(x_1, ..., x_n) => e⟧_lambda  =  (y_1, ..., y_n) => ⟦e[y_i / x_i]⟧ lift_B         with B the lambda's result type
(partial)  ⟦{ case p_i => e_i }⟧_lambda   =  (y) => ⟦ y match { case p_i => Maybe(e_i) ; case _ => Maybe.empty } ⟧ lift_Maybe[B]
```

The fresh parameters replace the old ones in terms and in the singleton types the typer may have ascribed (`(x: @unchecked) match`).
The fallback case of a partial function is left out when its last case already matches everything. The counterpart operation takes
`A => Maybe[B] < S` for a partial function, and keeps or drops each element by the result.

A lambda with a marker that is not the argument of a counterpart call is rejected: the receiving call and everything after it were
typed against the lambda's original result type, and the translation cannot retype them. A by-name argument with a marker is rejected
for the same reason unless its call is a counterpart, whose operation takes a computation.

### Nested blocks

A nested `direct { e }` is pure for the enclosing block: its markers were its own, and it is a value of type `A' < S'`. Under `now` it
is bound like any computation (rule `now`); under `later` it was a value and the marker is erased; as an argument it is a value; as a
statement it is rejected, since its effects would be dropped.

### The end

```text
(end)   the body e lowers to  ⟦e⟧ end,  of type A < S(e)
```

## 5. Typing

Write `Γ ⊢ e : A` for the typer's judgment on the source, with `now(e) : A` when `e : A < S_i`, so the body types as if each marker
returned its result. The translation satisfies:

- **Type.** If the body `e` has type `A` and row `S = S(e)`, then `⟦e⟧ end : A < S`. By induction on the rules: `bind(c, x ↦ t)` has
  type `R < S` when `c : A' < S'` with `S' <: S` (every `S'` is one of the conjuncts of `S`) and `t : R < S`; both `lift`s have type
  `R < S`; a join `if`/`match` of branches of type `T < S` has type `T < S`; a `Loop` over `Outcome[Unit, Unit] < S` has type
  `Unit < S`; a counterpart operation applied to `[R*, T*, S]` has the declared result type with row `S`; and the pure rule passes a
  term whose type is the one the typer gave it.
- **Precision.** `S` is exactly the union of the rows the markers recorded, no more. A branch whose markers record a smaller row still
  conforms, by the variance of `<`. `direct` is transparent inline, so `A < S` is the type the caller sees and the type a handler
  infers its own arguments from.
- **Order.** The target evaluates receivers before arguments, arguments left to right, the condition before a branch, the scrutinee
  before a case, and each `now` at its position, as the source would with the effects run in place. The `args` and `callee` rules bind
  pure terms only where a later marker would otherwise reorder them.
- **Once.** Each `now` is bound exactly once, each continuation is emitted exactly once, and no branch construct duplicates its
  continuation; the target's size is linear in the source's.
- **Multi-shot.** Because the target is Kyo composition and nothing is cached across resumptions, an effect that resumes its
  continuation several times (`Choice`) runs the rest of the block once per resumption, including inside counterparts.

## 6. What is rejected, and where

The translation is total on the shapes above; the checks in [Validate.scala](shared/src/main/scala/kyo/internal/Validate.scala) run
before it and reject the shapes it does not give a meaning to, each with a message: `var`, `lazy val`, nested class or object
definitions, `try`/`throw`, `synchronized`, a read of a mutable member, a bare computation in statement position (a nested block
included), a `now` inside a `later`, inside a by-name argument of a non-counterpart call, inside a lambda passed to a non-counterpart
call, inside a `Stream` lambda, inside a pattern guard, or inside a local definition. A `now` in a position no rule covers is reported
at that position.
