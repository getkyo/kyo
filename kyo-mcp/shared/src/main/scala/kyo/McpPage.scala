package kyo

/** Named-record paginated list result.
  *
  * Replaces the tuple `(Chunk[A], Maybe[String])` pattern per Audit-A3 / INV-023.
  * `nextCursor` is `Absent` on the last page.
  *
  * @tparam A the item type
  */
final case class McpPage[+A](items: Chunk[A], nextCursor: Maybe[String]) derives CanEqual:
    /** Returns `true` when there are no further pages. */
    def isLast: Boolean = nextCursor.isEmpty

object McpPage:
    /** Returns an empty page with no cursor. */
    def empty[A]: McpPage[A] = McpPage(Chunk.empty, Absent)

    /** Constructs a page from items and an optional continuation cursor. */
    def of[A](items: Chunk[A], next: Maybe[String]): McpPage[A] = McpPage(items, next)

    given [A: Schema]: Schema[McpPage[A]] = Schema.derived

end McpPage
