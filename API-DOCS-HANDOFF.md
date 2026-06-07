# Handoff: kyo-tasty as the engine for self-rendered getkyo.io API docs

**From:** the getkyo.io website work (branch `website`).
**To:** the `kyo-tasty` agent (branch `kyo-tasty`, worktree `cached-inventing-quasar`), before/with the merge.
**Date:** 2026-06-06.

## TL;DR

We want to stop embedding Scala 3 scaladoc on getkyo.io and instead **generate the API
docs ourselves at build time on top of `kyo-tasty`**. The reason is structural, not
cosmetic: scaladoc renders Kyo's central idiom (opaque type + companion + `Ops` implicit
class + `extension` block) poorly, and drops the opaque type's own docstring entirely. We
own presentation only if we read the model ourselves.

**I audited the `kyo-tasty` public surface against everything the generator needs. It is
already sufficient — there is nothing kyo-tasty *must* add.** This doc records (a) the
exact surface we will consume so you don't regress it on merge, (b) a few small
confirm/document asks, and (c) one high-value no-regression guarantee (kyo's own idioms
must decode). The website-side generator (page model, comment cleaning, templates,
theming, sidebar, `A < S` rendering) is **our** job, not yours.

## Why we're doing this (the scaladoc limitation, grounded in `kyo.Maybe`)

`kyo-data/shared/src/main/scala/kyo/Maybe.scala` is one logical type:
`opaque type Maybe[+A]` (with a real docstring "Represents an optional value…"), a
companion `object Maybe` (constructors), an `implicit final class Ops` (some instance
methods), and an `extension [A](self: Maybe[A])` block (most instance methods). Scaladoc:

- emits **no page for the opaque type itself** (only `Maybe$.html`, the companion) and
  **drops the type's docstring**;
- scatters the instance API across "Extensions" + the `Ops` classlike + companion "Value
  members" (three buckets for one type);
- shows a meaningless inheritance graph / Supertypes / Self type panel for the opaque type.

Embedding/re-theming scaladoc cannot fix any of that (it's content scaladoc never emits).
A model-driven render can: one page per opaque type, its docstring as the lede, companion
+ `Ops` + `extension` merged into a single "Operations on `Maybe`" list, no inheritance
graph, and the `A < S` pending type rendered the way our prose docs explain it.

## Capability audit (verified present in `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala`)

Everything below was read directly from the source on branch `kyo-tasty`. The consumed
surface for the generator:

### Acquisition
- `Tasty.withClasspath(roots: Seq[String])(f)` -> `A < (Async & Abort[TastyError] & S)`;
  also `withClasspath(roots, Present(cacheDir))`, `withPickles(pickles)`,
  `withClasspath(cp: Classpath)`. We'll point it at each module's compiled output
  (`<module>/jvm/target/scala-3.8.3/classes`) at website build time (JVM).
- `Tasty.supportedTastyVersion` = `28.8.0`.

### Lookup / enumeration (all `< Sync`)
- `findClass`/`findClassLike`/`findObject`/`findSymbol`/`findPackage`/`findMethod` by FQN
  (+ `require*` variants `< (Sync & Abort[TastyError])`).
- `allClassLike`/`allClasses`/`allObjects`/`allTraits`/`allMethods`/`allVals`/`allVars`/
  `allFields`/`allTypes`/`allPackages`.

### The `Symbol` model (public ADT, `Tasty.Symbol`, `derives Schema, CanEqual`)
Sealed, 13 cases: `ClassLike` -> `Class`, `Trait`, `Object`, `EnumCase`; plus `TypeAlias`,
`OpaqueType`, `AbstractType`, `TypeParam`, `Method`, `Val`, `Var`, `Field`, `Parameter`,
`Package`. Common fields on the base trait: `id`, `name`, `flags`, `ownerId`,
**`scaladoc: Maybe[String]`**, `sourcePosition: Maybe[Position]`.
- `Symbol.OpaqueType(id, name, flags, ownerId, scaladoc, sourcePosition, body: Maybe[Type], bounds: TypeBounds, typeParamIds, annotations)` — carries the docstring AND the underlying `body` type.
- `Symbol.Method(... scaladoc, sourcePosition, declaredType: Maybe[Type], paramListIds: Chunk[Chunk[SymbolId]], typeParamIds, annotations ...)`.

### Navigation (all `< Sync`)
- `owner`, `ownersChain`, `parents(classLike)`, **`companion(sym)`** (pairs opaque type <-> `object`),
  `permittedSubclasses(cls)` (sealed children), `typeParams(sym)`,
  **`members(sym, scope = MemberScope.Declared | Inherited | All)`**, `findMember`,
  `declarations(sym)`.

### Rendering / signatures (all `< Sync`)
- `signature(method)`, `show(sym, ShowFormat.{FullyQualified|Simple|Code})` (Code = source-shaped decl),
  `fullName`, `binaryName`, `typeShow(tpe)`, `typeSymbol(tpe)` (resolve a `Type.Named` to its `Symbol`),
  `treeShow`, and `bodyTree(sym)` `< (Sync & Abort[TastyError])` for implementations.
- `Type` is a sealed enum (~26 cases: `Named`, `Applied`, `Function`, `ContextFunction`,
  `Tuple`, `AndType`, `OrType`, `Annotated`, `ByName`, `Repeated`, `ConstantType`,
  `Refinement`, `Nothing`, `Any`, …) — enough to render `A < S` ourselves.

### Modifiers / metadata
- `flags: Flags` with ~40 predicates incl. `isFinal`, `isAbstract`, `isCase`, `isSealed`,
  `isPrivate`, `isProtected`, `isInline`, `isImplicit`, `isGiven`, `isOverride`, `isLazy`,
  **`isExtension`**; plus `Visibility` and `OpenLevel` enums.
- `hasAnnotation(sym, fqn)`, `findAnnotation(sym, fqn)`, `symbolsAnnotatedWith(fqn)`;
  `Parameter.annotations`, `Parameter.declaredType` (ByName/Repeated aware), `Parameter.defaultArgId`.
- `Position(sourceFile: String, line: Int, column: Int)` with `.show` -> source links.

### Doc comments — already exposed (the key finding)
`scaladoc: Maybe[String]` is a public field on every `Symbol` case (base-trait accessor at
`Tasty.scala:2728`). It is decoded by `CommentsUnpickler` (Comments TASTy section),
attached in `ClasspathOrchestrator` Pass C (`descs(idx).scaladoc = Maybe(text)`), copied
into the final symbol by `TypedSymbolFactory` (`scaladoc = d.scaladoc`), and serialized in
the snapshot (`SnapshotReader` carries `scaladoc = partial.scaladoc`). So the generator
reads `sym.scaladoc` directly. **No new doc-comment query is needed.**

### Cross-platform
`kyo-tasty` is a crossProject (jvm/js/native). We consume the JVM build at website build time.

## Asks (small; all fit inside the merge)

1. **Document `scaladoc` (and `sourcePosition`).** The README's "common fields" list
   (`id, name, flags, ownerId`) omits `scaladoc` and `sourcePosition`, and there is no
   section describing them, even though they're public and load-bearing for tooling. Please
   add a short "Doc comments" note: that `Symbol.scaladoc: Maybe[String]` exists, and the
   exact **text contract** — is it the raw comment **including** the `/** … */` delimiters
   and leading `*` margins (what dotty stores), or already stripped? We will do the
   cleaning + `@param`/`@return`/markdown parsing on the website side; we just need the
   form documented so we strip correctly.

2. **Confirm + document the extension-method representation.** This is the load-bearing
   query for the opaque-type merge. For `extension [A](self: Maybe[A]) def map(...) = …`,
   please confirm and document that the method surfaces as a `Symbol.Method` with
   `isExtension == true`, owned by the enclosing object (so it appears in
   `members(companion, Declared)`), and that the **receiver** is recoverable as the leading
   entry of `paramListIds` (i.e. `paramListIds.head` -> a `Parameter` whose `declaredType`
   resolves via `typeSymbol` back to `Maybe`). That lets us group "operations on `Maybe`".
   If the shape differs (e.g. receiver stored elsewhere), document where, so we can group
   correctly. Same question for `implicit final class Ops[A](maybe: Maybe[A])` members:
   confirm `members(Ops, Declared)` returns its `def`s and the `maybe` receiver param is
   recoverable.

3. **One no-regression guarantee: kyo's own idioms must decode.** Kyo leans hard on opaque
   types, givens, `inline`, and macros, which can stress a TASTy reader. Please add (or
   confirm) a fixture/smoke test that decodes a **real kyo module** (ideally `kyo-data`)
   and asserts the full chain for `Maybe`: the `OpaqueType` symbol is found, its
   `companion` resolves, `members`/extension methods come through, and `scaladoc` is
   `Present` on the opaque type and on at least one extension method. A green check here is
   the single most valuable thing for us — it certifies the engine handles the exact
   pattern the docs feature exists to render.

4. **Treat the consumed surface as stable through merge.** The website will depend on the
   `Tasty.*` and `Tasty.Symbol.*` names listed in the audit above. If any are renamed or
   change effect rows before merge, please flag it here (or in the kyo-tasty PR) so we
   update in lockstep rather than discover it post-merge.

5. **Confirm the TASTy version matches kyo's toolchain.** `supportedTastyVersion` is
   `28.8.0`; kyo builds on Scala `3.8.3`. Please confirm kyo's own emitted `.tasty` decodes
   (no `TastyError.UnsupportedVersion`) so the generator can read kyo's compiled output
   directly.

## Explicitly NOT asks (the website owns these)

- Comment cleaning / `@param`/`@return` extraction / markdown rendering of docstrings.
- The doc page model (grouping opaque type + companion + `Ops` + `extension` into one page),
  HTML templates, theming, sidebar "API" section, SPA links, the `A < S` pending-type
  rendering, and replacing the javadoc.io "API" link.
- Running the generator at website build time and wiring it into the render-at-build pipeline.

## Pointers
- Public API: `kyo-tasty/shared/src/main/scala/kyo/Tasty.scala` (Symbol ADT ~2720-3070; query defs throughout).
- Comments path: `internal/tasty/reader/CommentsUnpickler.scala` -> `internal/tasty/query/ClasspathOrchestrator.scala` (Pass C) -> `internal/tasty/symbol/{SymbolDescriptor,TypedSymbolFactory}.scala` -> snapshot in `internal/tasty/snapshot/SnapshotReader.scala`.
- README: `kyo-tasty/README.md` (650 lines; Symbol model, navigation, types, flags/visibility).
