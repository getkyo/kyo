package kyo.internal.dolt

import kyo.*

/** Which revision the enclosing computation runs against, carried per fiber.
  *
  * A `Local` rather than a field on the client because the active branch is SESSION state on a Dolt server, measured: one connection
  * running `CALL DOLT_CHECKOUT('feature')` leaves a second connection reporting `main`. A branch held on the shared client would be read by
  * every fiber using that client, and two fibers working on two branches would race over one value.
  *
  * Nothing is pinned for the duration of a branch scope: each statement takes whatever connection is free, and [[DoltConnection]]
  * reconciles that connection onto the ambient revision before using it. [[kyo.Absent]] means the caller named no revision, which is not
  * the same as naming the default branch: a URL pointing at `app/main` and one pointing at `app` differ in what the server does.
  */
private[kyo] object DoltBranchScope:

    private val local: Local[Maybe[String]] = Local.init(Absent)

    /** The revision the enclosing computation asked for, or [[kyo.Absent]] when it asked for none. */
    def current(using Frame): Maybe[String] < Any = local.get

    /** Runs `body` against `revision`, restoring whatever was ambient before it. Nesting works and the innermost wins. */
    def let[A, S](revision: String)(body: A < S)(using Frame): A < S =
        local.let(Present(revision))(body)

end DoltBranchScope
