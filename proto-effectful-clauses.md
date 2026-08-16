# Effectful handler clauses in the proto kernel

The two `???` arms in the evaluator, what they were, the paths explored, and the solution that landed
(commit 22c675072d). Written as a composition story: the whole fix is one equation about values, and the
evaluator only supplies the ingredients the equation names.

## The problem, intuitively

A `handleLoop` region is a value with three parts: a handler, a body, and whatever comes after the region.
While the body runs, each operation it performs is answered by a clause. The clause returns an outcome:
either "continue with this answer" or "we are done, here is the region's result".

Every test we had answered with a settled outcome. But the clause's type is `Outcome[...] < S`: a clause is
allowed to do its own effects before deciding. A logging handler consults a logger; a stateful handler asks a
clock. When that happens, the clause's result is itself a pending computation, and the evaluator landed on
`???`, inherited from the kernel2 migration.

What should happen is not mysterious. Written as an equation about values, a region whose body performs an
operation `op` and then continues with `k` satisfies:

```
handle(H)(op andThen k)  =  clause(op).map {
    continue(answer) => handle(H)(answer into (op andThen k))
    done(result)     => result
}
```

Read it aloud: run the clause; if it answers, the region carries on as a region, with the answer fed to the
operation and the same handler still in charge; if it says done, the region is over and the result is just
the result. The CPS kernel gets this line for free because everything there is already a closure. The proto's
job was to say the same sentence with its own values.

## What makes it non-trivial in the proto

Two facts about the proto shape the solution space:

1. While a region's body runs, the operation's pending continuation (the "interior") lives as frames on the
   evaluation stack, above the region's marker. It is not a closure in anyone's hand.
2. The clause's computation evaluates through the same loop as everything else, so whatever represents "when
   the outcome arrives, do the dispatch" is ordinary currency: it can be folded into carry chains when
   something parks, captured into segments when an outer handler answers across it, and applied more than
   once. It must be a pure value that works anywhere, any number of times. That has been the standing rule
   for everything the kernel hands out since the dump work.

Fact 2 rules out anything that remembers stack positions or mutates the stack from inside a value. Fact 1
means the interior has to be turned into a value if the equation's right-hand side is to mention it.

## Paths explored

### Path 1: dispatch in place, touch nothing (experiment, rejected)

The least code: leave interior and marker where they are, evaluate the clause's computation on top, and map
the outcome into "feed the answer" or "return the result". Ten lines. It was implemented and run against the
acceptance set, and it fails exactly where the equation says it must:

- **The clause's effects resolved in the wrong place.** With the interior still on the stack, a region of the
  same effect living inside the operation's own continuation answered the clause's effect. The test saw
  `List("clause") was not empty` on the interior handler. The equation's left side has the clause outside
  `handle(H)(...)`; the kernel agrees; this path does not.
- **`done` ran `complete`.** The result value settled against the still-present marker, and the marker did
  what markers do: `completed was true`. But `Loop.done` means the region is over, bypassing `complete`.

Both failures share one root: the region was still standing while the equation says it should already have
been taken apart. Any variant that keeps the marker (there were several sketches, including one with
dedicated "region exit" values interpreted by the loop) has to fight its own leftover state, which is where
all the extra machinery in those designs came from.

### Path 2: forbid it in the type (considered, not taken)

Change the clause type to a bare `Outcome[...]` and the arms become unreachable. Simplest and safest by
construction, but it removes expressiveness kyo-kernel has (its loop clauses carry an effect row), and the
reference for this prototype is kyo-kernel. Kept on the table only as a documented fallback.

### Path 3: take the region apart, recompose it (experiment, landed)

Implement the equation literally. At the moment the clause suspends:

- The interior becomes a value: the existing crossing machinery already copies a stack slice into an
  `Arrow.Eval` segment (entries, tags, states), preserving live inner regions and their state. That is the
  proto's honest reification of "the rest of the body".
- The marker is dropped with it: `truncate(i)`. The region is now fully a set of values in hand: handler,
  suspension, interior segment. Nothing about it remains on the stack.
- The rest is the equation, spelled with public combinators:

```scala
out.map {
    case c: Loop.Continue[?]     => region(h.handler, c._1)
    case c: Loop.Continue2[?, ?] => region(handlerWith(c._1), c._2)
    case done                    => done
}
```

  where `region(handler, payload)` is a fresh `Handle` node whose body replays the interior segment with the
  answer running into it (`Arrow.Eval(entries, tags, states, Identity(payload, resume(s)))`), and
  `handlerWith(st)` is a small delegate whose `initialState` is the clause's new state. The dispatch itself
  is nothing but `map`: no hand-rolled transform, no re-arm protocol; `map`'s own chain and park semantics
  are the tested ones.

Every case lands on machinery that already existed:

| equation piece                      | proto value                                             |
|-------------------------------------|---------------------------------------------------------|
| run the clause first                | `map` over the outcome computation                      |
| `answer into (op andThen k)`        | `Arrow.Eval` segment replay, answer via `resume(s)`     |
| `handle(H)(...)` again              | a fresh `Handle` node; the loop re-creates the marker   |
| new state after `Continue2`         | `initialState` on a delegating handler                  |
| `done(result) => result`            | the value itself; no marker exists, so nothing fires    |

And the two failures of path 1 disappear structurally rather than by patching:

- The clause evaluates with the region gone, so its effects can only resolve outside it. Scoping matches the
  kernel because the composition matches the kernel's, not because anything checks.
- `done` bypasses `complete` because there is nothing to bypass. The absence of the marker is the semantics.

Replay safety comes free as well: applying the dispatch twice rebuilds two independent regions from the same
immutable segment. There is no marker to find, so there is no "marker not found" failure mode.

## Cost

The settled-clause path, which is every benchmarked row, is untouched: not one instruction changes until a
clause actually suspends. The suspended path pays three `Span` copies of the interior per suspension, plus a
`Handle` and an `Arrow.Eval` node (and one small delegate when stateful) per continue. A handler whose every
clause suspends over a deep interior pays that copy per operation, where the CPS kernel shares the
continuation closure. That asymmetry is the price of the explicit stack and is confined to the rare path.

## Evidence

Acceptance set (all red on the `???` before, all green after, 110/110 total):

- a clause answers effectfully (the region's result still arrives)
- a clause ends the region effectfully (`complete` bypassed: the flag stays false)
- every clause suspension re-arms the region (two operations, two clause effects, right result)
- a clause suspension resolves outside the region (interior same-tag region untouched; outer handler sees it)
- a clause answer survives its own deep evaluation (the dispatch rides a parked carry)
- a stateful clause answers effectfully (state threads across suspensions)
- a loop can end its region effectfully with a computation result (the payload stays a value)

Path 1's run is preserved in the record above as the two quoted failures; it passed the four easy tests and
failed the two discriminating ones, which is what makes those tests worth keeping.
