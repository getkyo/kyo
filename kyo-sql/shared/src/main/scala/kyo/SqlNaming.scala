package kyo

/** Compile-time SQL naming convention, summoned as an in-scope `given` at a query site.
  *
  * Governs how a Scala type or field name becomes a SQL table or column name when no more specific override applies (an explicit per-field
  * `@column` rename still wins for a column, and the table-name parameter wins for a table). It is a `given` rather than a schema property so it is
  * resolved where the query is written, which is what lets the static-SQL macro fold the resolved names at compile time.
  *
  * The built-ins are the enum's cases. Opt into one in a scope with a plain `given`:
  * {{{
  *   given SqlNaming = SqlNaming.SnakeCase
  * }}}
  * With no `given SqlNaming` in scope no declaration is needed: column names are the field names verbatim and a derived table name is
  * the lowercased type name, both folded statically. [[SqlNaming.Identity]] differs from that default only for the table name, which
  * it emits verbatim. A `given` declared at the top level or in a companion folds statically (the macro resolves it by reference); a
  * `given` that the macro cannot resolve statically (for example one declared local to a method) degrades to the runtime render path, which
  * produces the same, correct SQL.
  *
  * The cases:
  *   - [[SqlNaming.Identity]]: names emitted verbatim.
  *   - [[SqlNaming.SnakeCase]]: `signedUpAt` becomes `signed_up_at`, `HTTPServer` becomes `http_server`. See [[SqlNaming.camelToSnake]].
  *   - [[SqlNaming.SnakeCaseUpper]]: the same segmentation upper-cased, `signedUpAt` becomes `SIGNED_UP_AT`.
  *   - [[SqlNaming.UpperCase]]: `signedUpAt` becomes `SIGNEDUPAT`, a per-character, locale-independent fold.
  *   - [[SqlNaming.LowerCase]]: `SignedUpAt` becomes `signedupat`, likewise.
  *
  * There is no escape-style case (Quill's `Escape` / `MysqlEscape` tier), because kyo-sql quotes and escapes every identifier it renders
  * unconditionally; a convention here decides spelling, never safety.
  */
enum SqlNaming derives CanEqual:
    case Identity, SnakeCase, SnakeCaseUpper, UpperCase, LowerCase

    /** The SQL column name for Scala field name `fieldName` under this convention. */
    def columnName(fieldName: String): String = this match
        case Identity       => fieldName
        case SnakeCase      => SqlNaming.camelToSnake(fieldName)
        case SnakeCaseUpper => SqlNaming.camelToSnake(fieldName).map(_.toUpper)
        case UpperCase      => fieldName.map(_.toUpper)
        case LowerCase      => fieldName.map(_.toLower)

    /** The SQL table name for Scala type name `typeName` under this convention. */
    def tableName(typeName: String): String = this match
        case Identity       => typeName
        case SnakeCase      => SqlNaming.camelToSnake(typeName)
        case SnakeCaseUpper => SqlNaming.camelToSnake(typeName).map(_.toUpper)
        case UpperCase      => typeName.map(_.toUpper)
        case LowerCase      => typeName.map(_.toLower)
end SqlNaming

object SqlNaming:

    import scala.compiletime.ops.string.+
    import scala.compiletime.ops.string.Length
    import scala.compiletime.ops.string.Substring

    /** `countryCode` becomes `country_code`, `topLevelCategoryId` becomes `top_level_category_id`, and an acronym stays one segment:
      * `HTTPServer` becomes `http_server`, `httpURLId` becomes `http_url_id`, `userID` becomes `user_id`.
      *
      * A segment starts where a lowercase letter or digit is followed by an uppercase one, and where the last uppercase letter of a run
      * is followed by a lowercase one; everything then lowers per character (locale-independent). This deliberately diverges from
      * Quill's per-character algorithm (`HTTPServer` as `h_t_t_p_server`): the names this produces become a wire contract on first
      * release, so the better segmentation had to be chosen before that freeze, and from then on the mapping does not change.
      */
    private[kyo] def camelToSnake(s: String): String =
        val sb = new StringBuilder(s.length + 4)
        var i  = 0
        while i < s.length do
            val c = s.charAt(i)
            if c.isUpper && i > 0 then
                val prev        = s.charAt(i - 1)
                val nextIsLower = i + 1 < s.length && s.charAt(i + 1).isLower
                if prev.isLower || prev.isDigit || (prev.isUpper && nextIsLower) then sb.append('_')
            end if
            sb.append(c.toLower)
            i += 1
        end while
        sb.toString
    end camelToSnake

    /** `Invoice` becomes `invoice` and `HTTPServer` stays `HTTPServer`: the first character lowers only when the second is not also
      * uppercase (the JavaBeans rule), so an acronym-led name is not mangled into `hTTPServer`.
      *
      * The rule behind a derived query alias, which is a client-side record key rather than a wire name, and therefore deliberately
      * independent of any [[SqlNaming]] convention in scope. Only the ASCII letters `A`-`Z` count as uppercase and lower to `a`-`z`,
      * keeping this in exact agreement with the type-level [[Decapitalize]]; a non-ASCII head stays verbatim.
      */
    private[kyo] def decapitalize(s: String): String =
        def isAsciiUpper(c: Char) = c >= 'A' && c <= 'Z'
        if s.length < 1 || !isAsciiUpper(s.charAt(0)) then s
        else if s.length >= 2 && isAsciiUpper(s.charAt(1)) then s
        else s.updated(0, (s.charAt(0) + ('a' - 'A')).toChar)
    end decapitalize

    /** Type-level twin of [[decapitalize]], the rule behind the bare `Sql.from[T]` record key.
      *
      * A match type rather than a macro so it reduces in every typing context, including the body of an enclosing `inline def`, where
      * macro expansion is deferred. Only the ASCII letters `A`-`Z` count as uppercase and lower to `a`-`z`, exactly like the runtime
      * twin, so a non-ASCII head stays verbatim.
      */
    type Decapitalize[S <: String] <: String = Length[S] match
        case 0 => S
        case 1 => DecapitalizeHead[S]
        case _ => Substring[S, 1, 2] match
                case "A" => S
                case "B" => S
                case "C" => S
                case "D" => S
                case "E" => S
                case "F" => S
                case "G" => S
                case "H" => S
                case "I" => S
                case "J" => S
                case "K" => S
                case "L" => S
                case "M" => S
                case "N" => S
                case "O" => S
                case "P" => S
                case "Q" => S
                case "R" => S
                case "S" => S
                case "T" => S
                case "U" => S
                case "V" => S
                case "W" => S
                case "X" => S
                case "Y" => S
                case "Z" => S
                case _   => DecapitalizeHead[S]

    /** Lowers `S`'s head character when it is an ASCII uppercase letter; part of [[Decapitalize]]. */
    type DecapitalizeHead[S <: String] <: String = Substring[S, 0, 1] match
        case "A" => "a" + DecapitalizeTail[S]
        case "B" => "b" + DecapitalizeTail[S]
        case "C" => "c" + DecapitalizeTail[S]
        case "D" => "d" + DecapitalizeTail[S]
        case "E" => "e" + DecapitalizeTail[S]
        case "F" => "f" + DecapitalizeTail[S]
        case "G" => "g" + DecapitalizeTail[S]
        case "H" => "h" + DecapitalizeTail[S]
        case "I" => "i" + DecapitalizeTail[S]
        case "J" => "j" + DecapitalizeTail[S]
        case "K" => "k" + DecapitalizeTail[S]
        case "L" => "l" + DecapitalizeTail[S]
        case "M" => "m" + DecapitalizeTail[S]
        case "N" => "n" + DecapitalizeTail[S]
        case "O" => "o" + DecapitalizeTail[S]
        case "P" => "p" + DecapitalizeTail[S]
        case "Q" => "q" + DecapitalizeTail[S]
        case "R" => "r" + DecapitalizeTail[S]
        case "S" => "s" + DecapitalizeTail[S]
        case "T" => "t" + DecapitalizeTail[S]
        case "U" => "u" + DecapitalizeTail[S]
        case "V" => "v" + DecapitalizeTail[S]
        case "W" => "w" + DecapitalizeTail[S]
        case "X" => "x" + DecapitalizeTail[S]
        case "Y" => "y" + DecapitalizeTail[S]
        case "Z" => "z" + DecapitalizeTail[S]
        case _   => S

    /** `S` without its head character; part of [[Decapitalize]]. */
    type DecapitalizeTail[S <: String] = Substring[S, 1, Length[S]]
end SqlNaming
