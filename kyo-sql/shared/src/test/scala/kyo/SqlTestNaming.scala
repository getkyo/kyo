package kyo

/** Casing givens for static-fold tests. A `given` must resolve as a stable, already-compiled member for the static macro's reflective
  * fold, which a same-unit given cannot; suites downstream of this module import these instead.
  */
object SqlTestNaming:
    given snake: SqlNaming = SqlNaming.SnakeCase
end SqlTestNaming
