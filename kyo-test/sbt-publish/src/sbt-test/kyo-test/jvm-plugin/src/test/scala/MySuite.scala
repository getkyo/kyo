import kyo.test.Test

class MySuite extends Test[Any]:
    "passes" in {
        assert(1 + 1 == 2)
    }
end MySuite

/** Deliberately red.
  *
  * Broken wiring (missing artifact, wrong framework class, incomplete POM) makes sbt discover nothing
  * and report success, so a suite that only asserts a green build cannot tell a working setup from a
  * broken one. The `test` script requires this suite to fail; a green result here means discovery
  * silently found no tests.
  */
class FailingSuite extends Test[Any]:
    "fails" in {
        assert(1 + 1 == 3)
    }
end FailingSuite
