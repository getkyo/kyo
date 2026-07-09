package kyo.fixtures

/** Target of a genuine cross-file body-level use site through a BARE MODULE QUALIFIER: a top-level
  * `object` selected directly by name (`CrossFileModule.value`), declared in a separate source file
  * from its consumer. Distinct from `CrossFileTarget`, whose consumer reaches it through a
  * PARAMETER's declared type rather than a module reference.
  */
object CrossFileModule:
    def value: Int = 1
end CrossFileModule
