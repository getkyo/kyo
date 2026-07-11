package kyo.fixtures

/** References kyo.fixtures.CrossFileTarget.value on a parameter typed in a separate source file, so
  * the body's SELECT use site resolves across a file boundary through the parameter's declared type.
  */
object CrossFileUser:
    def useIt(target: CrossFileTarget): Int = target.value
end CrossFileUser
