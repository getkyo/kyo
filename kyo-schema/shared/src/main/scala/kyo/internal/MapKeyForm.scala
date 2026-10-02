package kyo.internal

import scala.util.NotGiven

/** Whether a map's key type is `String` itself, decided where the map's schema is summoned.
  *
  * A key whose schema is a string on the wire writes the map as an object, and at run time `String`'s schema and the schema of an opaque
  * type over it are the same instance. Only the static type tells an explicitly bound array-form schema for `String` keys
  * (`Schema.dictSchema[String, V]`, the binding `stringDictSchema` does not serve) from a string-backed key, so the general map givens
  * take this evidence. Public in `kyo.internal` because it is resolved at the user's site.
  */
final class MapKeyForm[K] private (val literalString: Boolean)

object MapKeyForm:
    given literal: MapKeyForm[String]                           = new MapKeyForm(true)
    given other[K](using NotGiven[K =:= String]): MapKeyForm[K] = new MapKeyForm(false)
end MapKeyForm
