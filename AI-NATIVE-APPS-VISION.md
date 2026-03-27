# Kyo: AI-Native App Platform

## Vision

Kyo becomes a complete platform for AI-native applications. An AI agent takes a natural language description, generates a kyo-ui + kyo-ai application in Scala 3, and deploys it to any environment — web browser, iPhone, Android, desktop, terminal, server. The user owns everything: their data (local storage), their AI credentials (own API keys or on-device models), their app definitions (shareable, forkable, evolvable). No servers. No subscriptions. No gatekeepers.

## The Stack

Kyo's effect system is the unifying layer. AI, UI, HTTP, persistence, and concurrency are all effects that compose in a single computation.

```
kyo-core     Effects, Async, Signal, Fiber, Scope, STM, Cache, Gate, Channel
kyo-data     Dict, Record, Chunk, Maybe, Duration, Tag
kyo-http     HTTP client + server, all platforms (JVM Netty, JS Fetch, Native h2o)
kyo-ui       UI framework — sealed AST, Style props, Signal reactivity, multiple backends
kyo-ai       AI as effect — AI.gen, Agent, Tool, Prompt, Thought, Mode, Mind (evolution)
kyo-flow     Durable workflows — typed dispatch, saga (recover/revert), parallel fan-out, human-in-the-loop
kyo-stm      Software transactional memory (JVM, JS, Native)
kyo-stats    Metrics and histograms (all platforms)
kyo-oltp     OpenTelemetry integration
```

Cross-platform via Scala 3's `crossProject(JVM, JS, Native)`. One source, three targets.

## What Exists Today

### Core Infrastructure (merged to main)
- Full effect system with Async, STM, Cache, Gate, Channel, Signal
- kyo-http with native support on all platforms (JVM/JS/Native)
- Reactive Streams with JS and Native support
- Native histograms, scheduler fixes, native ZIO modules
- Dict, Record (new opaque type encoding)
- Cache (kyo-native, replacing Caffeine dependency)

### kyo-ui (kyo-ui-web branch)
- **Shared**: Sealed UI AST (~50 element types), Style property ADT (flexbox, colors, borders, typography, effects), Signal-based reactivity, Theme support
- **JS Backend**: DomBackend renders directly to browser DOM with full event handling, two-way data binding, dynamic style injection
- **Reactive Architecture**: ReactiveUI with path-based identity, targeted HTML fragment updates via SSE, Exchange trait for transport abstraction
- **TUI Backend**: Terminal rendering pipeline (kyo-ui branch)

### kyo-ai (separate repo: kyo-ai)
- AI effect (`opaque type AI <: Var[Context] & Async`)
- Agents (actor-based, persistent conversation state)
- Tools (type-safe, auto-schema from Scala types)
- Prompts (dual-component: primary + floating reminders)
- Thoughts (structured reasoning — opening/closing)
- Mode (middleware for intercepting/transforming AI.gen)
- Mind architecture (API sketch): Neuron/Synapse/Mind graph, cycle-based eval loop, genotype serialization, GA evolution with population/mutation/crossover/selection

### kyo-flow (active development, linked-forging-lollipop worktree)
- 252 tests passing, 7320 lines, 5 source files
- Type-safe `Flow[In, Out, S]` with compile-time checked field access
- AST-based: sealed data structure — foldable, visitable, renderable, persistable
- Durable execution: survives process restarts, resumes from persisted state
- Saga pattern: `onRecover` (error handling) + `onRevert` (compensation in reverse order)
- Parallel: `foreach` (configurable concurrency), `gather` (N-way), `race` (first-to-complete)
- Human-in-the-loop: `input` nodes suspend workflow until signal arrives
- Type-safe dispatch: `dispatch[V]("route").when(cond)(handler).orElse(fallback)`
- Sub-workflows: `invoke` with typed input/output propagation
- Per-node timeout and retry with schedules
- HTTP API: REST + SSE for starting, signaling, monitoring workflows
- Visual rendering: Mermaid, DOT, ELK, JSON, BPMN

### Active Work
- **kyo-ts**: TypeScript facade for Kyo via Scala.js — TS types expressing effect tracking
- **kyo-dstm**: Durable STM exploration
- **OTLP module**: OpenTelemetry integration (kyo-native)
- **kyo-caliban**: Migration from kyo-sttp/kyo-tapir to kyo-http
- **kyo-native-queues**: Cache-line padded unsafe queues for Native platform

## The Path

### Phase 1: Web-First (builds on current work)

The web deployment path is fully unblocked and has zero gatekeeping.

**kyo-ui web backend** (in progress on kyo-ui-web branch):
- Complete the SSE reactive push architecture (ReactiveUI → Exchange → browser)
- Client is minimal JS: event delegation + SSE listener + DOM patching
- Server renders HTML, pushes targeted fragment updates

**Web app capabilities** (no server needed for client-only apps):
- Local storage via IndexedDB / Origin Private File System (gigabytes of structured data)
- Local AI models via WebGPU + WASM (small quantized models: Phi, Gemma 2B, Llama 3.2 1B)
- Offline via Service Workers
- Push notifications (Android + iOS Safari 16.4+)
- Installable as PWA (app icon, full screen, no browser chrome)
- Camera, mic, GPS via standard Web APIs

**AI integration**:
- kyo-ai compiled to Scala.js, running in browser
- User enters their AI provider API key (stored in browser secure storage)
- App calls provider APIs directly via kyo-http (Fetch backend)
- Alternatively: on-device inference via WebGPU for quick/private tasks

**Distribution**: Share by URL. No app store. No review. No developer account.

### Phase 2: Native Mobile (iOS + Android)

For apps that need native look-and-feel, deeper platform integration, or full hardware access.

**Architecture: native views + Yoga for layout**

The approach uses real platform UI widgets — not custom rendering (no Skia). This gives actual native feel with native text editing, scrolling, accessibility, and platform conventions.

**iOS (Scala Native → ObjC runtime FFI → UIKit)**:
- Thin ObjC runtime wrapper (~300 lines): `objc_msgSend`, `objc_getClass`, `sel_registerName`
- Element mapping: `UI.Div` → `UIView`, `UI.Button` → `UIButton`, `UI.Input` → `UITextField`, etc.
- Layout: Yoga (C library, MIT, used by React Native) — Style's flexbox maps 1:1 to Yoga API
- Style visual props → UIKit properties (`setBackgroundColor`, `layer.cornerRadius`, etc.)
- Signals → native view property updates via kyo scheduler integrated with `CFRunLoop`

**Android (Scala JVM → Android SDK)**:
- Standard JVM interop — Android runs JVM bytecode
- Element mapping: `UI.Div` → `FrameLayout`, `UI.Button` → `android.widget.Button`, `UI.Input` → `EditText`, etc.
- Layout: Yoga (yoga-android or direct JNI)
- Style visual props → Android View properties
- Signals → native view property updates via kyo scheduler integrated with `Looper`/`Handler`

**Why native views over custom rendering (Skia)**:
- Text editing, scrolling, accessibility come free from the platform
- No Skia build complexity or binary size (~15MB per arch)
- No GC-in-render-loop risk — native views own their render pipeline
- Actual native look-and-feel, not Flutter-like custom widgets
- kyo's non-blocking Async means no thread contention with platform UI thread

**The only cross-platform dependency is Yoga** (~500KB, stable C API, MIT).

**Mobile-specific features**:
- Background refresh: `BGTaskScheduler` (iOS) / `WorkManager` (Android) — app wakes periodically, scheduler resumes, fetch data from APIs, update cached state, scheduler pauses
- Local notifications: schedule precise alerts without a server
- Secure storage: iOS Keychain / Android EncryptedSharedPreferences for API keys
- On-device AI: Core ML (iOS, accesses Neural Engine) / NNAPI (Android) for local inference
- App lifecycle: scheduler pause/resume on background/foreground transitions — zero CPU when not visible

**Distribution**:
- Scoped player apps in App Store (category-specific: dashboards, trackers, learning tools)
- Direct APK on Android (sideloading always available)
- TestFlight for personal/small-audience apps
- Or: AI generates and submits individual apps through standard store review

### Phase 3: AI-Developed Apps

The AI (Claude, etc.) writes the apps. This is the key differentiator.

**The generation loop**:
1. User describes what they want in natural language
2. AI generates kyo-ui + kyo-ai Scala code
3. Code compiles to JS (web) / JVM (Android) / Native (iOS)
4. App runs — native UI, AI effects, local persistence, API access

**Why kyo makes this tractable for AI**:
- UI is a sealed AST — finite, well-typed, pattern-matchable. No ambiguity.
- Style is a property ADT — every visual property is a method call. No CSS string manipulation.
- AI is an effect — `AI.gen[A]` is just a type in the signature. Composable with everything else.
- Signals handle reactivity — AI doesn't need to reason about DOM diffing or update cycles.
- Effects compose — `AI & UI & HTTP & Scope` in one type, compiler enforces correctness.

**Apps that couldn't exist before**:
- Too niche for a startup (sourdough tracker, niche hobbyist inventory)
- Too personal for SaaS (health journal, personal CRM, financial analyzer)
- Too privacy-sensitive for cloud (medical records, therapy notes)
- Too domain-specific for general tools (jurisdiction-specific legal assistant)

The economics flip: instead of 1000 users × $10/month for a generic product, one user × a few cents of AI generation cost for a bespoke app.

### Phase 4: Evolving Apps (Mind Architecture)

Apps don't stay static. The Mind architecture from kyo-ai enables self-improving applications.

**How it works**:
- App behavior is encoded as a Mind genotype (neurons, synapses, prompts, weights)
- Genotype is serializable data, not compiled code
- Steady-state evolution: branch current genotype → try variant → measure → merge or discard
- The app gets better at helping THIS user over time

**Sharing and community**:
- Export a tuned genotype → share via link/file
- Someone else loads it → gets a pre-tuned AI assistant
- Fork and customize — change prompts, add neurons, adjust topology
- Community galleries of genotypes per app category

## The Ultimate Automation Platform

This is what Zapier would be if it had no ceiling.

Zapier is constrained by its connector catalog, its trigger/action model, and its pre-built data transformations. You can only do what someone anticipated. Code has no such constraint — if an API exists, you call it; if a transformation is possible, you express it; if a decision requires AI reasoning, you embed it.

The gap has always been that code requires a developer. AI closes that gap.

### kyo-flow: Durable Workflows as the Backbone

kyo-flow (252 tests, 7320 lines, in active development) provides the execution engine for complex automations. It's a type-safe, durable workflow framework with:

- **Type-safe composition**: `Flow[In, Out, S]` — inputs, outputs, and effects tracked at compile time. AI-generated workflows are compiler-checked.
- **Durable execution**: Workflows survive process restarts. On mobile, this means surviving background kills — the OS can terminate the app, and when it relaunches, the workflow resumes exactly where it left off.
- **Saga pattern**: `onRecover` (catch errors, substitute values) + `onRevert` (compensate completed steps in reverse). Critical for multi-step operations: book hotel → book flight → flight fails → automatically cancel hotel.
- **Parallel fan-out**: `foreach` with configurable concurrency, `gather` for N-way parallel branches, `race` for first-to-complete.
- **Human-in-the-loop**: `input` nodes pause the workflow and wait for a signal — user approval, external event, or scheduled trigger. The workflow is suspended, not polling.
- **Dispatch**: Type-safe branching — `dispatch[String]("route").when(condition)(handler).orElse(fallback)`.
- **Sub-workflows**: `invoke` calls child flows with typed input/output propagation.
- **Timeout and retry**: Per-node timeout and retry schedules. Retry with backoff, timeout cancels the attempt.
- **HTTP API**: REST endpoints for starting workflows, signaling inputs, checking status, streaming events via SSE.
- **Visual rendering**: Flows render to Mermaid, DOT, ELK, JSON, and BPMN — the workflow structure is inspectable and displayable in kyo-ui.

A kyo-flow workflow is an AST — a sealed data structure that can be folded, visited, rendered, and persisted. This makes it both AI-generable and AI-inspectable.

### Simple automations: just effects

Not everything needs durable workflows. Simple automations are just kyo effects:

```scala
// "Every morning, check my portfolio, get AI analysis, notify me if action needed"
Schedule.repeat(6.hours) {
    for
        holdings  <- HttpClient.get[Portfolio](config.brokerApi)
        news      <- HttpClient.get[News](config.newsApi, holdings.tickers)
        analysis  <- AI.gen[Analysis](holdings, news)
        _         <- if analysis.actionNeeded then
                         LocalNotification.send(analysis.summary)
                     else ()
    yield ()
}
```

### Complex automations: kyo-flow

When you need durability, human-in-the-loop, compensation, or multi-step orchestration:

```scala
val expenseWorkflow = Flow
    .input[Receipt]("receipt")
    .output("extracted")(ctx =>
        AI.gen[ExpenseData](ctx.receipt))                      // AI extracts fields
    .output("categorized")(ctx =>
        AI.gen[Category](ctx.extracted))                       // AI categorizes
    .dispatch[Action]("approval")
        .when(ctx => ctx.extracted.amount > 100)(ctx =>
            Action.RequireApproval)                             // needs human approval
        .orElse(ctx =>
            Action.AutoApprove)                                 // auto-approve small amounts
    .input[Boolean]("approved")                                // pauses, waits for user
    .output("posted")(ctx =>
        HttpClient.post(config.accountingApi, ctx.extracted))  // post to external API
    .onRecover("fallback")(ctx =>
        LocalStorage.queue(ctx.extracted))                     // if API down, queue locally
    .onRevert(ctx =>
        HttpClient.delete(config.accountingApi, ctx.posted))   // compensate if later step fails
```

This workflow is durable (survives app restart), type-safe (compiler checks every field access), has AI reasoning (extraction + categorization), pauses for human input (approval), handles errors (recover), compensates on failure (revert), and calls external APIs. It's generated by AI from a natural language description.

### What this can do that Zapier can't

- **AI reasoning at any step** — not just "summarize text" but genuine analysis with full context
- **Durable multi-step orchestration** — saga pattern with automatic compensation
- **Human-in-the-loop** — workflow pauses and waits for user input, not just triggers
- **Stateful** — remember what happened, track trends, build knowledge over time
- **Complex branching** based on content and AI reasoning, not just field values
- **Call any HTTP API**, not just ones with pre-built connectors
- **Run entirely on the user's device** — no third-party data exposure
- **Native UI** for configuration, monitoring, workflow visualization, and results
- **Evolving behavior** via Mind genotypes that improve over time
- **Type-safe** — compiler catches wiring errors in AI-generated workflows
- **Unlimited automations, unlimited complexity, no per-step pricing**

Zapier charges $20-600/month for limited automations with limited steps. This is unlimited everything, user pays only for their own AI API calls, running on hardware they already own.

### Example apps

- **Personal finance dashboard** — connects to bank APIs (user's credentials), AI categorizes transactions, identifies tax deductions, generates quarterly estimates, alerts on unusual patterns. Durable workflow tracks pending categorizations across sessions.
- **Research monitor** — watches arxiv/HN/specific APIs on topics the user defines, AI summarizes overnight via background refresh, presents a native morning briefing. kyo-flow manages the fetch → analyze → summarize → notify pipeline.
- **Personal CRM** — user describes people they meet, AI builds a knowledge graph, reminds before meetings with relevant context, drafts follow-ups. Durable workflows track follow-up sequences.
- **Smart home orchestrator** — queries device APIs, applies user-defined rules with AI interpretation, learns patterns. Race conditions handled by kyo-flow's race/timeout.
- **Health tracker** — correlates mood, symptoms, medications, sleep, weather across entries, AI identifies triggers the user wouldn't notice. Foreach processes historical entries in parallel.
- **Travel planner** — book hotel → book flight → book car, with automatic cancellation (revert) if any step fails. Human approval for expensive bookings. The saga pattern was invented for exactly this.

Each of these is an automation (scheduled API calls + data processing + AI reasoning) wrapped in a native UI (kyo-ui) with local persistence and durable execution (kyo-flow). The user describes it in natural language, AI generates it, it runs autonomously on their device.

## Mobile App Lifecycle

kyo's fully async, non-blocking architecture maps naturally to mobile platform constraints.

**Foreground/background transitions:**
```
App enters background → scheduler.pause()
  All fibers park. Zero CPU. Zero allocations. Zero GC pressure.
  Signals retain current values (stateful, not event-based).

App enters foreground → scheduler.resume()
  Fibers resume. Signals evaluate to current state.
  streamChanges skips intermediate values — only latest matters.
  One layout pass, caught up.
```

On iOS the OS eventually freezes the process entirely (suspended state). Explicit pausing before that lets the app release resources and reduce memory footprint — iOS kills background apps under memory pressure, highest memory first. On Android, a paused scheduler means near-zero CPU, so the OS has no reason to kill the process.

**Background execution (serverless):**
Both platforms allow periodic background work — the app can fetch data from APIs without a server:

- `BGTaskScheduler` (iOS): ~30 seconds of execution, OS decides timing. App wakes, scheduler resumes, fetches data via kyo-http, updates cached state, scheduler pauses.
- `WorkManager` (Android): ~10 minutes, minimum 15-minute interval. Same pattern.
- Both have full network access during their execution window.

This enables autonomous apps that monitor APIs, check conditions, and notify the user — all without a backend server. The user configures which endpoints to check and when. The app is a general-purpose client running on their device.

**Local notifications:** Schedule precise alerts without a server. OS handles the timer. Code runs only when user taps the notification, resuming the scheduler and navigating to the relevant screen.

**All within App Store rules.** Background refresh + network requests is the officially recommended pattern on both iOS and Android. Weather, news, finance, and fitness apps all work this way.

## AI Provider Integration

**Current state:** Users provide their own API keys. This is the standard model (Cursor, Continue, Bolt all do it). Keys stored in platform secure storage (iOS Keychain, Android EncryptedSharedPreferences, browser localStorage). App calls provider APIs directly. Billed to user's account. No middleman.

**On-device models:** For offline, privacy-sensitive, or latency-critical tasks:
- **Web**: WebGPU + WASM — small quantized models (Phi, Gemma 2B) run in-browser with GPU acceleration
- **iOS native**: Core ML accesses Apple's Neural Engine (16-core, ~35 TOPS on A18 Pro). Can run 3B+ parameter models at ~30 tokens/sec.
- **Android native**: NNAPI / MediaPipe for on-device inference

The `AI` effect doesn't care where completions come from:
```scala
AI.run(Config.Anthropic.sonnet(apiKey = secureStorage.get("key"))) { ... }  // cloud
AI.run(Config.Local.phi4mini) { ... }                                        // on-device
```

Same app code, same UI — different backend. User chooses: cloud for complex reasoning, local for quick/private tasks.

**Note on Apple's on-device models:** Apple Intelligence uses a ~3B base model + LoRA adapters for task-specific specialization, but does not expose this to third-party apps. Core ML lets apps run their own models on the Neural Engine. If Apple opens their foundation model to developers, kyo-ai could target it directly.

## App Store Distribution

Full morphable "player" apps are not allowed — Apple guards against meta-app-stores. Realistic distribution paths:

**Web (primary, zero friction):**
- PWA: share by URL, installable, offline-capable, push notifications
- No store, no review, no developer account, no restrictions on what the app does
- Covers the majority of use cases

**Native mobile (when needed):**
- **Scoped player apps**: Category-specific apps (dashboards, trackers, learning tools) with user customization within clear boundaries. Same model as Notion, Airtable, Shortcuts. App Store approved.
- **AI-generated individual apps**: AI writes code, compiles, submits to App Store. Each app is a proper reviewed listing. 1-3 day review cycle.
- **Android sideloading**: Always available. AI generates APK, user installs directly.
- **TestFlight**: Up to 10,000 users, lighter review. Good for personal/small-audience apps.

The web path is the universal default. Native adds platform depth for apps that justify store distribution.

## Funding and Sustainability

The project is fully open source. The innovation itself has value.

**AI provider alignment:** Every AI-native app built on kyo generates API calls to AI providers. An open source platform that makes it trivial to build AI-powered apps directly grows the market for Anthropic, OpenAI, Google, etc. This is the same dynamic as Vercel funding Next.js (drives hosting revenue) or Google funding Kubernetes (drives cloud adoption). The platform doesn't need to monetize — it's infrastructure that grows the ecosystem.

**With a working prototype**, the conversation shifts from "fund this idea" to "this exists, it works, watch this." A demo where someone describes an app in natural language and a native app appears on their phone is not a pitch deck — it's proof.

**Funding paths:**
- AI provider ecosystem investment (most aligned — kyo drives their API revenue)
- OSS-focused accelerators (YC has funded PostHog, Supabase, others with no initial revenue model)
- Grants: Sovereign Tech Fund (EU, up to ~$1M for critical OSS), NLnet, GitHub Sponsors Fund
- Corporate sponsorship from companies building on the platform

The goal is not to build products — it's to build the best open source infrastructure for AI-native applications and let the ecosystem grow around it.

## Key Design Decisions

**Web-first, native when needed.** The web path has zero friction (share a URL), covers most use cases, and is where kyo-ui already has momentum. Native mobile adds platform depth for apps that need it.

**Native views, not custom rendering.** For mobile, use real platform widgets (`UIButton`, `EditText`) via FFI, not Skia. Every hard problem (text editing, scrolling, accessibility) is solved by the platform. Yoga handles layout.

**User owns everything.** No server between user and their data. API keys in local secure storage. Local persistence. Optional local models. The app is autonomous.

**AI writes the apps.** The kyo DSLs (UI, Style, AI, HTTP) are designed to be generated. Sealed types, property ADTs, and effect composition make AI-generated code reliable and type-checked.

**kyo-ts extends reach.** The TypeScript facade (in progress) means the same effect system can be expressed in TS types, potentially enabling a TypeScript/React frontend that shares kyo's effect model.

## Non-Goals

- **Not a Flutter competitor.** Not building a custom rendering engine. Using platform-native views.
- **Not a low-code platform.** Apps are real Scala code, generated by AI, type-checked by the compiler.
- **Not SaaS.** No hosted backend, no subscription, no data collection. The platform is a library.

## Risks

| Risk | Severity | Mitigation |
|---|---|---|
| Scala Native on iOS packaging | Medium | Proven path: SN → static lib → Xcode project. Unconventional but viable. |
| ObjC FFI verbosity | Low | Mechanical work, AI generates wrappers. ~300 lines. |
| Android as separate build target | Medium | Dedicated Gradle module wrapping Scala JVM output. |
| App Store restrictions on "player" apps | Medium | Scope to specific categories. Web as universal fallback. |
| Yoga maintenance/API churn | Low | Pin version. Could write own flexbox engine (~800 lines) for full control. |
| kyo-ai not yet cross-platform | Medium | Core AI module needs Scala.js compilation for web path. |
