package kyo.internal.tasty.scala2

import kyo.*

/** Result produced by Scala2PickleReader for a single Scala 2 pickle.
  *
  * @param classSymbol
  *   The primary class/object/trait symbol decoded from the pickle. May be Absent for packages or malformed inputs.
  * @param symbols
  *   All Tasty.Symbol instances decoded from the pickle table, including nested members.
  * @param parents
  *   Unresolved parent types for the primary class symbol.
  */
final case class Scala2PickleResult(
    classSymbol: Maybe[Tasty.Symbol],
    symbols: Chunk[Tasty.Symbol],
    parents: Chunk[Tasty.Type]
)
