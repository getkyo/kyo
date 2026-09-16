package kyo

/** A Dolt commit hash, which is a 32-character base-32 string rather than the hex a git user expects.
  *
  * Opaque over `String` so it cannot be confused with a branch name, a tag, or a message, and widens back to `String` where text is wanted.
  */
opaque type DoltCommitHash <: String = String

object DoltCommitHash:

    /** Wraps text the server produced. Unvalidated: a hash the server does not recognise fails at the operation that uses it. */
    def apply(value: String): DoltCommitHash = value

end DoltCommitHash
