# 03 Candidates: pass-1 extraction of open questions from 02-design.md

Source plan: `kyo-tasty/audit-fixes/02-design.md`
Run id: `stage5-pass1`
Validate run: none (Stage 5 mode)
Record count: 9

Each row carries the Q-NNN id from 02-design.md verbatim, the rewritten one-sentence question, the type classification, the single recommended answer, and the source citation. Rejected alternatives are in the JSON sibling.

## Q-001: Subtyping under-determination signal shape

- Type: value-underdetermined
- Scope: `02-design.md:653`; `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:1091`; `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/type_/Subtyping.scala:18,144`
- Question: What return shape should the public Subtyping accessor use to surface the under-determination signal (subtype, not-subtype, under-determined)?
- Recommended answer: Use `Maybe[Boolean]` (Present(true) subtype, Present(false) not-subtype, Absent under-determined). Matches existing kyo Maybe idiom (Symbol.scaladoc, Symbol.position, Symbol.companion); smaller type surface than a new enum; replaces Boolean outright per the no-backwards-compat rule.

## Q-002: M5 ZLIB cross-platform path

- Type: research-knowable
- Scope: `02-design.md:656`; `kyo-tasty/js/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala:1-10`; `kyo-tasty/native/src/main/scala/kyo/internal/tasty/scala2/InflateHook.scala:1-10`
- Question: Which ZLIB inflate strategy should the JS and Native InflateHook implementations adopt for cross-platform RFC 1950 parity with the JVM InflaterInputStream?
- Recommended answer: Port RFC 1950 inflate in tree as pure Scala under `kyo-tasty/shared`; JS and Native objects delegate to the shared implementation. Minimal dependency surface, full control over cold-load and warm-cache performance, no risk of upstream library going stale or fragmenting JVM/JS/Native artifact resolution. Research agent should still enumerate pure-Scala ZLIB candidates and override only if a battle-tested cross-platform library exists.

## Q-003: Tree decoder decomposition axis

- Type: value-underdetermined
- Scope: `02-design.md:659`; `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/reader/TreeUnpickler.scala:129,186,216-220,258,264,268,313-317,323,380,512,519,587-596,707`; `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:394-492`
- Question: Which decomposition axis should the TreeUnpickler use to organize the missing TASTy AST tag decode branches that currently fall through to Tree.Unknown?
- Recommended answer: Category-based decomposition (terms, definitions, type-trees, patterns) matching the TASTy spec section divisions. Aligns with the upstream spec partitioning; gives each category its own phase boundary in the plan; mirrors how tasty-query and dotty Tasty printer organize their decoders.

## Q-004: TastyError byteOffset enrichment scope

- Type: value-underdetermined
- Scope: `02-design.md:662`; `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala:7-22`
- Question: Should the byteOffset enrichment on TastyError.MalformedSection apply uniformly to every malformed-section case site, or only to cases that already carry a structural at: Long payload?
- Recommended answer: Add `byteOffset: Long` to every MalformedSection construction site uniformly. The byteOffset is available wherever the error is built; uniform enrichment avoids a two-tier error shape; debuggability is the dominant value driver for L5. The case becomes `MalformedSection(name: String, reason: String, byteOffset: Long)`.

## Q-005: Snapshot format version bump scope

- Type: research-knowable
- Scope: `02-design.md:665`; `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotFormat.scala:42-44,57-58`; `kyo-tasty/shared/src/main/scala/kyo/internal/tasty/snapshot/SnapshotReader.scala:170-177`
- Question: Does the M4 snapshot format extension warrant a minor (add-only) or major (invalidating) version bump?
- Recommended answer: Minor bump if the new Parents / TypeParams / Declarations sections append without altering existing field widths or section ordering; major bump if any existing Int-typed length field must widen to Long. Research agent confirms by inspecting SnapshotFormat field types. Default expectation per the existing format shape is minor (add-only).

## Q-006: AllowUnsafe callsite proof availability

- Type: research-knowable
- Scope: `02-design.md:668`; `audit-findings.md:82`; `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:60-688`
- Question: After the routine Symbol and Classpath accessors take (using AllowUnsafe), does every existing callsite in kyo-tasty, kyo-ts, and kyo-flow either have AllowUnsafe in scope or sit inside Sync.Unsafe.defer?
- Recommended answer: Yes. Every callsite of the listed accessors either has `using AllowUnsafe` in scope (internal decoder code under `kyo.internal.tasty.*`) or can adopt `Sync.Unsafe.defer` at the boundary (downstream kyo-ts code generators, kyo-flow workflow scanners, kyo-tasty test scaffolding). Research agent enumerates per-file and reports any site that resists both options.

## Q-007: Classpath.open canonical-impl selection

- Type: research-knowable
- Scope: `02-design.md:671`; `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala:899,903`
- Question: Which of the two Classpath.open overloads is the canonical implementation that the other delegates to?
- Recommended answer: The `(roots, strict)` overload is canonical and owns the body; the no-strict overload delegates by name to the canonical with `strict = false` made explicit. Matches the canonical-impl-plus-variants pattern at CONTRIBUTING.md §358-§374. Research agent confirms by counting per-file callsite frequency; the rule fixes which arity is canonical regardless of count distribution.

## Q-008: README Reflect.Reads existence

- Type: research-knowable
- Scope: `02-design.md:674`; `kyo-tasty/README.md:41`
- Question: Does the README's Reflect.Reads reference correspond to a real typeclass or facade in the kyo-tasty source, or is it an aspirational name from the pre-rename Reflect.* nomenclature?
- Recommended answer: Aspirational. The kyo-tasty source tree contains no Reads typeclass or facade; the README mention should be removed during the L1 / L2 doc rewrite. Research agent confirms by grepping `kyo-tasty/{shared,jvm,js,native}/src/main/scala/kyo/` for any `Reads` identifier; if a match exists, the rewrite documents its real Tasty.* name.

## Q-009: kyo.tasty.examples package placement

- Type: value-underdetermined
- Scope: `02-design.md:677`; `kyo-tasty/shared/src/main/scala/kyo/tasty/examples/CodegenExample.scala:1`
- Question: Where should the four kyo.tasty.examples files live to comply with the kyo / kyo.internal.tasty two-namespace convention without losing their public-documentation role?
- Recommended answer: Keep at `kyo.tasty.examples` with a top-of-file explanatory comment in each file justifying the deviation from the two-namespace convention. Examples are deliberately public so users browse them from the published jar; relocating to `kyo.internal.tasty.examples` mislabels them as internal; extracting to a sibling `kyo-tasty-examples` module adds build and publish overhead without user benefit.
