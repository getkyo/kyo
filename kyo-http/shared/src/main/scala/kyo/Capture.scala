// package kyo

// import scala.NamedTuple.NamedTuple

// /** Type-safe path captures for HTTP route definitions.
//   *
//   * {{{
//   * HttpRoute2.get("users" / Capture[Int]("id"))
//   * HttpRoute2.get("users" / Capture[Int]("id") / "posts" / Capture[String]("slug"))
//   * }}}
//   *
//   * Extensible via [[HttpRoute2.Codec]]:
//   * {{{
//   * given HttpRoute2.Codec[MyId] = HttpRoute2.Codec(MyId.parse, _.value)
//   * HttpRoute2.get("items" / Capture[MyId]("itemId"))
//   * }}}
//   */
// object Capture:

// end Capture
