# CONTRIBUTING.md compliance audit

## Method

Read CONTRIBUTING.md end-to-end (1223 lines). Enumerated all 23 commits and ~58 touched files via `git diff main..HEAD --stat`. Filtered to the schema (`kyo-schema/**`) + the kyo-data `TagMacro.scala` change that the branch actually owns; ignored the kyo-data churn unrelated to this branch (those are already-merged commits pulled in by main). Audited each touched file by re-reading the changed regions plus surrounding context, then grepped branch-wide for `Frame.internal`, `asInstanceOf`, `AllowUnsafe`, `@unchecked`, semicolons, and `ignore`/`pending` markers. Cross-referenced each finding to the CONTRIBUTING.md section + paragraph that names the rule.

## CONTRIBUTING.md rules consulted (with section + line range)

- **Core Principles** (164-180): source files as documentation; most-used first; type-safety first / `asInstanceOf` last resort (#6); symmetry across related types (#7); explain the surprising, skip the obvious (#8).
- **API Design / Naming** (185-225): no symbolic operators in `kyo-data`/`prelude`/`core`; `get`/`use`/`init` naming; `noop` for degenerate cases.
- **API Design / Types** (227-311): `Maybe` over `Option`; `Result` over `Either`/`Try`; `Chunk` internally, accept generic collections in public APIs; `Span` over `IArray`/`ArraySeq`.
- **Method Signatures** (313-386): `using` ordering (`Frame` placement, `AllowUnsafe` always last); call-by-name for side-effecting bodies.
- **Code Conventions / Scala** (404-424): no `var`/`while`/`throw` for control flow; `discard(expr)` over `val _ =`; `CanEqual` via `derives`; no `asInstanceOf`/`@unchecked` except inside opaque-type boundaries or kernel internals; no `@uncheckedVariance`; explicit return types on public API only; no `protected`; all public APIs in `kyo` package, internal in `kyo.internal`.
- **Documentation / Type-Level Scaladoc** (430-444): 8-35 lines; opening sentence; `@tparam`; `@see` 3-6 links.
- **Method-Level Scaladoc** (446-450).
- **Markdown Formatting** (452-512): no Scala 2 wiki syntax (`=heading=`, `'''bold'''`, `''italic''`); use `####` for in-class subsections; CommonMark only.
- **Inline Comments** (514-526): comments must add understanding; quality bar = "does removal hurt comprehension?".
- **File Organization** (528-633): public API first, internal last; section separators; visibility tiers.
- **Optimization / Performance** (640-666): `final class` / `abstract class` over `trait`; fast-path before slow-path; opaque types; `@tailrec`; no `Thread.sleep`/blocking.
- **Inline Guidelines** (703-754): inline only the function/by-name parameters; small bodies; `@nowarn("msg=anonymous")` for inlined lambdas.
- **Testing / Framework** (759-855): extend the module-local `Test`; `Test` not `Spec`; mirror source structure; effect-evaluation pattern; `runJVM` / `runNotJS` for platform-conditional; `untilTrue` for eventual consistency.
- **Unsafe Boundary** (859-964): no `AllowUnsafe` leaking through constructors; scope as narrowly as possible; safe→unsafe bridge via `Sync.Unsafe.defer`.
- **KyoException Convention** (1078-1094): extend `KyoException`; `using Frame`; concise messages.

Skipped (not applicable to schema): Effect Implementation Reference (1096-1222) — schema does not introduce a new ArrowEffect/ContextEffect; Closeable Resource Pattern; Local-Backed Service Pattern; Isolate Protocol.

## Per-file findings

### kyo-schema/shared/src/main/scala/kyo/Schema.scala (~1500 new/changed lines)

- **BLOCKER**: none.
- **MAJOR**:
  - Schema.scala:142, 903, 905, 906, 1029, 1135-1136, 1204-1205, 1219, 1330, 1340, 2129, 2136, 2188, 2195, 2464 — heavy use of `asInstanceOf` in public-facing methods (`focus`, `foreach`, `resultOf`, `init`, `initFocused`, `createWithFocused`, `frameSchema`, `tagSchema`, `resultSchema`, `eitherSchema`). CONTRIBUTING §6 and "Scala Conventions" (419) say `asInstanceOf` is acceptable "only inside opaque type boundaries or kernel internals where the type system can't express a known invariant — never as a convenience shortcut". Several of these are justified (opaque `Frame`/`Tag` read paths; the `Focused`-type-member contract documented at lines 1196-1200), but most have no inline justification comment. The CONTRIBUTING bar is: "the *one* place where casts are justified" should be commented at the site (see line 2410-2412 — that comment exists on `createFrom`, proving the precedent). Add a one-line `// cast: ...` comment to each `asInstanceOf` site that isn't covered by the existing `initFocused` block comment. Fix: gate each cast with a justification comment matching the precedent at 2410-2412.
  - Schema.scala:1407 `r.skip(); ()` — uses `;` to chain statements, violating the user-memory rule "no semicolons" (`feedback_no_semicolons`). Same pattern at 2156 and 2206 in the macro-generated reader bodies. CONTRIBUTING does not explicitly forbid them, but the user-memory rule is project-wide. Fix: split onto two lines, or wrap with a `Sync.defer`-style block.
- **MINOR**:
  - Schema.scala:1135-1136 — `inline def init[...]` has default `getterFn`/`setterFn` lambdas inlined as `(a: A) => Maybe(a).asInstanceOf[Maybe[Any]]`. This violates `feedback_no_default_params_internal` ("never add `= default` to internal/private APIs; update every caller explicitly"). `Schema.init` is `inline` but effectively public (called by every macro-emitted Schema). Fix: drop the defaults and require macros/callers to pass the identity functions explicitly.
  - Schema.scala:1407 — the `Unit` reader body `r.skip(); ()` would be clearer as `kyo.discard(r.skip()); ()` matching the `discard` convention used elsewhere; alternatively use a two-line body.
  - Schema.scala scaladoc at 28: external URL is wrapped in plain prose, but two `[[Json]]` / `[[Protobuf]]` wiki links rely on wiki-link interpretation — these are *correct* per CONTRIBUTING §"Links" (line 479) which prefers `[[Foo]]` for internal references. No fix needed; documenting that this is intentional.
  - Schema.scala:93 — `abstract class Schema[A] @publicInBinary private[kyo] (...)` has 13 constructor parameters across 5 lines. The constructor is private so this isn't an API-surface concern; flag as POLISH below.
  - Schema.scala:949-956 — `inline given order(using Mirror.ProductOf[A])` and `inline given canEqual: CanEqual[A, A] = CanEqual.derived` are instance-level `given`s inside `abstract class Schema[A]`. These will get re-summoned at each call site. Per CONTRIBUTING §"Inline Guidelines" (line 703), inline should be on creation paths, not on derived givens; here it adds bloat without benefit. Fix: drop `inline`.
- **POLISH**:
  - Schema.scala:93-107 — the 13-param `@publicInBinary` constructor with a mix of public and `private[kyo]` parameters in one parameter list is hard to scan. Consider splitting into a primary `private[kyo]` ctor + a separate apply-style factory.
  - Schema.scala:1872-2108 — the tuple ladder (Tuple1..Tuple22) is 250 lines of generated boilerplate; while necessary, a brief `// --- Tuple Schemas ---` divider header is in place (1870), but the 23 givens have no per-given scaladoc. Tuples are well-known so this is acceptable; flag for future generation via macro.

### kyo-schema/shared/src/main/scala/kyo/KeyCodec.scala (new, 51 lines)

- **BLOCKER**: none.
- **MAJOR**: none. Placement in the `kyo` (public) package is correct — `KeyCodec` is part of the user-facing surface.
- **MINOR**:
  - KeyCodec.scala:33, 41, 48 — error path uses `Result.fail(ParseException(Json(), s, "Int"))` constructing a throwaway `Json()` codec instance just for `format: Codec`. Since `KeyCodec[K]` doesn't know the codec at use time, this is acceptable, but a `KeyCodecParseException` or a dedicated parameter-less variant would be cleaner. CONTRIBUTING §KyoException convention (1078-1094) prefers concise constructors; today's wiring forces every `decode` failure to allocate a `Json()`. Fix: add a `format: Maybe[Codec] = Maybe.empty` overload to `ParseException`, or define a small `KeyDecodeException` next to `KeyCodec`.
  - KeyCodec.scala — `KeyCodec.apply` is correct, but the trait lacks the `Tag`/`@see` cross-references CONTRIBUTING §"Type-Level Scaladoc" (438) recommends. The class scaladoc is 3 lines vs the 8-35 target. Fix: expand to mention the related `Schema.mapSchemaWithKeyCodec` / `Schema.mapPairsSchema` givens.
  - KeyCodec.scala:24-49 — four `given X with` instances each defined inline with no per-given scaladoc. Per the convention in Schema.scala (every given gets a one-line scaladoc), KeyCodec's givens are conspicuously bare. Fix: add a single-line `/** ... */` above each given.
- **POLISH**: none.

### kyo-schema/shared/src/main/scala/kyo/internal/UnionMacro.scala (new, 339 lines)

- **BLOCKER**: none.
- **MAJOR**:
  - UnionMacro.scala:234, 254, 265, 327 — `asInstanceOf` in macro-emitted code is justified by the union-type type erasure (the leg type `L` is only known at compile time), but the `$value.asInstanceOf[L].asInstanceOf[Any]` at line 265 is a double-cast that's not commented. Fix: collapse to a single cast (or add a comment noting why the Any-cast is needed for the schema array's erased element type).
  - UnionMacro.scala:225-235 — uses `Frame.internal` for `TypeMismatchException` (terminal write branch) and lines 255-267 for inner writes. The block comments above each site correctly explain the constraint (no Frame reachable in the emitted lambda); aligns with feedback_no_unsafe carve-out for emitted-code Frame propagation. The same justification pattern matches Schema.scala:1445-1450 and SerializationMacro.scala:60-63. PASS for the carve-out, MAJOR only because there's no Sync/AllowUnsafe path being bypassed here (it's a macro-emission decision).
- **MINOR**:
  - UnionMacro.scala:329 — `case scala.util.control.NonFatal(t) if !dispatched =>` — catch-all wrapping of NonFatal. The user-memory `feedback_log_unexpected_failures` says "never use catch-all `case _ =>`". This isn't `case _` but it does swallow context (`t.getClass.getSimpleName: t.getMessage`); the original exception is dropped on the floor. Fix: pass the cause to `TypeMismatchException` via the `cause` parameter slot, or rethrow with `addSuppressed`. At minimum, log via `Log.warn` or similar before swallowing.
  - UnionMacro.scala:192-196 — `legName` falls back to `tpe.show` for structural types. `tpe.show` can produce noisy fully-qualified strings; CONTRIBUTING §Naming (185-198) prefers user-friendly names. Fix: use a stable hash or document the failure mode.
- **POLISH**:
  - UnionMacro.scala:107-128 — `LegInfo` is `sealed private trait` with one anonymous implementation. CONTRIBUTING §Performance (640) prefers `final class`/`abstract class` over trait when possible. Here it's only used for path-dependent typing of `L`, so the trait is intentional; the comment block already explains it.

### kyo-schema/shared/src/main/scala/kyo/internal/SerializationMacro.scala (~1300 lines, ~150 new in branch)

- **BLOCKER**: none.
- **MAJOR**:
  - SerializationMacro.scala:569, 622 — `((errTpe.asType, okTpe.asType): @unchecked) match` uses `@unchecked` ascription. CONTRIBUTING §6 says `@unchecked` is acceptable "only inside opaque type boundaries or kernel internals where the type system can't express a known invariant". Macro internals qualify, but the existing rule expects a justification comment. The two existing sites match the FocusMacro:1085 site (pre-existing pattern). The branch added these to the new Result-specialization paths. Fix: add a one-line `// @unchecked: errTpe/okTpe are guaranteed primitives by resultFieldSpec` justification at both sites.
- **MINOR**:
  - SerializationMacro.scala:898 — `paramss.head.asInstanceOf[List[Term]]` — silent cast from the macro reflection API's `Tree` to `Term`. Macro emission knows the param trees are Terms, but the cast is uncommented. Fix: add a `// macro: paramss[0] is the single value param list, always List[Term]` comment.
  - SerializationMacro.scala:721 — `'{ null.asInstanceOf[t] }.asTerm` — used as the reference-type zero-init. The same trick recurs at 796, 799, 802, 858, 862, 871, 877, 885, 1050, 1079. CONTRIBUTING §Scala Conventions (419) accepts this in macro internals; PASS but flag the volume — the dedicated `zeroInitTerm` helper at 705-723 is already documented, so the helper-call pattern is fine; the other sites are unavoidable.
- **POLISH**:
  - SerializationMacro.scala — primitive-detection lists at 203-225 and 663-694 duplicate the Symbol list 2x; consider extracting into a single `private val primitiveSymbolSet` so future additions don't drift.

### kyo-schema/shared/src/main/scala/kyo/internal/SchemaSerializer.scala

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**:
  - SchemaSerializer.scala:206 `case m: (Maybe[?] @unchecked)` — newly introduced `@unchecked` (was previously bare `Maybe[?]`). CONTRIBUTING §6 forbids `@unchecked` outside kernel/opaque sites unless justified. The cause is real (Scala's pattern matcher can't verify `Maybe[?]`'s opaque erasure), but there's no comment explaining why this and not `Option[?]` (line 207) needs the annotation. Fix: add `// @unchecked: Maybe is opaque, erasure cannot be checked` next to the case.
  - SchemaSerializer.scala:269, 270, 274 — three `asInstanceOf[AnyRef]` casts inside `zeroForField`. The function is `private[kyo]` and the casts are forced by the `AnyRef` target type; PASS but no inline comment exists. Fix: add a one-line comment.
  - SchemaSerializer.scala:551 — `val discFieldIdStr = CodecMacro.fieldId(discField).toString` — recomputes the hash on every entry. The cost is tiny but the pattern violates `feedback_keep_constants_extracted` (extract constants even at single call site); should be hoisted to the closing block or to a small helper.
- **POLISH**: none.

### kyo-schema/shared/src/main/scala/kyo/internal/FocusMacro.scala (Phase 14 + 15 changes; ~105 lines added)

- **BLOCKER**: none.
- **MAJOR**:
  - FocusMacro.scala:744 — Phase 14 Java-enum macro emits `v.asInstanceOf[java.lang.Enum[?]].name`. The cast is necessary because the macro-time `A` isn't constrained to `<: java.lang.Enum[?]`. PASS for justification, but a one-line comment is missing. Fix: add `// cast: macro reached this branch only when sym is a Java enum class` justification.
  - FocusMacro.scala:748 — `valueOfMethod.invoke(null, name).asInstanceOf[A]` uses Java reflection. CONTRIBUTING §6 says "type safety first, escape hatches as last resort". The block comment at 738-744 already explains why Scala can't type the polymorphic `Enum.valueOf` from a macro `TypeRepr`. PASS. Consider a private helper `decodeJavaEnum[A](cls, name): A` that centralizes the reflection so callers see one well-named site.
  - FocusMacro.scala:807, 810 — `w.objectStart(${ Expr(child.name) }, 0); w.objectEnd()` and `kyo.discard(r.objectStart()); r.objectEnd(); $singletonRef` — multi-statement chains with `;`. Violates `feedback_no_semicolons`. Fix: split onto separate lines (these are emitted-code blocks so the formatting is at the macro author's discretion; multi-line quoted blocks are supported).
- **MINOR**:
  - FocusMacro.scala:1085 — `((arg1.asType, arg2.asType): @unchecked)` — same as SerializationMacro:569; pre-existing in this file but newly relevant. MINOR for consistency.
  - FocusMacro.scala:1581 — `Array[String | Any]` cast in `new Record[r](Dict.fromArrayUnsafe(arr.asInstanceOf[Array[String | Any]]))`. The function name `fromArrayUnsafe` already signals unsafe construction, so the cast is consistent with the unsafe boundary. PASS.
  - FocusMacro.scala:1620-1638 — `object UnionMacroProxy` is at top level alongside `object FocusMacro`. The scaladoc (1623-1637) explains the trampoline pattern well. Could use a `// internal` section separator above it per CONTRIBUTING §"Section Separators" (607).
- **POLISH**:
  - FocusMacro.scala:735 — Java-enum branch comments make a long upfront note (8 lines). The note correctly explains the ordering precondition (must precede sealed branch). The comment is on-bar per CONTRIBUTING §Inline Comments (514-526) — "explain *why*, not *what*".

### kyo-schema/shared/src/main/scala/kyo/internal/MacroUtils.scala

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**:
  - MacroUtils.scala:351-356 — added `platformPrimitiveSymbols` is `private[internal]` and is a one-line delegate. Could be inlined as `PlatformSymbols.primitiveSymbols` at call sites. The scaladoc (357-368) carries the rationale, so keeping the wrapper is fine. PASS.
- **POLISH**: none.

### kyo-schema/shared/src/main/scala/kyo/internal/ExpandMacro.scala

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**: none.
- **POLISH**:
  - ExpandMacro.scala:266-275, 284-291, 299-322 — newly added union-type and Java-enum branches duplicate the leg-flattening / dedup logic of UnionMacro. The block comment at 300-302 already calls this out ("Mirrors `UnionMacro.collectOrTypeLegs`"). Consider extracting to a single `private[internal]` shared helper so the two sites cannot drift.

### kyo-schema/shared/src/main/scala/kyo/Codec.scala

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**:
  - Codec.scala:170 — new `withFieldNames(names: Map[Int, String]): this.type = this` is a default no-op method. Per `feedback_no_default_params_internal`, the underlying API should not paper over wire-format differences via silent no-ops. However, this is the safe-default pattern explicitly documented in the scaladoc (165-170) and matches the `release(): Unit = ()` precedent at 134. PASS.
- **POLISH**: none.

### kyo-schema/shared/src/main/scala/kyo/Json.scala, Protobuf.scala, Structure.scala, SchemaException.scala

- All trivial edits (import cleanup, one new case in `PrimitiveKind`, one new line in JSON/Protobuf type-mapping). No findings.

### kyo-schema/jvm/src/main/scala/kyo/internal/PlatformSchemas.scala (new, 45 lines)

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**:
  - PlatformSchemas.scala:48 — `object PlatformSchemas` is public (not `private[internal]`) despite living in the `kyo.internal` package. The scaladoc says "Import as `import kyo.internal.PlatformSchemas.given`" — so callers DO need access. Acceptable, but the visibility marker should be explicit (e.g., `object PlatformSchemas` is implicitly public). PASS.
  - PlatformSchemas.scala:48-71 — each given is one line with no scaladoc. Same convention as Schema.scala's primitive givens, which DO have scaladoc. Fix: add a one-line scaladoc per given (`/** Schema for java.net.URI — encoded as URI string. */`).
- **POLISH**: none.

### kyo-schema/jvm/src/main/scala/kyo/internal/PlatformSymbols.scala (new, 27 lines)

- **BLOCKER**: none. MAJOR: none. MINOR: none. POLISH: none. Clean.

### kyo-schema/js/src/main/scala/kyo/internal/PlatformSymbols.scala (new, 14 lines), kyo-schema/native/src/main/scala/kyo/internal/PlatformSymbols.scala (new, 14 lines)

- Trivial cross-build shadows returning `Set.empty`. Clean.

### kyo-schema/shared/src/test/scala/kyo/CompositionMatrixTest.scala (new, 479 lines)

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**:
  - CompositionMatrixTest.scala:48 — `private given CanEqual[Any, Any] = CanEqual.derived` — broad `CanEqual[Any, Any]` defeats strict equality. CONTRIBUTING enables strict equality project-wide (§Scala Conventions, 419 → "Provide `CanEqual` for all comparable types"). Tests need this for heterogeneous comparisons but a per-test scope is preferable to the file-level `private given`. PASS but flag.
- **POLISH**: file lives in `shared` — correct per `feedback_all_platforms_all_tests`.

### kyo-schema/shared/src/test/scala/kyo/CodecTest.scala (new, 376 lines)

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**: none. Fixtures are top-level; `extends Test`; no `ignore`/`pending`. Clean.

### kyo-schema/shared/src/test/scala/kyo/UnionTest.scala (new, 185 lines)

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**:
  - UnionTest.scala:12 — `actual.asInstanceOf[AnyRef].equals(expected.asInstanceOf[AnyRef])` — helper-method route around CanEqual. The block comment at 10-11 explains the rationale (CanEqual would reject `String | Int` comparisons). Acceptable in tests, but the cast pair is uncommented inside the helper body. PASS.
  - UnionTest.scala:179 — same `asInstanceOf[AnyRef]` cast inline (not via the helper). Should call `sameRef` for consistency.
- **POLISH**: none.

### kyo-schema/shared/src/test/scala/kyo/NestedTransformTest.scala (new, 114 lines)

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**:
  - NestedTransformTest.scala:98-100 — `val Result.Success(decoded) = dec: @unchecked` followed by `decoded.result.asInstanceOf[NestedDiscDropRO.`string`]`. CONTRIBUTING §6 limits `@unchecked` to opaque/kernel internals; tests using it to dodge exhaustiveness is acceptable as a documented gotcha. Fix: prefer `assert(dec.isSuccess); val decoded = dec.getOrThrow` + a `match` with explicit failure branch.
- **POLISH**: none.

### kyo-schema/shared/src/test/scala/kyo/KeyCodecTest.scala (new, 49 lines)

- **MINOR**:
  - KeyCodecTest.scala — each test ends with `succeed` after `assert(...)` chains. Per CONTRIBUTING §Testing patterns (789-855), the canonical pattern is "return assert(...)" not "assert(...); succeed". The trailing `succeed` is harmless but redundant. Fix: drop `succeed` and let the final `assert` carry the assertion result.

### kyo-schema/shared/src/test/scala/kyo/StructureTest.scala (new, 74 lines), ProtobufTest.scala (new, 84 lines)

- Clean. Tests extend the module `Test`, fixtures are top-level, no `ignore`/`pending`.

### kyo-schema/jvm/src/test/scala/kyo/JavaEnumTest.scala (new, 38 lines)

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**:
  - JavaEnumTest.scala:8 — `given CanEqual[Any, Any] = CanEqual.derived` — same broad-equality pattern as CompositionMatrixTest. PASS but flag.
  - JavaEnumTest.scala — file lives in `jvm/` only because Java enums are JVM-specific. Correct per `feedback_all_platforms_all_tests` ("never demote to jvm/ to dodge infra cost" — here it's a real JVM-only feature).
- **POLISH**: none.

### kyo-schema/jvm/src/test/scala/kyo/CodecJvmTest.scala (new, 88 lines)

- Clean.

### kyo-schema/shared/src/test/scala/kyo/SchemaTest.scala (existing file, ~20 line diff)

- Pure test additions adapting to the new error message strings + new intersection-type rejection assertions. Clean.

### kyo-data/shared/src/main/scala/kyo/internal/TagMacro.scala (single 8-line addition)

- **BLOCKER**: none.
- **MAJOR**: none.
- **MINOR**: none. The block comment (49-53) names the bug and the fix; conforms to CONTRIBUTING §Inline Comments rule "explain why, not what".
- **POLISH**: none.

### Tests/internal — MacroUtilsDriftMacro.scala, SerializationMacroDriftMacro.scala (new, ~227 lines combined)

- Drift-detection scaffolding that compares two macro-generated symbol sets at compile time and reports divergence. Lives in `kyo.internal` so visibility is correct. Clean — no findings.

## Cross-cutting checks

1. **Frame.internal coverage**: PASS. Every `Frame.internal` site in `kyo-schema/shared/src/main/scala/kyo/Schema.scala` (29 sites) and macro-emitted code in `internal/SerializationMacro.scala` + `internal/UnionMacro.scala` is now covered by a block comment (added in commit `9f4992da4`). Verified: comments at Schema.scala:1444-1450 and 2112-2117 explicitly enumerate the giverns they cover ("Applies to every `Frame.internal` use in ..."), and SerializationMacro.scala:58-63, 166-170 + UnionMacro.scala:225-228, 255-258 each cover their local sites. No orphan sites found.

2. **asInstanceOf justification**: PARTIAL. The branch added at least 25 new `asInstanceOf` sites across Schema.scala, UnionMacro.scala, FocusMacro.scala, and SerializationMacro.scala. Existing precedent (Schema.scala:1196-1200, 2410-2413) sets the expectation that each site has an inline `// cast: ...` justification. The new sites in Schema.scala (frameSchema:1330, tagSchema:1340, resultSchema:2129/2136, eitherSchema:2188/2195, focus:142, foreach:903-906, resultOf:1029) lack inline comments. FAIL relative to the precedent. **Cite as MAJOR collectively**.

3. **`Schema.derived` vs manual `Schema.init`**: PASS for collections (List/Vector/Set/Chunk/Seq/Span/Queue/SortedSet/Map/SortedMap/Dict/Array/ArraySeq) — these require custom array-encoding write/read loops that `Schema.derived` cannot produce. PASS for `Maybe`/`Option`/`Result`/`Either` — discriminated-union shape requires custom logic. The Phase 12 tuple ladder correctly uses `Schema.derived` (1872-2108). Phase 11 java.time givens correctly use `stringSchema.transform[...]`. PASS overall.

4. **Tests under `kyo-schema/shared/src/test/`**: PASS. Every new test class extends `Test` (the module-local class), not `KyoTest`. Fixtures are top-level (visible to macros). No `ignored` / `pending` markers found in new tests.

5. **`kyo` vs `kyo.internal` packages**: PASS. `KeyCodec`, all new `given Schema[X]`s, the new `PrimitiveKind` enum cases, and `Schema` transforms live in the public `kyo` package. `UnionMacro`, `ExpandMacro` additions, `PlatformSymbols`, `PlatformSchemas`, and drift macros all live in `kyo.internal`. Clean.

6. **No semicolons**: MAJOR. Found at Schema.scala:1407 (`r.skip(); ()`), 2156, 2206 (`reader.skip(); loop(...)`), and FocusMacro.scala:807, 810 (macro-emitted code with `;`). Pre-existing semicolons in JsonReader.scala (~15 sites) were untouched on this branch. The five newly-touched sites should be cleaned up per `feedback_no_semicolons`.

7. **No symbolic operators in user-facing API**: PASS. New methods all use English names (`drop`, `rename`, `select`, `add`, `flatten`, `check`, `discriminator`, `transform`, `convert`, `focus`, `foreach`, `fold`, `toRecord`, `defaults`, `structure`, `encode`, `decode`, etc.). No `~>`, `<*>`, etc.

8. **Documentation**: PARTIAL. Schema.scala primitive/collection/tuple givens have one-line scaladocs (consistent with the file's convention). New `KeyCodec` givens at 24-49 LACK per-given scaladocs (4 missing). New `PlatformSchemas` givens at 48-71 LACK per-given scaladocs (7 missing). Otherwise comprehensive — `derive` in `UnionMacro`, every new method on `Schema`, `enrichJsonSchema`, etc. have appropriate scaladoc. Fix: add 11 missing one-line scaladocs.

9. **Inline-given correctness**: PARTIAL. Schema.scala:949-956 (`inline given order`, `inline given canEqual`) are inline without obvious benefit — they take no function or by-name params (CONTRIBUTING §Inline Guidelines, line 738-744 lists "Methods that take only value parameters" as "DO NOT inline"). Drop `inline`. The Phase 7-15 new givens (`KeyCodec`, Phase 13 Queue/SortedSet, Phase 11 java.time) are correctly non-inline.

10. **Implicit-search ergonomics**: PASS. `KeyCodec` givens live in the `KeyCodec` companion. New `Schema` givens live in `Schema` companion. `PlatformSchemas.given` requires explicit import (documented in scaladoc) — acceptable for platform-bound types because importing them on JS/Native would cause compile errors.

## Summary

- BLOCKER count: 0
- MAJOR count: 7 (one of which is multi-site: `asInstanceOf` justification gaps)
- MINOR count: ~22
- POLISH count: 4

## Top-priority fixes

1. **Add inline `// cast: ...` comments to every new `asInstanceOf` site** in Schema.scala, UnionMacro.scala, FocusMacro.scala (Phase 14 Java-enum branch), SerializationMacro.scala (Result specialization), and SchemaSerializer.scala (`@unchecked` Maybe match). Precedent at Schema.scala:2410-2412. Aim for one-line `// cast: <why>` next to each site. (MAJOR)
2. **Remove semicolon-chained statements** at Schema.scala:1407, 2156, 2206 and FocusMacro.scala:807, 810. Per `feedback_no_semicolons`. (MAJOR)
3. **Add the missing 11 per-given scaladocs** in KeyCodec.scala (4) and PlatformSchemas.scala (7). One-line each. (MINOR)
4. **Drop `inline` on Schema.scala:949 `given order` and :956 `given canEqual`** — no closure to eliminate. (MINOR)
5. **Drop default-parameter values in `Schema.init` at Schema.scala:1135-1136** — defaults on internal APIs violate `feedback_no_default_params_internal`. Update macro callers to pass identity functions explicitly. (MINOR)
6. **Pass the swallowed cause in UnionMacro.scala:329** — `case NonFatal(t) if !dispatched` discards `t`. Forward via `TypeMismatchException`'s `cause` slot or add a `Log.warn`. Aligns with `feedback_log_unexpected_failures`. (MINOR)
7. **Replace `Json()` allocation in KeyCodec.scala:33/41/48** with a `KeyDecodeException` or a `format: Maybe[Codec]` overload on `ParseException`. (MINOR)

## Overall verdict

The branch is in good shape for a 23-commit, ~3500-line addition. There are **zero BLOCKERs** — no `AllowUnsafe` leakage, no public API leaking into `kyo.internal` (or vice versa), no `@uncheckedVariance`, no `Thread.sleep`/blocking primitives, no `protected`. The unsafe boundary is respected: the `Frame.internal` carve-out for macro-emitted code is correctly annotated (commit `9f4992da4`), and the test/fixture hygiene meets the bar (top-level fixtures, module-local `Test` class, no `pending` markers).

The MAJORs are dominated by two patterns: (a) missing inline justification comments on the new `asInstanceOf` sites — the precedent in the same file (Schema.scala:2410-2412) is clear about the standard, and (b) five touched lines using `;`-chained statements that the project-wide no-semicolons rule forbids. Both are mechanical to fix in a single follow-up commit.

The MINORs cluster on documentation polish (11 missing per-given scaladocs), one over-eager `inline` (Schema.scala givens that have no closure parameters), one `discFieldIdStr` constant that could be extracted, and a handful of test-time `CanEqual[Any, Any]` widenings that are typical of multi-type test files. None are correctness issues.

If the seven top-priority fixes above land, the branch passes the CONTRIBUTING.md bar.
