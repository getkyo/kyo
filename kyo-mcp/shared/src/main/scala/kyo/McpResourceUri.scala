package kyo

/** Opaque type wrapping a resource URI string.
  *
  * Use `parse` for user-supplied URIs that require validation (rejects blank / whitespace-only
  * strings). Use `apply` at trusted call sites within the library where the value is known valid.
  * All public surface carrying resource identifiers uses this type, never raw `String`.
  *
  * @see [[McpResourceUri.parse]]
  */
opaque type McpResourceUri = String

object McpResourceUri:

    /** Returns `Present(uri)` if `s` is non-empty and not purely whitespace; `Absent` otherwise. */
    def parse(s: String): Maybe[McpResourceUri] =
        if s.nonEmpty && !s.forall(_.isWhitespace) then Present(s) else Absent

    /** Trusted call-site constructor; not gated by validation. */
    def apply(s: String): McpResourceUri = s

    extension (u: McpResourceUri)
        /** Returns the underlying string value. */
        def asString: String = u

    // Uses `apply` (total constructor) so the codec accepts any wire-received string.
    // Blank-rejection is at the user `parse` callsite, not at the codec level.
    given Schema[McpResourceUri] = Schema.stringSchema.transform[McpResourceUri](apply)(_.asString)

    given CanEqual[McpResourceUri, McpResourceUri] = CanEqual.derived

    /** Opaque type wrapping an RFC 6570 URI template string.
      *
      * `parse` enforces two minimal constraints: the string must be non-empty and must contain
      * at least one `{` character (the RFC 6570 template expression open-brace). Use `apply`
      * at trusted call sites. All public surface carrying URI templates uses this type, never
      * raw `String`.
      *
      * @see [[McpResourceUri.Template.parse]]
      */
    opaque type Template = String

    object Template:

        /** Returns `Present(t)` if `s` is non-empty and contains `{`; `Absent` otherwise. */
        def parse(s: String): Maybe[Template] =
            if s.nonEmpty && s.contains('{') then Present(s) else Absent

        /** Trusted call-site constructor; not gated by validation. */
        def apply(s: String): Template = s

        extension (t: Template)
            /** Returns the underlying string value. */
            def asString: String = t

            /** Extracts the variable bindings from a concrete URI that matches this template.
              *
              * Each `{name}` placeholder in the template captures non-empty text in the URI; the
              * returned `Map[String, String]` carries each `name -> captured-value`. Returns
              * `Absent` if the URI does not match the template's shape. Supports RFC 6570 Level 1
              * (`{var}`) syntax. Captured values include reserved characters like `/`, so
              * `Template("file:///{path}").extract(McpResourceUri("file:///foo/bar.txt"))` returns
              * `Present(Map("path" -> "foo/bar.txt"))`.
              */
            def extract(uri: McpResourceUri): Maybe[Map[String, String]] =
                val tmpl        = t.asString
                val placeholder = """\{([^}]+)\}""".r
                val varNames    = scala.collection.mutable.ListBuffer.empty[String]
                val builder     = new StringBuilder("^")
                var pos         = 0
                for m <- placeholder.findAllMatchIn(tmpl) do
                    builder.append(java.util.regex.Pattern.quote(tmpl.substring(pos, m.start)))
                    builder.append("(.+?)")
                    varNames += m.group(1)
                    pos = m.end
                end for
                builder.append(java.util.regex.Pattern.quote(tmpl.substring(pos)))
                builder.append("$")
                val regex = builder.toString.r
                regex.findFirstMatchIn(uri.asString) match
                    case Some(m) =>
                        Present(varNames.iterator.zip((1 to m.groupCount).iterator.map(m.group)).toMap)
                    case None => Absent
                end match
            end extract
        end extension

        // Uses `apply` (total constructor) so the codec accepts any wire-received string.
        given Schema[Template] = Schema.stringSchema.transform[Template](apply)(_.asString)

        given CanEqual[Template, Template] = CanEqual.derived

    end Template

end McpResourceUri
