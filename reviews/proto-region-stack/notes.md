# Your two notes on `deferInline`, with what I found

Both were riding along in the change's range as `TODO` comments in `Effect.scala`. They are not this
change, and a file the derivation lists under does-not-change should not quietly change, so they are
out of the range and written up here instead. Neither is answered, both are researched.

## "is there a reason for this? it'll create a pointer in deferInline"

On:

```scala
private val unitValue: Unit < Any = ()
```

**Yes, there is a reason, and the reference kernel has the same constant with a note on it.** Its
version:

```scala
/** Cast rather than lifted: a raw value is already the union's first arm, and `Unit` admits no
  * `Boxed` subtype, so there is nothing to nest. Lifting here would summon the macro inside a core
  * kernel file, which is the cascade that ends in a stale-symbol crash on the clean build.
  */
private val unitValue: Unit < Any = ().asInstanceOf[Unit < Any]
```

Two separate things are going on, and the proto has one of them and not the other.

**The shared constant.** `deferInline` expands at every call site, and each expansion needs a `Unit`
payload in the union representation. A shared `val` is one field read; the alternative is each
expansion carrying its own. Your "it'll create a pointer" is exactly right about what it costs: the
expansion reads `Effect.unitValue`, which loads the module. The reference kernel pays the same and
keeps the constant, so the trade is settled there in favour of the constant.

**The cast versus the conversion.** This is the part where the proto differs, and it looks like a
live concern rather than a preference. The proto writes `= ()`, which fires the implicit lift, which
is a splice macro, **inside a core kernel file**. That is the suspension cascade the skill's
"suspension equilibrium" section is about: a file that summons a same-module macro is suspended to a
retry run, and new summons inside an inlined-from file deepen the cascade until dotty crashes with a
`StaleSymbolException`, on the clean batch build only. `Effect.scala` is inlined-from, since
`deferInline` is `inline`.

It has not crashed: the clean batch build is green at the tip. So either the cascade has room, or
the lift resolves through the primitive arm without a splice. I did not establish which, and the
difference matters, because "green today" and "cannot cascade" are not the same claim. The reference
kernel's comment reads as someone who hit the crash and wrote the cast to stop it.

**What I would do, and why I did not do it here**: match the reference, `().asInstanceOf[Unit < Any]`
with its comment. It is one line and it removes a macro summon from a core file. It is not this
change's surface, and it is the kind of edit that should be its own live review with its own clean
build, precisely because the failure mode it addresses is invisible to incremental builds.

## "let's update code like this to be structured like `<.map` with a single branch"

On `deferInline`'s `apply`:

```scala
override def apply[C, S2](v: Unit < S2, cont: Arrow[A, C, S2]) =
    v match
        case kyo: Pending[Unit, S2] @unchecked =>
            defer(kyo, this, cont)
        case _ =>
            val slot = Safepoint.get()
            if !Safepoint.enter(slot) then
                defer(v, this, cont)
            else
                val out = cont.head(apply(Nested.unnest(v)), cont.tail)
                Safepoint.exit(slot)
                out
```

`<.map`'s shape, which you are pointing at:

```scala
var slot: Safepoint.Slot = 0
val shouldDefer = v.isInstanceOf[Pending[?, ?]] || { slot = Safepoint.get(); !Safepoint.enter(slot) }
if shouldDefer then ... else ...
```

One branch, with the two deferral reasons folded into one condition by short-circuit, and the slot
resolved only on the arm that needs it.

**It applies here, and there are about fifteen sites in the same shape**: `Arrow.apply`,
`Arrow.recursive`, `Effect.deferInline`, and eight in `Loop`. They are the hottest expansions in the
kernel, so the argument for uniformity is also an argument about bytecode size at every call site,
which is a measurement rather than a preference. `<.map` presumably went first because it is the
hottest of them.

**One thing I would want settled first.** These sites are also where the safepoint budget leaks on a
throw: `enter` runs, the body throws, `exit` never runs. This change repairs the leak at the two
places that catch, which is correct but means every future catcher must know to repair it. The
alternative is `try ... finally Safepoint.exit(slot)` at each of the fifteen sites, which is correct
by construction and costs bytecode on exactly the paths whose size decides whether their callers
inline. If those sites are going to be rewritten to one branch anyway, the two questions should be
answered together rather than the file being touched twice.

I have not measured either. Both are real work with a real answer, and neither is this change.
