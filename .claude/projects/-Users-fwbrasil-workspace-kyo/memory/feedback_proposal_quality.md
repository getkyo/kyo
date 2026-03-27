---
name: proposal_quality
description: How to write high-quality proposals — simplicity, safety, no assumptions, assist don't lead
type: feedback
---

When writing design proposals:
- **Simplicity to the extreme** — every type, every phase, every indirection must justify itself. If it can be simpler, it should be.
- **Safety first** — isolate mutability to the absolute minimum (e.g., only screen buffers). Prefer immutable data, pure functions, kyo effects.
- **Don't assume priorities** — don't decide what's "for later" vs "now". Present the complete design.
- **Don't assume design decisions** — explore alternatives, present tradeoffs, let the user decide.
- **Assist, don't lead** — the user drives the design. Present options and analysis, not conclusions.
- **Validate before proposing** — trace through scenarios, check edge cases, verify consistency BEFORE writing. No "but wait" moments.
- **Code over prose** — favor concrete code snippets. Explain with good text flow around them.
- **Why:** The user has deep experience iterating on kyo-ui (4+ TUI iterations). They know the pitfalls. Proposals that assume or over-engineer waste time.

**How to apply:** Before writing any proposal, first: (1) trace through all scenarios, (2) identify where mutability is needed vs avoidable, (3) check every type/function for whether it can be eliminated, (4) verify the design handles ALL listed requirements.
