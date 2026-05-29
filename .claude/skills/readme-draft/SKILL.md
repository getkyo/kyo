---
name: readme-draft
description: Draft a README from a source-analysis output (spine + callouts + API inventory). For new READMEs or full rewrites. Length scales with API surface; never invents content not in the inventory.
argument-hint: <source-analysis-path> <output-readme-path>
---

# readme-draft

Input: the structured output of `readme-analyze-source` (five sections: spine candidate, callout candidates, API surface inventory, section clusters, running-domain seeds). Output: a draft README.

This skill writes from a curriculum: every API listed in the inventory must be introduced, in order, with one or more worked examples. The spine candidate becomes the opening paragraphs. Callout candidates become inline `Note:`, `Caution:`, or `Unlike X` paragraphs at the points where the surface they describe is introduced. **The section clusters become the `##` section list** — one section per cluster, NOT one per API. **The running-domain seeds determine the case classes used in worked examples** — pick one early and reuse throughout.

This skill does NOT:
- Read or compare against an existing README at the output path. If a README exists there, the user wants a full rewrite; you write fresh.
- Add content beyond what the inventory and callouts specify.
- Invent capabilities or surfaces.
- Add hedging, defensive disclaimers, audience disclaimers, or competitor-suggestion paragraphs.
- Produce one `##` section per inventory API (catalog shape). Sections come from the cluster list, not the inventory list.

## What you produce

A single Markdown file at `<output-readme-path>`, structured as follows:

1. Title (`# kyo-<name>`) — exactly the module name, no marketing.
2. Spine paragraphs — verbatim from the spine candidate, or lightly edited for prose flow. NEVER trade specificity for fluency.
3. **Composition opening**: ONE code block exercising 3-5 capabilities on the chosen running-domain value, with `// comment` headers. See "Structural discipline" below.
4. `##` sections IN CLUSTER ORDER (one per cluster from the source-analysis output), each with its APIs as `###` sub-sections. APIs from the same cluster MUST appear together under their cluster heading.
5. Optional sections that the inventory specifically supports (cross-platform behavior, integration points, configuration, exceptions).

No "Why?" section, no "Comparison to X", no "Frequently asked questions", no "Roadmap", no "Audience: built for X". These are anti-patterns.

## Length budget

Length scales with API surface. Reference points:

- 5-10 inventory entries: ~200-400 lines.
- 10-20 entries: ~400-700 lines.
- 20-40 entries: ~700-1000 lines.
- 40+ entries with cross-cutting platform/integration surfaces: ~1000-1500 lines.

**The main-branch `kyo-http/README.md` is 912 lines for ~25 public types and ~50 methods, with cross-platform notes, integration examples, and worked TLS, HTTP/2, SSE, NDJSON, and middleware examples.** Calibrate against this when drafting. Shorter is fine when the surface is smaller. Cutting to hit a fixed target % is reward hacking.

Do NOT impose a percentage cut. The spine + curriculum + callouts is the budget; nothing more, nothing less.

## Section structure: curriculum order, not API reference order

A README is curriculum, not Scaladoc. Order sections by what a reader needs to know first:

1. **Opening (spine)**: 1-2 paragraphs of mental model. No code yet. The reader knows what this module IS before seeing any API.
2. **Composition opening (see Structural discipline below)**: ONE multi-capability code block on a running value. Required unless the module is genuinely flat (1-3 APIs total).
3. **First useful call**: the smallest worked example that exercises the typical primary use case. For a use-case-first spine, this is the convenience API (`HttpClient.getText`). For a type-first spine, this is the central-type construction.
4. **Sections by cluster**: walk the `## Section clusters` from the source-analysis output IN ORDER. One `##` section per cluster. Cluster name becomes the heading; APIs in the cluster become `###` sub-sections inside it. **Do not produce one section per API.**
5. **Advanced surfaces**: configuration, lifecycle, platform-specific behavior, integration points. Each gets a `##` heading; sub-surfaces get `###`.
6. **Callouts** are inline at the point of introduction, not collected at the end. A `Note:` paragraph follows the API it qualifies.

The opening's composition example IS the code-first hook; that's the section-2 slot above.

## Structural discipline (cluster, compose, run the domain)

A README that introduces APIs one section at a time, each with its own ad-hoc case class, reads as a CATALOG no matter how complete it is. Catalog shape is the dominant remaining failure mode for kyo READMEs because the verify gates (coverage, forward-reference, skim-reader) all pass on a catalog. The fix is upstream: shape the draft to TEACH, not enumerate.

Five rules, all required unless the module is genuinely flat (1-3 APIs total). For modules with 5+ APIs in their inventory, all five are mandatory.

### 1. Opening hook (≤15 lines, or no block)

After the spine prose and before any `##` section, write ONE small code block (**≤15 lines total, including imports, declarations, and blank lines**) that demonstrates ONE compelling thing the reader can immediately copy and run. If you cannot show something compelling in 15 lines, **do not write an opening code block at all** — the spine prose alone is the hook.

Hard rules:
- Hard cap: 15 lines per the line count of the fenced block (between the opening and closing triple-backticks).
- No multi-capability "tour of the module" in the opening. One verb, one running value, one result.
- No `// section heading` comments dividing sub-blocks; if you find yourself writing those, the block is too big.
- The block stands on its own: a reader who only reads it should understand what the module does, not "here is every cluster I'll explain later."

Reference shapes (good openers):

```scala
import kyo.*
val log: Log = JavaLog("kyo.app")
Log.let(log)(Log.info("hello"))
```

```scala
import kyo.*
val tens: Chunk[Int] < Any = Kyo.fill(3)(10)
assert(tens.eval == Chunk(10, 10, 10))
```

**Where the comprehensive multi-capability example goes**: NOT at the top. Move it to a dedicated `## Putting it together` (or similarly-named) section near the end of the README, after the per-cluster sections have introduced each piece. A reader who has read the chapters arrives at the integrated example primed to understand it; a reader who skims the headings still sees the full composition shape exists in the doc.

The point of these rules: readers bounce off walls of code at the top. A 15-line hook gets them committed; a 40-line tour gets them gone before the first `##`.

### 2. Running domain

Pick one of the running-domain seeds from the source-analysis output. Use it (and any helper case classes it brings) throughout the README. Every section's worked examples should operate on instances of the running domain unless the section's content fundamentally requires a different shape (e.g. a sealed-trait example for sum-type navigation).

Concrete rule: count the number of distinct case-class declarations in the finished draft. Excluding sealed-trait variants and platform-specific examples, this number should be at most 3 for a typical module. A draft with 8+ distinct case classes is a signal that every section invented its own example.

**Doctest self-containment without visible noise.** Doctest compiles each fenced ```scala``` block in isolation, but you do NOT need to repeat the running-domain case classes in every visible block. Use a `doctest:setup` block wrapped in an HTML comment at the top of the README:

```markdown
<!-- doctest:setup
\`\`\`scala
import kyo.*
case class Order(id: Long, customerId: Long, items: Chunk[Item], total: BigDecimal)
case class Item(sku: String, qty: Int, price: BigDecimal)
val orderId: Long = 42L
\`\`\`
-->
```

The HTML-comment-wrapped block is compiled by doctest (the prelude is injected into every subsequent block) but invisible to readers in rendered markdown. Subsequent visible blocks consume the prelude without repeating the running-domain declarations.

Use this for: running-domain case classes, `import kyo.*`, `AllowUnsafe.embrace.danger` imports, and any other fixture state that has zero teaching value. Only the per-block code that genuinely teaches stays visible.

Carriers boil down to two states for the author: visible (plain ```scala```) or hidden (`<!-- ... -->`). `<details>` is standard markdown for collapsed-by-default visible blocks; doctest treats it identically to plain `Bare`. Use HTML comments for true noise.

### 3. Section names: use, not API

Section `##` headings name what the reader is trying to do or the conceptual group, not the API names.

| Bad (catalog) | Good (teaching) |
|---|---|
| `## Modify: batched mutation` | `## Comparison and Mutation` (with `### Modify` sub-section) |
| `## Compare: read-only diff` | (same: `### Compare` inside Comparison and Mutation) |
| `## Changeset: serializable diff` | (same: `### Changeset` inside Comparison and Mutation) |
| `## Builder: compile-time-checked construction` | `## Construction` (with `### Builder`) |
| `## Json` + `## Protobuf` | `## Serialization` (with `### JSON` and `### Protocol Buffers`) |
| `## HttpRoute: typed contracts` | `## Routes and handlers` (with `### Defining a route` `### Attaching a handler`) |

If an API genuinely has no cluster siblings, it can be its own section. Name the section by use anyway: "Custom codecs" not "Codec extension API."

### 4. Section openers: WHY, not WHAT

The first sentence of every `##` and `###` section should answer "when does a reader reach for this?" or "how does this relate to what came before?" — never "<API> provides <feature>."

| Bad (catalog) | Good (teaching) |
|---|---|
| "`Modify` provides batched field mutations." | "When you want to describe several changes as a single value you can build up, store, and apply later, use `Modify`." |
| "`Changeset` is a serializable diff." | "`Compare` and `Modify` both need both values in memory. When you want to compute the diff on one machine and apply it on another, use `Changeset`." |
| "`HttpFilter` is composable middleware." | "Cross-cutting concerns (auth, rate limiting, CORS, logging) attach to routes as filters. Filters compose, and their type parameters track which request and response fields they require and produce." |

Detectable failure pattern: section openers that start with a backticked API name as the subject of the sentence (`` `X` `` does, `` `Y` `` is, `` `Z` `` lets you). Rewrite to lead with the reader's situation or the relationship to prior content.

### 5. Cross-API teaching paragraphs

When two APIs in a cluster (or two APIs flagged by the source-analysis Step 6 cross-API scan) have a "when to use which" relationship, write an explicit paragraph stating the distinction. Inside the cluster, between the two `###` sub-sections, or immediately after the second one.

Examples of cross-API teaching paragraphs that belong in every README that introduces both APIs:
- `Focus.set/update` vs `Modify`: single-shot vs batched.
- `Compare` vs `Changeset`: in-memory query vs serializable replay.
- `withConfig(value)` vs `withConfig(f)`: replace vs stack.
- `Json.encode(value)` vs `s.encode[Json](value)`: ambient schema vs transformed schema (this is the implicit-summoning gotcha).
- `HttpClient.getJson` (body-only) vs `HttpClient.getJsonResponse`: auto-fail vs full response.

A cross-API paragraph reads: "When you need <A's situation>, use <A>. When you need <B's situation>, use <B>." Or: "Both X and Y do <thing>. The difference is <distinction>."

Detectable failure pattern: two related APIs appear in the same cluster but neither section mentions the other. Reader has to assemble the comparison themselves.

## Worked-example discipline

Every code block follows these rules:

- **Real, compilable Scala code.** Imports at top of the first example in each section; subsequent examples may omit imports.
- **Self-contained.** A reader copying the block into a Scala 3 REPL or `*.sc` file should see it run (or compile cleanly, for examples that depend on external services).
- **Names match scaladoc and source.** If the source method is `HttpClient.getJson[A: Json]`, the example calls `HttpClient.getJson[User](url)`, not `HttpClient.fetchJson` or `httpClient.getJson`.
- **Only use APIs that are in the inventory.** If you want to illustrate something the inventory doesn't list, that's a signal the inventory has a gap (escalate by including the name in your draft and flagging it). Never invent an example around a method you haven't verified exists.
- **No dead code.** Every line is used by a subsequent line, OR is a `// ...` comment marking elision.
- **No identity-or-no-op illustrations.** Never write `Client.update(c => c).apply { ... // identity, just to show the let pattern }` or analogous demo-by-no-op. If the example value adds nothing to the demonstration, find a realistic one or cut the example.
- **Type annotations only where they teach.** If the return type is interesting (`HttpResponse[Json[User]] < (Async & Abort[Throwable])`), show it. If it's noise (`val s: String = "..."`), omit it.
- **Effect rows match reality.** Do not hide `< Sync` or `< Async` in examples just to make them shorter. The reader needs the effect-row truth.

### Teaching-pattern checklist

These are reader-experience patterns the inventory cannot enumerate but every high-quality kyo README applies. Apply each at least once where the curriculum supports it:

1. **Primer before novel syntax.** Before the first use of any syntax the reader is unlikely to know (`"name" ~ Type`, `&`-typed records, `derives Schema`, `Capture[A]("name")`, `_.field.Variant.subfield` Focus lambdas, opaque-type smart constructors), spend 5-15 lines explaining what the syntax says and what it produces. Do NOT use the syntax in a route or call before the primer paragraph.
2. **One fully-inferred type display.** For type-parameterized central types (`HttpRoute[In, Out, E]`, `Focus[Root, Value, Mode]`, `HttpFilter[ReqUse, ReqAdd, ResUse, ResAdd, +E]`), show ONE worked example where the full inferred type appears after a chained build. The reader needs to see how the type tracks the chain. One example is enough; don't annotate every chain.
3. **Happy-path + decode-failure pair.** For any decode / parse / validate surface, show the success case AND one realistic failure case (the actual `Result.Failure(...)` or `Chunk[ValidationFailedException](...)` value). Failure cases teach error shapes the reader will see in production.
4. **Cross-API interaction callouts inline.** Seam-footgun callouts from the source-analysis output (entries with two anchors) belong inline at the FIRST surface mentioned, not collected at the end. Pin them at the seam, not at the end of the module.
5. **Realistic per-platform setup.** When cross-platform is a feature, include the small practical setup notes the reader needs: "JS requires a Node runtime" / "Native requires OpenSSL when TLS is used" / "JS regex coverage is reduced for X, Y, Z." A one-line setup note is worth fifty lines of platform-agnostic prose.
6. **Curriculum continuity over fluency.** A section that reads beautifully but introduces three new concepts at once should be split. Each `##` section introduces at most one new mental-model concept and explains it before using it elsewhere.

## Callout discipline

Each callout candidate becomes one of these inline forms:

- `> **Note:** <one-sentence behavior callout>` for behavior that differs from a reader's default expectation but is intentional.
- `> **Caution:** <one-sentence warning>` for surfaces that can cause incorrect behavior (resource leaks, double-execution, security pitfalls).
- `> **Unlike** <X>, <this surface> <does Y instead>.` for surfaces whose API shape suggests one behavior but actually has another.

Place each callout immediately after the worked example that introduces the qualifying surface. Never collect callouts in a "Gotchas" section at the end.

## Banned phrases and patterns

Do NOT write any of the following:

- **Audience disclaimers** ("Built for library authors", "If you're new to Scala...", "This module assumes familiarity with X"). The reader is here because they need the module. Disclaimers waste their attention.
- **Competitor name-drops** as orientation ("Like Akka HTTP, but...", "Similar to fs2 Stream..."). The spine establishes orientation; competitor comparisons add noise.
- **Pre-emptive justification** ("You might wonder why...", "It's not obvious, but..."). Just state the behavior.
- **Hedging** ("might", "should typically", "in most cases", "usually"). Use precise language. If a behavior is conditional, name the condition.
- **"This README will cover..." / table-of-contents prose.** The headings ARE the table of contents.
- **Marketing copy** ("fast", "powerful", "elegant", "idiomatic", "zero-cost", "blazing"). Show the behavior; let the reader form the judgment.
- **Em-dashes (`—`).** Use commas, parentheses, colons, or sentence breaks. Never substitute `;` for `—` mechanically; rewrite the sentence.

## Cross-platform claims

If the inventory marks the module as cross-platform (JVM/JS/Native), the README must:

1. State the platform support in the spine or in a short paragraph immediately after.
2. Include a `## Platform-specific behavior` section (or equivalent) ONLY when a surface behaves differently per platform AND the inventory lists it.
3. NEVER fabricate platform behavior. If the inventory says "all three platforms, single API," the README does not invent a platform-specific section.

## Working with existing examples in source / test files

If the inventory's API entries reference source files with Scaladoc-grade example code (`/** Example: ... */`), prefer those examples verbatim or near-verbatim. Source-anchored examples are guaranteed to compile.

If a test file's name matches a curriculum section ("HttpClientSpec.scala", "JsonRoundTripSpec.scala"), scan the spec for a clear "happy-path" test; its setup is often a clean example.

Do NOT copy whole test cases (they include assertions, fixtures, and other noise). Extract the call shape.

## Output contract

Write the draft to `<output-readme-path>` using `Write`. The output is a complete, ready-to-edit Markdown file. After writing, run:

```
bash .claude/skills/readme/readme-check.sh <output-readme-path>
```

Report any STRONG findings. The supervisor's verify stage will catch the rest.

## Execution

This sub-skill runs as a single Opus agent dispatched by the supervisor. You do NOT dispatch further sub-agents. All drafting happens in your own context:

1. Read the source-analysis output (spine, callouts, inventory).
2. Read each source file the inventory references for example extraction (use Read on the specific source files as needed).
3. Draft the README top to bottom in your scratchpad.
4. Write the final draft via `Write` to `<output-readme-path>`.
5. Run the script check; report STRONG findings.

The draft is one continuous artifact; do not split it into multiple files or sections written by parallel agents. The curriculum continuity (each section building on the prior one's introductions) requires single-author voice.
