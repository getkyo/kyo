package kyo.internal

/** Tracks the lifetime of a borrowed buffer.
  *
  * Attach to a buffer via checked-borrow APIs; every access then verifies [[isValid]] and throws [[BorrowRevoked]] once [[revoke]] is
  * called. The `label` appears in [[BorrowRevoked]] messages for diagnostics.
  */
final class BorrowOwner(val label: String):
    @volatile private var revoked = false

    /** `true` until [[revoke]] is called. Cheap, non-blocking read. */
    def isValid: Boolean = !revoked

    /** Mark this owner revoked. Idempotent. Subsequent access on checked-borrow buffers throw [[BorrowRevoked]]. */
    def revoke(): Unit = revoked = true
end BorrowOwner

/** Thrown on access to a borrowed buffer whose [[BorrowOwner]] has been revoked. The owner's label is embedded in the message for
  * diagnostic purposes.
  */
final class BorrowRevoked(owner: String) extends RuntimeException(s"Borrowed buffer revoked: $owner")
