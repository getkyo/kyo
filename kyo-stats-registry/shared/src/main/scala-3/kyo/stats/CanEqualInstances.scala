package kyo.stats

// Strict-equality evidence for the attribute types, reached with `import kyo.stats.given`; in scala-3/ because CanEqual does not exist on
// Scala 2.13 and the companions are shared source, where a given cannot be written.

given CanEqual[Attributes, Attributes]                     = CanEqual.derived
given CanEqual[Attributes.Attribute, Attributes.Attribute] = CanEqual.derived
