package kyo.fixtures

/** Target of a genuine cross-file body-level use site: a parameter typed `CrossFileTarget` in
  * kyo.fixtures.CrossFileUser.useIt, declared in a separate source file.
  */
class CrossFileTarget:
    val value: Int = 1
end CrossFileTarget
