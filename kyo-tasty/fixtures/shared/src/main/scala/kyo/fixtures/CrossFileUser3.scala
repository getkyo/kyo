package kyo.fixtures

/** The sole consumer of kyo.fixtures.CrossFileTarget2.value, in a separate file from both
  * CrossFileTarget2 and CrossFileTarget's own consumers.
  */
object CrossFileUser3:
    def useItThree(target: CrossFileTarget2): Int = target.value
end CrossFileUser3
