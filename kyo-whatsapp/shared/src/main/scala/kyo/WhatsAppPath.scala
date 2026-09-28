package kyo

/** The path of a Graph API endpoint the module does not model, as `WhatsApp.custom` calls it: `me/phone_numbers`,
  * `106540352242922/message_templates`.
  *
  * The path is appended under `{baseUrl}/{apiVersion}/`, and the request carries the access token, so a `?`, `#`, `%`, space or `..` in
  * it would move the token to another path or put text into a query. Construction accepts only relative segments of ASCII letters,
  * digits, `.`, `_` and `-` joined by single slashes, no segment being `.` or `..`, and panics with [[kyo.WhatsAppInvalidPathException]]
  * otherwise. A path is never stripped to a shorter one. Query parameters go in `custom`'s `query`, which the module encodes.
  *
  * @see
  *   [[kyo.WhatsApp.custom]] the operation that takes it
  */
opaque type WhatsAppPath = String

object WhatsAppPath:

    /** The path `path`, relative segments of `A-Z a-z 0-9 . _ -` joined by single slashes. */
    def apply(path: String)(using Frame): WhatsAppPath =
        import WhatsAppInvalidPathException.Problem
        if path.isEmpty then throw WhatsAppInvalidPathException(Problem.Empty)
        val bad = path.indexWhere(c => !isPathChar(c))
        if bad >= 0 then throw WhatsAppInvalidPathException(Problem.Character(bad))
        val segments = path.split("/", -1)
        segments.indices.foreach { i =>
            if segments(i).isEmpty then throw WhatsAppInvalidPathException(Problem.EmptySegment(i))
            if segments(i) == "." || segments(i) == ".." then throw WhatsAppInvalidPathException(Problem.DotSegment(i))
        }
        path
    end apply

    extension (self: WhatsAppPath) def value: String = self

    given CanEqual[WhatsAppPath, WhatsAppPath] = CanEqual.derived

    private def isPathChar(c: Char): Boolean =
        (c >= 'a' && c <= 'z') ||
            (c >= 'A' && c <= 'Z') ||
            (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-' || c == '/'

end WhatsAppPath
