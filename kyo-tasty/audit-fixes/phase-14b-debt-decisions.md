# Phase 14b-debt: ParameterizedTypeNotAllowed -- Decision

## Decision: DELETE

`TastyError.ParameterizedTypeNotAllowed(tag: String)` has been removed from the ADT.
Test 5 ("ParameterizedTypeNotAllowed carries tag field") has been removed from TastyErrorTest.

## Rationale

After full analysis of TypeUnpickler.decodeTag and the TASTy grammar:

1. `APPLIEDtype` (tag 161) is grammatically valid in every type position the decoder handles.
   There is no position in the existing decoder where an `APPLIEDtype` tag constitutes a
   structurally illegal occurrence that the decoder should reject rather than decode normally.

2. The one candidate position was `THIS` (category 3: tag + typeref_Type), where the TASTy
   spec requires a nominal typeref, not a parameterized applied type. However, the existing
   decoder handles any non-Named result from that inner type node by silently returning
   `ThisType(makeUnresolvedSym("this-unknown", ctx.home))`. This is intentional defensive
   decoding: real Dotty-generated TASTy never puts APPLIEDtype inside THIS, and the decoder
   must not crash on malformed files.

3. Wiring `ParameterizedTypeNotAllowed` at the `THIS` fallback would require propagating a
   specific `TastyError` value through the synchronous throw-and-catch infrastructure.
   The only mechanism would be a wrapper exception -- but all enclosing catch clauses
   (in `readType`, `AstUnpickler.readPass1`, `decodeOneTypeIfPresent`,
   `decodeTemplateParents`) catch `Exception` and re-raise as `MalformedSection`.
   Extracting the wrapped error without a cast is architecturally awkward and adds
   complexity with no consumer benefit.

4. The `ParameterizedTypeNotAllowed` name implies a term-level occurrence of a type-level
   construct. The actual decoder already handles all tag positions through the permissive
   skip-with-placeholder pattern for truly unknown tags.

5. Zero production call sites existed. The ADT case was introduced speculatively. The
   "no scope cuts, complete and correct" mandate requires either a reachable wire-up or
   an honest deletion with documented rationale. Deletion is the correct outcome.

## Files Modified

- `kyo-tasty/shared/src/main/scala/kyo/TastyError.scala`: removed `case ParameterizedTypeNotAllowed(tag: String)`
- `kyo-tasty/shared/src/test/scala/kyo/TastyErrorTest.scala`: removed Test 5

## Grep Confirmation

After deletion, zero references to `ParameterizedTypeNotAllowed` remain in
`kyo-tasty/shared/src`, `kyo-tasty/jvm/src`, `kyo-tasty/js/src`, `kyo-tasty/native/src`.
