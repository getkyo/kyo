# Decisions for your review

There are four design forks in the kyo-tasty audit-fix plan that need a value
judgment. I've picked a defensible default for each. **You do not need to
respond unless you want to override a default.** If you say nothing, I take
the defaults and proceed.

Skim the four sections. If one of them looks wrong, tell me which and what
you'd rather do.

---

## 1. Subtyping result when the answer is genuinely unknown

The current `Type.isSubtypeOf(other)` returns `Boolean`. When the rec-unfolding
budget runs out (the type graph is too deep) or the classpath isn't fully
loaded yet, the code currently returns `false`. That conflates "definitely not
a subtype" with "I don't know yet", which a caller can't distinguish.

**Default I'd take:** change the return type to `Maybe[Boolean]`.
`Present(true)` means yes, `Present(false)` means no, `Absent` means
undetermined. Matches Kyo's existing `Maybe` idiom and adds no new public type.

**Alternative considered:** introduce `enum SubtypeVerdict { Sub, NotSub,
Unknown }`. Names the cases explicitly and is slightly more discoverable, but
it's a new public type the user has to learn.

---

## 2. How to break up the work of completing the Tree decoder

`Symbol.body` currently decodes only 5 of the ~101 TASTy AST tags; everything
else returns `Tree.Unknown(tag, length)`. Filling that in is a lot of work
per commit unless we split it into sub-phases. Three reasonable ways to split:

**Default I'd take:** split by TASTy spec category (terms, definitions,
type-trees in tree position, specialized). Matches how the upstream Scala 3
compiler organizes the tags, so anyone reading the spec finds the split
familiar.

**Alternative A:** split by semantic group (literals + references, calls +
applications, control flow + definitions, patterns + rest). Application-shaped
rather than spec-shaped; easier for kyo callers but harder to cross-reference
with the spec.

**Alternative B:** split by numeric tag-id range. Purely mechanical, no
judgment. Smallest commits possible but readers can't tell what's in each
sub-phase without consulting the spec.

---

## 3. How widely to enrich TastyError with a byte offset

When the unpickler hits malformed input, the error today is
`MalformedSection(name: String, reason: String)`. The `reason` collapses to
strings like "unexpected end: null", which doesn't tell you which byte was
the problem.

**Default I'd take:** add `byteOffset: Long` to every malformed-section case.
Uniform debugging story, and every site that throws this error already knows
its cursor position.

**Alternative considered:** add `byteOffset` only to the few cases that
already carry a structured `at: Long` payload (CorruptedFile does). Smaller
API change but leaves some malformed-section sites with opaque error context.

---

## 4. Where the `kyo.tasty.examples` package should live

The four example files (`CodegenExample`, `IdeHoverExample`,
`JavaScalaBridgeExample`, `RuntimeReflectionExample`) currently sit at
`kyo.tasty.examples`. That deviates from the project convention of `kyo` for
public API and `kyo.internal.tasty` for implementation. The package is
neither.

**Default I'd take:** leave the examples at `kyo.tasty.examples` and add a
top-of-file comment explaining the deliberate exception. They're intended
to be public demo code; an `internal` namespace would mislead readers.

**Alternative A:** rename to `kyo.internal.tasty.examples`. Aligns with the
two-namespace convention but signals the examples are private demos, which
they aren't.

**Alternative B:** extract to a separate `kyo-tasty-examples` sbt module.
Fully isolates the examples from the public surface but adds build overhead
for a small benefit.

---

## What happens if you say nothing

I proceed with all four defaults:

1. `Type.isSubtypeOf` returns `Maybe[Boolean]`.
2. Tree decoder splits into four sub-phases by TASTy spec category.
3. Every malformed-section TastyError case gets `byteOffset: Long`.
4. `kyo.tasty.examples` stays where it is with an explanatory header.

Tell me which (if any) to flip.
