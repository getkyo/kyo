package kyo.internal

import kyo.*
import kyo.Test

/** Drift-guard for [[MacroUtils]] symbol-set consolidation (Phase 3).
  *
  * Two leaves:
  *
  *   1. Every parameterised `given Schema[F[_]]` / `given Schema[F[_, _]]` declared in the `kyo.Schema` companion that
  *      represents a container, optional, or map shape must have its tycon `F` registered in one of
  *      `MacroUtils.collectionSymbols`, `MacroUtils.optionalSymbols`, or `MacroUtils.mapSymbols`. Tuple and `Tag` givens
  *      are intentionally excluded — tuples live in `SerializationMacro.tupleSymbols`, `Tag` is special-cased in the
  *      gate. Monomorphic givens (e.g. `Schema[String]`, `Schema[Span[Byte]]`) are excluded because they are primitive
  *      categories, not container categories.
  *
  *   2. `SerializationMacro.containerSymbols` (post-Phase-3 consolidation) must equal
  *      `MacroUtils.collectionSymbols ++ MacroUtils.optionalSymbols`. Both sets are `private[internal]`; this test lives
  *      in the `kyo.internal` package so the helper macro can read them directly.
  */
class MacroUtilsDriftTest extends Test:

    "every container/optional/map given in Schema companion is in MacroUtils" in {
        val unmatched = MacroUtilsDriftMacro.containerGivensNotInMacroUtils
        assert(unmatched.isEmpty, s"MacroUtils missing tycons for: ${unmatched.mkString(", ")}")
    }

    "SerializationMacro.containerSymbols equals MacroUtils collection ++ optional" in {
        assert(MacroUtilsDriftMacro.containerSymbolsConsistent)
    }

end MacroUtilsDriftTest
