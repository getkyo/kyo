package kyo.fixtures

/** A second cross-file consumer of kyo.fixtures.CrossFileTarget.value, alongside CrossFileUser, so
  * references(...) can be tested against use sites in two distinct files.
  */
object CrossFileUser2:
    def useItToo(target: CrossFileTarget): Int = target.value
end CrossFileUser2
