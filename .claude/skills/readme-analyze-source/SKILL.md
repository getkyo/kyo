---
name: readme-analyze-source
description: Analyze a module's source code to produce a spine candidate, callout list, and API surface inventory for use by readme-draft and readme-analyze-existing.
argument-hint: <source-dir>
---

# readme-analyze-source

Read the module source directory given in `$ARGUMENTS` and produce three artifacts: a spine candidate, a list of callout candidates, and an API surface inventory. These artifacts are consumed by `readme-draft` and `readme-analyze-existing`. This skill does NOT draft a README, evaluate any existing README, or verify output.

## What you produce

```
## Spine candidate
[use-case-first|type-first]
<spine paragraph here>

## Callout candidates
- <source file>:<line> -- <one-line summary>
- <file-A>:<line-A> + <file-B>:<line-B> -- <cross-API seam summary>
- ...

## API surface inventory
- <load-bearing type/method>: <one-line description of role>
- ...

## Section clusters
- "<Cluster name (use, not API)>" -- <one-sentence mental model>
  - <API in this cluster>: <one-line role>
  - ...
- ...

## Running-domain seeds
- <case class declarations + which clusters they can demonstrate>
- ...
```

Output these five sections and nothing else. The downstream skills consume this structure directly.

---

## Input

A path to a module source directory (for example, `kyo-http/`).

This sub-skill MUST NOT read the existing README at the module path even if one exists. The spine candidate produced here is the 0(a)(i) source-only draft that downstream cleanup discipline depends on. Reading the existing README would anchor the spine on its wording and defeat the technique.

---

## Step 1: read the source

Scan these locations in order:

1. The package object's scaladoc (top of `package.scala` or `package object` in the primary source tree).
2. Top-level scaladocs on the main public types and the primary entry-point companions.
3. Method-level scaladocs on load-bearing methods (especially those with qualifiers: `Unsafe`, `Lazy`, `Default`, `Async`).
4. Test case names under `src/test`: names shaped "fails when...", "returns empty when...", "throws when..." describe edge behavior.
5. Implementation comments shaped "// This looks wrong but..." or "// Note:" in source.

Do NOT read the existing README during this step. The point is a fresh, source-grounded read.

---

## Step 2: the spine candidate (THE PIVOTAL DECISION)

The spine is a 1-or-2-paragraph mental-model paragraph: the worldview the reader needs before any API name matters. Before drafting, answer this question:

**What does the typical reader's first useful call site look like?**

This single question determines the spine pattern. Two patterns exist:

### Type-first spine

Use when the user's first call directly constructs or names the central type. The spine names the type and threads it through the worldview.

Examples where type-first is correct:
- `case class User(...) derives Schema` (kyo-schema: the user names `Schema` immediately).
- `Flow.init("name").output(...)` (kyo-flow: the user builds a `Flow` directly).
- `Maybe.present(x)` / `Chunk.from(...)` (kyo-data: the user constructs the type directly).

For these libraries, naming the central type in the spine matches the reader's actual workflow.

### Use-case-first spine

Use when the user's first call goes through convenience APIs that HIDE the central type. The spine names the user-facing workflow; the architectural type is introduced later, when the curriculum reaches it.

Example where use-case-first is correct: kyo-http. The user's first call is `HttpClient.getText(url)`. The architecturally central type is `HttpRoute[In, Out, E]`, but users rarely construct it directly in simple cases. Naming `HttpRoute[In, Out, E]` in the spine is premature load: the reader gets type-level machinery before they need it.

The failure mode an agent must avoid: read source, identify the architecturally-central type, name it in the spine even when the typical user will rarely touch it directly. The READER's perspective is "what's the first thing I'd write to use this?", not the library author's perspective "what's the load-bearing type underneath?"

### Three wrong spine shapes to avoid

- **No spine**: jump straight to a flat capability list ("handles JSON, text, binary, streaming via SSE and NDJSON...") with no worldview at all.
- **Premature deep spine**: front-load the central type even when users rarely touch it directly ("the central abstraction is `HttpRoute[In, Out, E]`...").
- **Pure pitch**: the opening reads like marketing ("fast, type-safe, cross-platform HTTP for Scala") without establishing a mental model.

### Calibration examples

These are the reference implementations to calibrate against:

**kyo-flow (type-first)**: "A `Flow` is a plan, not an execution. You describe what should happen (values to compute, inputs to wait for, side effects to perform, branches to take) and the engine handles the rest. Every step is checkpointed to a store before the next begins. If the process crashes, another executor claims the work and replays from the last checkpoint, skipping steps that already completed. Side effects in steps must be idempotent because they may re-execute on recovery."

After this paragraph, the reader already understands: plan vs. execution, step granularity, checkpointing, recovery, idempotence-as-constraint. Type-first is correct because users name `Flow` in their very first call.

**kyo-http (use-case-first)**: "Kyo's HTTP/1.1 client and server module. Both client and server share a single API that compiles across JVM, JavaScript, and Scala Native, with platform-specific backends handling the actual I/O."

The reader knows: one API, multiple I/O backends, both client and server share it. `HttpRoute` is NOT mentioned in the spine because the typical user's first call is `HttpClient.getText(url)`, not a route construction.

**kyo-schema (type-first)**: "Define a case class and get JSON serialization, Protobuf encoding, field validation, type-safe lenses, structural diffs, and more, all derived from the type's structure." Then: "Everything flows from `Schema[A]`, the central type that captures a type's structure at compile time. It's the single source of truth that powers serialization, validation, navigation, and conversion."

Type-first is correct because `derives Schema` is the user's literal first line.

### Tag the spine

At the top of the spine candidate, add one of:

- `[type-first]` when the central type IS what the user's first call produces.
- `[use-case-first]` when the user's first call uses convenience APIs that hide the central type.

---

## Step 3: callout candidates

Scan source for surfaces where a reader's natural expectation will not match the actual behavior. Sources:

- **Scaladoc signals**: `Note:`, `Unlike X`, `Caution:`, `Be aware`, `WARNING`, design-rationale paragraphs. These mark surfaces the author already identified as non-intuitive.
- **API names with qualifiers**: `Unsafe`, `Lazy`, `Default`, `Async`, `Force*`, `*Or*`. The qualifier is in the name because the unqualified version is the expected default and the qualified version deviates.
- **Test shapes**: tests named "fails when..." or "returns empty when..." describe edge behavior the README should preview.
- **Implementation comments**: "// This looks wrong but..." paragraphs.
- **Your own reading**: when a sentence or use site makes you think "wait, but I'd expect X," that is a callout candidate.

For each candidate, record the source file path and line number, and a one-line summary of the surprise.

### What counts as non-intuitive (worth listing)

- Defaults that surprise: a connection pool that is global when you expect per-service.
- Behavior that differs by platform or mode.
- APIs that look the same but behave differently (same name, different type-level behavior).
- Restricted contexts (a method callable only when a capability type is in scope).
- Common expectations that do not hold (derivation works for case classes but not for case classes with higher-kinded type parameters).
- Cross-cutting interactions that deadlock or error only when two APIs are combined.

### What does NOT count (omit from the list)

- The surface is obvious from the name and type signature.
- The "surprise" is standard Scala or kyo behavior the reader already knows from the root README.
- The callout would explain a design choice rather than warn about a behavior.

Produce 5-10 candidates for a typical module. More is acceptable when the source is callout-dense.

### Companion script

The companion script's `--callouts` mode surfaces candidates from source-side signals:

```
bash .claude/skills/readme/readme-check.sh <module-dir>/README.md --callouts
```

Use this when the README exists. When the README does not exist yet, rely on your source scan from Step 1.

---

## Step 4: API surface inventory (conceptual pass)

List the load-bearing concepts, types, and methods that a downstream `readme-draft` must introduce to teach the module. Each entry is a name plus a one-line description of its role in the curriculum.

Ordering rules:
- First: the spine concept(s) the entire module builds on.
- Then: primary entry points the user will call first.
- Then: supporting types and methods the primary entry points return or require.
- Last: advanced or rarely-touched surfaces.

The inventory is curriculum order, not alphabetical order. A reader working through a README should encounter concepts in roughly this order.

Do not include implementation-private types, build-configuration DSL, or cross-compilation setup. Do include cross-platform behavior claims ("JVM, JavaScript, and Scala Native") as a single note, not a type entry.

---

## Step 4b: section clustering (teach, do not enumerate)

A curriculum-ordered inventory is still a catalog. A README built section-per-API teaches the reader the API list; a README built section-per-mental-model group teaches them the design.

After Step 4, cluster the inventory entries into mental-model groups. Each cluster will become ONE `##` section in the README. The cluster name is what a reader is TRYING TO DO or the conceptual group the APIs belong to, not the name of any single API in the cluster.

### What counts as a cluster

Group APIs together when at least one of these holds:

- **Increasing-complexity siblings.** Three APIs that describe variants of the same task at different power levels (e.g. `Compare` = read-only diff, `Modify` = batched writes, `Changeset` = serializable diff). One cluster called "Comparison and Mutation" or "Describing Changes."
- **Format-family siblings.** `Json`, `Protobuf`, plus any future format. One cluster called "Serialization" (sub-headed per format), not one section per format. (Exception: when a format has substantial unique surface like JSON Schema generation, that gets its own cluster.)
- **Shared mental model.** `Focus`, `Modify`, `Compare`, `Changeset` all use focus-lambda navigation. Where the shared idiom is itself the point of the cluster, name the cluster after the idiom (e.g. "Navigation and lenses" or "Operations on focused fields").
- **Lifecycle or workflow stages.** `Builder` (incremental construction), `Schema.derived` (capture), `Schema.transform` (reshape), all stages of "how a value becomes/changes a schema-typed thing." Cluster called "Working with values" or similar.
- **Public extension points.** SPI, custom codec implementation, custom schema for opaque types. One cluster called "Extending kyo-X" with subsections per extension point.

### What does NOT cluster

- APIs whose only commonality is "they're in the module." Don't force a unifying name. If two APIs are conceptually orphan, give each its own section, named by use.
- APIs at different audience levels. Application-author APIs (e.g. `Json.encode`) and library-author APIs (e.g. `Codec.Writer`) belong in different clusters even when they touch the same concept.

### Cluster naming rules

- Use **noun phrases or gerunds** that name what the reader is trying to do or the concept being grouped: "Comparison and Mutation", "Construction", "Structural Introspection", "Custom Formats", "Cross-platform behavior."
- Do NOT use API names as section names. "Modify" / "Compare" / "Changeset" as section names is the V2 failure pattern. They go INSIDE the "Comparison and Mutation" section as `###` sub-headings.
- Do NOT append ": <what it does>" suffixes to API-named sections. "Modify: batched mutation" is still an API-named section.

### Output addition

After the API surface inventory in Step 4's output, add a `## Section clusters` block:

```
## Section clusters
- "Comparison and Mutation" -- three views of describing changes between values, in increasing complexity
  - Compare: read-only diff
  - Modify: batched writes
  - Changeset: serializable diff
- "Construction"
  - Builder: type-safe incremental case-class construction
- "Custom Formats" -- public extension point for adding new wire formats
  - Codec / Codec.Writer / Codec.Reader: writer/reader contract
- ...
```

This is the contract the draft skill consumes. The clusters are the section list of the README, in order.

### Running-domain seeds

While clustering, identify 1-3 candidate case classes (or other central values) that can serve as the README's running domain. A good seed:
- Has 3+ fields, at least one nested (e.g. `User` with an `Address`).
- Allows demonstrating most clusters (encodable, focusable, validatable, transformable, diff-able).
- Reads as realistic: a domain a reader would actually model.

Add to the output:

```
## Running-domain seeds
- case class User(id: Int, name: String, email: String, password: String, address: Address)
  case class Address(city: String, zip: String)
  Demonstrates: Serialization (encode/decode), Navigation (deep focus), Transforms (drop sensitive field), Comparison (diff two users), Validation (constraints on fields).
- ...
```

The draft skill will pick ONE of these and thread it through the entire README. Per-section ad-hoc case classes are forbidden by draft discipline unless the section's content fundamentally requires a different shape (e.g. a sealed-trait example for sum-type navigation).

---

## Step 5: mechanical enumeration (completeness check)

The conceptual pass groups APIs by slot ("format-side entry points", "metadata builders", "navigation"). Surfaces that don't fit a slot are routinely dropped. This is the most common failure mode the verify gate has caught. Defense: a mechanical pass that enumerates every public name and checks it appears in Step 4's inventory.

For each public source file in `<module-dir>/shared/src/main/scala/kyo/` (and platform-specific public dirs if any), run:

```
grep -nE '^( {2,4})?(inline |transparent inline )?(def |given |val |object |extension |trait |class |sealed |type )' <file>
```

Filter out private/internal entries (anything in a `package internal` file, or marked `private[kyo]` / `private`). For each remaining public name, check: does it appear in Step 4's inventory either by name or as part of a parent-type entry?

If a name is missing, add it. Group orphans under a generic `Other public surface` heading at the end of the inventory; do NOT silently drop them because they don't fit a conceptual slot.

Common orphan categories (these reliably get dropped without the mechanical pass):

- **Givens that are not the primary type-class instance.** `given Ordering[A]`, `given CanEqual[A, A]`, `given Eq[A]`, `given Hash[A]` on the central type. These are orthogonal to format / data / metadata slots but real public surface.
- **Inline accessors and introspection methods.** `fieldNames`, `fieldDescriptors`, `defaults`, `toRecord`, `fold`. These live on the central type but read structure rather than transform values, so they don't fit transform / navigation slots.
- **Compound-type sub-APIs.** If `Structure.of` is in the inventory but `Structure.Path` / `Structure.Type` operations (`compatible`, `fold`, `fieldPaths`) are not, the analyzer captured the entry-point but didn't walk into ops on the returned types. Walk one level deeper.
- **Module-level admin/ops surfaces.** `FlagAdmin`, `FlagSync`, debug consoles, runtime introspection. These don't fit the main curriculum slot but are real ops surfaces.

The mechanical pass is a check, not a replacement. The conceptual inventory carries curriculum order and grouping; the mechanical pass guarantees nothing is silently dropped.

---

## Step 6: cross-API interaction-footgun scan

Per-method scaladoc callouts (Step 3) catch in-place gotchas. They miss footguns that live in the **seam between two surfaces**: a transform producer + a format consumer, a config setter + a separately-documented default, a file-generator + a wire-encoder.

For each module, walk the inventory and ask:

1. **Transform → format-consumer pairs.** If a method returns a new instance of the central type (`Schema#drop`, `Schema#rename`, `Schema#add`), does the format consumer (`Json.encode`, `Protobuf.encode`) summon the implicit instance from scope, or use the transformed instance? If from scope: this is the implicit-summoning trap. List as a callout: "`s.drop(_.field)` returns a transformed schema, but `Json.encode(value)` summons the *original* implicit `Schema[A]` from scope and ignores the transform. Use `s.encode[Json](value)` to apply the transform."
2. **Generator → encoder pairs.** If two methods describe the same artifact at different layers (`Protobuf.protoSchema` vs `Protobuf.encode`), do they agree on identifiers, ordering, naming? List any disagreement.
3. **Default conflicts.** If two configs / defaults describe the same surface, do they agree? (E.g. `HttpServerConfig.default.host` vs documented default in the project's existing README — flag if they disagree.)
4. **Lifecycle interaction.** Does a method work inside a particular scope only? (E.g. handlers requiring `Scope` in the effect row; `HttpServer.initUnscoped` needing explicit `close`.)
5. **Filter / middleware compositions.** Does combining two specific filters error or deadlock?
6. **Implicit-conversion ambient effects.** If `Convert[A, B]` is a `given`, does providing it create surprising at-a-distance conversions? List as a callout.

For each pair-level footgun found, add a callout entry sourced from the *interaction* rather than one line. Format:

```
- <file-A>:<line-A> + <file-B>:<line-B> -- <one-sentence summary of the seam>
```

Two source anchors mark interaction callouts. Single anchors are per-method.

---

## Output contract

Produce exactly five sections in this order, using these headings verbatim:

```
## Spine candidate
[use-case-first|type-first]
<1-or-2-paragraph spine here>

## Callout candidates
- <source file>:<line> -- <one-line summary>
- <file-A>:<line-A> + <file-B>:<line-B> -- <cross-API seam summary>
- ...

## API surface inventory
- <name>: <one-line description>
- ...

## Section clusters
- "<cluster name>" -- <mental-model paragraph>
  - <API>: <role>
  - ...
- ...

## Running-domain seeds
- <case-class declarations>
  Demonstrates: <list of clusters>
- ...
```

Do not add prose before or after these five sections. The downstream skills read this output structurally.

---

## Execution

This sub-skill runs as a single Opus agent dispatched by the supervisor. You do NOT dispatch further sub-agents (your context already has the Agent tool unavailable). All work happens in your own context:

1. Read the source files directly using `Read` and `Grep`.
2. Scan for callout signals and API entries inline (Step 1).
3. Make the spine decision (Step 2) by examining the public API surface as a whole.
4. Build per-method callout candidates (Step 3).
5. Build the conceptual API inventory (Step 4).
6. **Cluster the inventory (Step 4b)**: group entries into mental-model clusters with use-named cluster headings. Identify running-domain seeds. This step prevents catalog-shape READMEs downstream; skipping it is the dominant failure mode for module READMEs that pass all coverage checks but read as enumerations rather than teaching.
7. **Run the mechanical enumeration pass (Step 5)** and add any orphans to the inventory before clustering them. This step is mandatory; skipping it is the dominant failure mode for modules with orthogonal APIs (kyo-schema, kyo-data, kyo-prelude).
8. **Run the cross-API interaction scan (Step 6)** and add any seam callouts to the callout list.
9. Emit the five structured sections per the output contract.

For modules with many source files (kyo-http has ~25 public files), use Glob and Grep to find scaladoc signal patterns efficiently before opening individual files. You can fit the relevant portions of source for a typical kyo module in a single Opus context.

The pivotal call is the spine decision. Reserve careful judgment for it after the source scan completes. Do not commit to a tag until you have seen enough of the public API to know what the user's first call looks like.

The two completeness checks (Steps 5 and 6) are non-negotiable. The downstream draft skill cannot teach a surface that isn't in the inventory or warn about a footgun that isn't in the callouts. Every drop here propagates to the finished README.
