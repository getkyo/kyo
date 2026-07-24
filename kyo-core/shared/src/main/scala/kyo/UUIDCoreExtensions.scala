package kyo

/** Effectful UUID generation operations exported onto the [[UUID]] companion. */
object UUIDCoreExtensions:

    extension (self: UUID.type)

        /** Generates a secure RFC version 4 UUID with the dynamically scoped generator. */
        def v4(using Frame): UUID < Sync =
            UUIDGenerator.v4

        /** Generates a monotonic RFC version 7 UUID with the dynamically scoped generator. */
        def v7(using Frame): UUID < Sync =
            UUIDGenerator.v7

        /** Runs `value` with `generator` installed as the dynamically scoped UUID generator. */
        def let[A, S](generator: UUIDGenerator)(value: A < S)(using Frame): A < (S & Sync) =
            UUIDGenerator.let(generator)(value)
    end extension
end UUIDCoreExtensions

export UUIDCoreExtensions.*
