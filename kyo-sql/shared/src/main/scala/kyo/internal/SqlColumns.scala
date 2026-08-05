package kyo.internal

import kyo.*

/** Transport helpers over [[kyo.SqlSchema]].
  *
  * Which types are SQL-storable, how many columns a value occupies, and how it is written and read are all properties of the
  * [[kyo.SqlSchema]] instance itself, proved at compile time by its presence and enforced by construction by its codecs. What lives here
  * is the one check that compares instances rather than using one.
  */
private[kyo] object SqlColumns:

    /** Reference identity between two codec values, wildcard-friendly. The bind-agreement check compares identity rather than shape,
      * because two distinct codecs can encode the same column layout while disagreeing on the bytes.
      */
    def eqRef(a: SqlSchema[?], b: SqlSchema[?]): Boolean =
        a.asInstanceOf[AnyRef] eq b.asInstanceOf[AnyRef]

end SqlColumns
