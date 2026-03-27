---
name: Always read Scala first
description: EVERY time writing TS facade code, FIRST read the equivalent Scala source. This is transpilation, not invention.
type: feedback
---

EVERY time writing TS code for the facade (Scala bindings, TypeScript types, or tests), FIRST read the equivalent Scala source code.

**Why:** The user has repeatedly corrected me for inventing names (`Async.runFiber` instead of `Fiber.initUnscoped`), inventing APIs (`runAsync`, `runCore`), and writing code that doesn't match Scala's semantics. The facade is a TRANSPILATION of the Scala API, not an invention.

**How to apply:**
1. Before writing ANY facade method: Read the Scala source for that method
2. Before writing ANY test: Read the Scala test source
3. Before writing ANY type declaration: Read the Scala type definition
4. Use the EXACT same names as Scala (e.g., `Fiber.initUnscoped`, not `Async.runFiber`)
5. Use the EXACT same semantics (e.g., `v.handle(Scope.run, ...)` not manual chaining)
6. The test runner must match Scala's test base class exactly
7. If something doesn't work in TS, fix the facade — don't invent a workaround
