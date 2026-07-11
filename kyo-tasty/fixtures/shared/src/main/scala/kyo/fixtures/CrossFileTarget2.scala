package kyo.fixtures

/** A second, unrelated target carrying its own `value` member with the same simple name as
  * `CrossFileTarget.value`, so `references` can be tested for SymbolId-equality matching (no false
  * positives across same-named symbols in different files).
  */
class CrossFileTarget2:
    val value: Int = 2
end CrossFileTarget2
