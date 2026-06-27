package kyo

import scala.annotation.StaticAnnotation

/** Attaches a human-readable description to a case-class field for schema derivation.
  *
  * When a field of a `derives Schema` case class carries `@doc("...")`, the derivation macro records the
  * string in that field's `Structure.Field.doc` slot, and JSON-Schema generation (`Json.jsonSchema`)
  * surfaces it as the field node's `description`. This lets the schema document its fields at the
  * definition site rather than through a separate programmatic description map.
  *
  * The annotation is read off the primary-constructor parameter symbol during derivation; placing it on
  * a field of a non-derived type, or on a non-case class, has no effect. A field with no `@doc` produces
  * a schema node with no `description`, unchanged from the pre-annotation behavior.
  *
  * Example:
  * {{{
  * case class City(@doc("ISO 3166 country code") country: String, name: String) derives Schema
  * }}}
  * The generated JSON schema gives the `country` property a `"description": "ISO 3166 country code"`.
  *
  * @param value
  *   the description text propagated into the field's schema node
  */
case class doc(value: String) extends StaticAnnotation
