# Phase 14b Decisions

## D1: SymbolNotFound test uses direct construction + empty-classpath lookup

**Test 4 design:** `TastyError.SymbolNotFound` has no call sites in the core
library source (only in `tasty/examples/RuntimeReflectionExample.scala`). There
is no internal codec path that fires it automatically. The test therefore:

1. Constructs `TastyError.SymbolNotFound("missing.X")` directly and asserts
   `.fqn == "missing.X"` to verify the ADT field is present and readable (T3).
2. Calls `Tasty.Classpath.unwrap(cp).lookupClass("missing.X")` on an empty
   classpath built via `Tasty.Classpath.fromPickles(Seq.empty)`, and asserts
   the result is `Result.Success(Maybe.Absent)`. This verifies the internal
   lookup contract: a missing symbol is a soft-fail (Absent), not a hard-fail
   (SymbolNotFound). The `SymbolNotFound` error is used by higher-level
   user-facing wrappers that convert Absent to a hard error.

**Fallback used:** Yes, direct construction for the ADT field check.

## D2: ParameterizedTypeNotAllowed test uses direct construction

**Test 5 design:** `TastyError.ParameterizedTypeNotAllowed` has no call sites
in the core library source. Constructing a minimal TASTy payload that fires
this error through the decode pipeline would require reverse-engineering the
type section binary format, which is disproportionately complex for a T3 ADT
field coverage test. The test constructs the value directly:

    val err = TastyError.ParameterizedTypeNotAllowed("APPLIEDtype")

and asserts `err.tag == "APPLIEDtype"`.

**Fallback used:** Yes, direct construction.
