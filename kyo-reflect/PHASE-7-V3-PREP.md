# Phase 7 Prep: Examples + Bench Updates

Commit at time of prep: `73855f5ccb6e897117bbc5ea32914a3cad1ce481`

---

## Per-example: current shape and suggested pure shape

### CodegenExample.scala

Current shape (at time of audit): already pure after Phase 3. The `for`-comprehension only threads `Sync & Async & Abort[ReflectError] & Scope` through `openCached`. All `Symbol` accessors (`parents`, `declarations`, `declaredType`) are called as plain method calls without effect threading. No `.flatMap` or `.map` on `parents`, `declarations`, etc.

There is no effect ceremony to remove. The example is ready; verify it compiles with no changes.

Action for Phase 7: confirm compilation only. No edits needed.

---

### IdeHoverExample.scala

Current shape: already pure after Phase 3. `hover` opens the classpath via `openCached` (effectful), then calls `cp.findClass(fqn)` (pure), `cls.declarations.find(...)` (pure), and `s.declaredType.show` (pure). All post-open steps are plain expressions inside `yield`.

`findSealed` uses `cp.topLevelClasses.filter(...)` (pure) inside `yield`.

No stale effect ceremony. Ready; verify compilation only.

Action for Phase 7: confirm compilation only. No edits needed.

---

### JavaScalaBridgeExample.scala

Current shape: already pure after Phase 3. `summarize` opens the classpath then calls `cp.findClass(fqn)` (pure), `cls.parents` (pure), `cls.declarations` (pure) inside a `yield` block. `compare` sequences two `summarize` calls with `flatMap`/`map` against the Kyo effect row, which is correct because `summarize` itself is effectful (opens a classpath).

No stale accessor-level ceremony. Ready; verify compilation only.

Action for Phase 7: confirm compilation only. No edits needed.

---

### RuntimeReflectionExample.scala

Current shape: already pure after Phase 3. `fieldsOf` and `describe` open the classpath, then call `cls.declarations` (pure) and `cls.parents` (pure) inside `yield` blocks. `requireFound` has a correctly typed `Maybe[Symbol] < (Sync & Abort[ReflectError])` return using `Sync.defer` for the `Present` branch.

No stale effect ceremony. Ready; verify compilation only.

Action for Phase 7: confirm compilation only. No edits needed.

---

## ReflectBench.scala: current state and required changes

### Stale effect ceremony to remove

Three workloads contain `.flatMap`/`.map` on pure accessors that became pure in Phase 3:

**W4 (per-FQN lookup warm cache, lines 267-278)**

```scala
warmCp.findClass(fqn).map:
    case Present(_) => hits += 1
    case Absent     => ()
```

`findClass` is now pure (`Maybe[Symbol]`). The entire workload body must be rewritten to not thread effects. The bench loop should use plain Scala pattern match.

Suggested pure shape:
```scala
bench("W4 per-FQN lookup warm cache", warmupIter, measureIter):
    var hits = 0
    for fqn <- fqnsToLookup do
        warmCp.findClass(fqn) match
            case Present(_) => hits += 1
            case Absent     => ()
```

No `runSync` needed for W4 once these are pure. However W4 currently wraps each iteration in `runSync` because it was iterating with an effectful body; with pure accessors the outer `runSync` can be removed too. The bench framework is `bench(name, w, m) { action: => Unit }` so the action is a plain synchronous block.

**W5 (declarations enumeration, lines 282-289)**

```scala
val _ = runSync:
    warmCp.findClass("kyo.fixtures.PlainClass").flatMap:
        case Present(sym) => sym.declarations.map(_.size)
        case Absent       => 0
```

Both `findClass` and `declarations` are now pure. Suggested pure shape:
```scala
bench("W5 declarations enumeration (PlainClass)", warmupIter, measureIter):
    val count = warmCp.findClass("kyo.fixtures.PlainClass") match
        case Present(sym) => sym.declarations.size
        case Absent       => 0
    val _ = count
```

**W8 (plain iteration, lines 291-301)**

```scala
val _ = runSync:
    for
        tops <- warmCp.topLevelClasses
        all <- kyo.Kyo.foreach(tops): cls =>
            cls.declarations.map: decls =>
                decls.count(_.kind == Reflect.SymbolKind.Method) + ...
    yield all.sum
```

`topLevelClasses` and `declarations` are pure. `Kyo.foreach` over a pure function is unnecessary. Suggested pure shape:
```scala
bench("W8 plain iteration (no Query)", warmupIter, measureIter):
    val total = warmCp.topLevelClasses.map: cls =>
        cls.declarations.count(_.kind == Reflect.SymbolKind.Method) +
            (if cls.kind == Reflect.SymbolKind.Method then 1 else 0)
    .sum
    val _ = total
```

---

## W9: hover-shaped query

### Walking pattern

W9 simulates an IDE hover query: for each top-level class, walk declarations, filter to those with a position in a target line range, format each as `"name: type (scaladoc)"`.

Pure walk, no effects:

```scala
def w9HoverQuery(cp: Reflect.Classpath, targetLineMin: Int, targetLineMax: Int): Chunk[String] =
    cp.topLevelClasses.flatMap: cls =>
        cls.declarations.collect:
            case sym if sym.position.isDefined &&
                        sym.position.get.line >= targetLineMin &&
                        sym.position.get.line <= targetLineMax =>
                val doc = sym.scaladoc.getOrElse("")
                s"${sym.name.asString}: ${sym.declaredType.show} $doc".trim
```

### Fixture target

Use `kyo.fixtures.PlainClass` or `kyo.fixtures.SomeObject` from the embedded fixture TASTy files (the bench already loads all fixture TASTy from classpath). These are small classes with a few declared members and known source positions in the fixture file.

Target line range for W9: `1` to `50` (covers all methods in PlainClass and SomeObject which are short fixture files).

Since position info from TASTy includes source file path and line numbers relative to the original source, and the fixture TASTy was compiled from a small source, nearly all declarations will fall within lines 1-50. This guarantees at least one match per run and makes the bench non-trivial.

### Expected timing

W9 walks `topLevelClasses` (Chunk of ~10 fixture symbols), then for each class walks `declarations` (2-10 members each), checking `position` (pure SingleAssign read, ~50 ns each) and `scaladoc` (pure). The total work per run is roughly 10 classes * 5 members * 3 pure reads = 150 SingleAssign reads plus string formatting.

Expected median: 5-50 microseconds. Expected p95: under 200 microseconds. If the bench prints a median above 1 ms, the position/scaladoc reads are hitting an unexpected code path.

---

## W10: find-references-shaped query

### Tree walk pattern matching Apply / Select / Ident

W10 counts how many times a target FQN is referenced (as Ident name or Select name) across all method bodies in the fixture classpath.

The tree walk is a recursive descent. The helper `countRefs(tree: Reflect.Tree, targetName: Reflect.Name): Int` accumulates matches.

The full ADT cases to handle, with recursion:

```scala
def countRefs(tree: Reflect.Tree, target: Reflect.Name): Int =
    import Reflect.Tree.*
    tree match
        // Terminal: check Ident name directly
        case Ident(name, _) =>
            if name == target then 1 else 0

        // Terminal: check Select qualifier then name
        case Select(qualifier, name, _) =>
            (if name == target then 1 else 0) + countRefs(qualifier, target)

        // Recurse into Apply function and all args
        case Apply(fun, args) =>
            countRefs(fun, target) + args.map(countRefs(_, target)).sum

        // Recurse into TypeApply function only (type args are Type, not Tree)
        case TypeApply(fun, _) =>
            countRefs(fun, target)

        // Recurse into all Block stats and final expr
        case Block(stats, expr) =>
            stats.map(countRefs(_, target)).sum + countRefs(expr, target)

        // If: recurse into all three branches
        case If(cond, thenp, elsep) =>
            countRefs(cond, target) + countRefs(thenp, target) + countRefs(elsep, target)

        // Match: recurse into selector and all cases
        case Match(selector, cases) =>
            countRefs(selector, target) + cases.map(cd => countRefs(cd.body, target) +
                cd.guard.map(countRefs(_, target)).getOrElse(0) +
                countRefs(cd.pattern, target)).sum

        // CaseDef: recursed by Match above; if encountered standalone, recurse
        case CaseDef(pattern, guard, body) =>
            countRefs(pattern, target) + guard.map(countRefs(_, target)).getOrElse(0) +
                countRefs(body, target)

        // Inlined: recurse into call, bindings, and body
        case Inlined(call, bindings, body) =>
            call.map(countRefs(_, target)).getOrElse(0) +
                bindings.map(countRefs(_, target)).sum +
                countRefs(body, target)

        // Bind: recurse into pattern; name is a binding site, not a reference
        case Bind(_, pattern) =>
            countRefs(pattern, target)

        // Alternative: recurse into all patterns
        case Alternative(patterns) =>
            patterns.map(countRefs(_, target)).sum

        // Unapply: recurse into fun, implicits, and patterns
        case Unapply(fun, implicits, patterns) =>
            countRefs(fun, target) +
                implicits.map(countRefs(_, target)).sum +
                patterns.map(countRefs(_, target)).sum

        // ValDef / DefDef: recurse into rhs if present
        case ValDef(_, _, rhs) =>
            rhs.map(countRefs(_, target)).getOrElse(0)
        case DefDef(_, _, _, rhs) =>
            rhs.map(countRefs(_, target)).getOrElse(0)

        // ClassDef / Template: recurse into template body
        case ClassDef(_, tmpl) =>
            tmpl.body.map(countRefs(_, target)).sum
        case Template(parents, _, body) =>
            parents.map(countRefs(_, target)).sum + body.map(countRefs(_, target)).sum

        // PackageDef: recurse into all stats
        case PackageDef(_, stats) =>
            stats.map(countRefs(_, target)).sum

        // Try: recurse into expr, cases, and finalizer
        case Try(expr, cases, fin) =>
            countRefs(expr, target) +
                cases.map(cd => countRefs(cd.body, target)).sum +
                fin.map(countRefs(_, target)).getOrElse(0)

        // While: recurse into cond and body
        case While(cond, body) =>
            countRefs(cond, target) + countRefs(body, target)

        // Assign: recurse into lhs and rhs
        case Assign(lhs, rhs) =>
            countRefs(lhs, target) + countRefs(rhs, target)

        // Throw, Return, Typed, NamedArg, Annotated, Super, This, Lambda: recurse where applicable
        case Throw(expr)              => countRefs(expr, target)
        case Return(expr, _)          => expr.map(countRefs(_, target)).getOrElse(0)
        case Typed(expr, _)           => countRefs(expr, target)
        case NamedArg(_, value)       => countRefs(value, target)
        case Annotated(expr, ann)     => countRefs(expr, target) + countRefs(ann, target)
        case Lambda(method, _)        => countRefs(method, target)

        // Terminals with no sub-trees
        case Literal(_) | New(_) | This(_) | Unknown(_, _) | Super(_, _) | TypeDef(_, _) => 0
end countRefs
```

### Parallelism via Kyo.foreach

W10 must decode method bodies, which is effectful (`< (Sync & Abort[ReflectError])`). The plan calls for `Kyo.foreach` to parallelize body decodes. The bench already uses `runSync` to execute effects.

Structure for W10:

```scala
bench("W10 find-references walk", warmupIter, measureIter):
    val _ = runSync:
        Scope.run:
            openClasspath(fixtureSrc).flatMap: cp =>
                val targetName = Reflect.Name("value")
                val methods = cp.topLevelClasses.flatMap(_.declarations)
                    .filter(_.kind == Reflect.SymbolKind.Method)
                Kyo.foreach(methods): sym =>
                    Abort.run[ReflectError](sym.body).map:
                        case Result.Success(tree) => countRefs(tree, targetName)
                        case _                    => 0
                .map(_.sum)
```

The bench opens a fresh classpath per W10 run (not the warm classpath) so the OnceCell body cache is cold on the first iteration and warm on subsequent ones. This exercises both the decode path and the cached-return path across the `warmupIter + measureIter` runs.

---

## Edge cases

### Inlined nodes

`Reflect.Tree.Inlined(call, bindings, body)` is already in the ADT. The `countRefs` above recurses into `call`, `bindings`, and `body`. This covers inline expansions correctly.

`Inlined` nodes can nest. The recursion handles arbitrary depth because each recursive call descends into sub-trees.

### Bind patterns

`Reflect.Tree.Bind(name, pattern)` binds a name on the LHS of a case. The `name` is a binding site, not a reference to target. Only the `pattern` (the type test or extractor sub-tree) is recursed. The count therefore correctly distinguishes `case value: Int` (binding of `value`, not a reference to any external `value`) from `foo.value` (a Select reference).

### Match cases

The `countRefs` for `Match` recurses into `selector` and each `CaseDef`. Each `CaseDef` recurses into `pattern`, `guard`, and `body`. No cases are skipped.

### Handling bodies with zero matches

If no sub-tree matches the target name, `countRefs` returns 0. This is the normal case for most methods. The bench sums 0s and reports a sum; printing `0` is valid. No guard needed.

### Symbols without a body slice (Package, Java, no-body TASTy)

`sym.body` returns `Abort.fail(ReflectError.NotImplemented(...))` for Java symbols, Package symbols, and symbols without a TASTy body slice. The W10 structure wraps each `sym.body` call in `Abort.run[ReflectError]` and maps `Result.Failure` / `Result.Panic` to 0. This is already shown in the suggested code above. No exception can escape.

---

## Anti-flakiness notes

### Bench timer warm-up

The bench uses 5 warm-up iterations followed by 10 measurement iterations. This is sufficient for JIT on JVM because OnceCell bodies are decoded on the first warm-up iteration; subsequent iterations hit the cached path. W10 should show lower latency on measured iterations than on warm-up for precisely this reason. The bench structure already handles this correctly via the `warmupIter` / `measureIter` split.

For W9 (pure walk, no JVM decode), the warm-up primarily warms the JIT for the Chunk iteration and string allocation path. 5 iterations is sufficient for a sub-millisecond benchmark to reach steady state.

### Preventing W10 explosion

The fixture classpath contains small fixture classes (PlainClass, SomeObject, SomeTrait, etc.), each with 2-10 methods and bodies of 5-50 TASTy nodes. The total tree size across all method bodies in the fixture set is small (estimated: under 2000 nodes total). The recursive `countRefs` will not stack-overflow on this input.

For production use with large classpaths, the recursion depth is bounded by tree nesting, not class count. Deep method bodies (100+ levels of nesting) are rare. If stack depth becomes a concern in a future prod context, convert `countRefs` to a trampoline, but that is out of scope for Phase 7.

### Deterministic test fixtures

The fixture TASTy files are embedded as byte-array literals in `Embedded.scala`. The set of classes and their member counts are fixed at compile time. W9 and W10 therefore produce the same results across runs (same fixture set, same TASTy bytes). The bench prints timing but not result values, so non-determinism in output format is not a concern.

W4 uses a hardcoded `fqnsToLookup` list of 9 known FQNs. All 9 are present in the fixture TASTy. If any lookup returns `Absent`, the bench still completes; `hits` will be lower. The list is stable.

---

## Concerns

### W4/W5/W8 still use runSync and effect threading in current code

The three workloads have stale `.flatMap`/`.map` effect threading that became unnecessary after Phase 3 made `findClass`, `declarations`, and `topLevelClasses` pure. These must be rewritten in Phase 7. The work is mechanical: replace effectful for-comprehensions with plain Scala expressions. No new logic is needed.

Without this cleanup, the bench needlessly allocates Kyo effect-continuation objects and inflates latency measurements for W4/W5/W8. The numbers will be misleading.

### W10 fresh classpath per run vs warm classpath

W10 opens a fresh classpath for every `bench` action block. This means each of the 15 runs (5 warm-up + 10 measure) re-runs the full classpath open and body decode for the first iteration of each `Kyo.foreach` block. This is intentional: it benchmarks both classpath-open cost and body-decode cost together, which matches the "cold find-references" use case.

If the intent is to isolate only the tree-walk cost (post-decode), the bench should open the classpath once, run `Kyo.foreach` with `sym.body` calls to warm all OnceCell caches before the bench loop starts, then measure only the cached path. This is a design decision: the current spec does not specify which variant. Recommendation: use the fresh-classpath variant as the primary measurement (matches real-world use), and add a comment noting that steady-state tree-walk cost is much lower (visible in the p95 vs median gap after warm-up).

### Abort.run wrapping cost in W10

Each `sym.body` call is individually wrapped in `Abort.run[ReflectError]`. For the fixture set with ~50 methods, this creates 50 `Abort.run` calls per bench iteration. The allocation cost is small but measurable. An alternative is to collect all methods, run one `Abort.run` wrapping the entire `Kyo.foreach`, and treat any decode failure as 0 for the whole class. The current per-body wrapping is safer (one corrupt body does not abort the whole run) and matches the spec. Keep per-body wrapping.

### Stack safety for countRefs in JS/Native

On Scala.js and Scala Native, the default call stack is shallower than on JVM. The fixture bodies are small (estimated max depth: 10-15 levels of nesting). Stack overflow is not expected for the fixture set. If future fixtures contain deeply nested bodies, add a `StackLimitedRunner` guard (already used in test suites for JS/Native).

The bench is JVM-only (`kyo-reflect-bench/jvm`). Stack safety for `countRefs` in the bench itself is not a concern. Note this only if `countRefs` is later extracted into shared code.
