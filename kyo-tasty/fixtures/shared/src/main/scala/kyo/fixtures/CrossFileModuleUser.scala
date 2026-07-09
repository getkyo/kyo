package kyo.fixtures

/** References `kyo.fixtures.CrossFileModule.value` by a bare module qualifier, from a separate
  * source file, so the body's SELECT use site resolves across a file boundary through the
  * qualifier's package-owned name rather than through a locally-typed parameter or value.
  */
object CrossFileModuleUser:
    def useIt: Int = CrossFileModule.value
end CrossFileModuleUser
