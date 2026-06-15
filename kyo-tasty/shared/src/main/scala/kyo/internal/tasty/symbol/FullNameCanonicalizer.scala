package kyo.internal.tasty.symbol

/** Converts JVM binary names (with '/' separators and '$' for nesting) to dotted fully-qualified names using the InnerClasses attribute
  * table.
  *
  * Protocol:
  *   - Top-level class: binary name with '/' replaced by '.', '$' preserved literally.
  *   - Anonymous/local class (outerBinaryName == "" or innerSimpleName == ""): preserve '$' form.
  *   - Named inner class: recursively resolve outer, then append '.' + innerSimpleName.
  *
  * Cross-platform: pure string operations, no JVM I/O.
  */
object FullNameCanonicalizer:

    /** Convert a JVM binary name (with '/' separators and '$' for nesting) to a dotted fully-qualified name using the InnerClasses
      * attribute table.
      *
      * innerClassTable: key = inner binary name (slashes, not dots); value = (outerBinaryName, innerSimpleName). An entry with
      * outerBinaryName == "" is anonymous/local; preserve '$' form.
      *
      * Examples: toFullName("java/util/Map$Entry", table) = "java.util.Map.Entry" toFullName("java/lang/Object", emptyTable) =
      * "java.lang.Object" toFullName("com/example/Foo$1", table with ("com/example/Foo$1", ("", ""))) = "com.example.Foo$1"
      */
    def toFullName(binaryName: String, innerClassTable: Map[String, (String, String)]): String =
        innerClassTable.get(binaryName) match
            case None =>
                // Not in table: top-level class. Convert '/' to '.', preserve '$'.
                binaryName.replace('/', '.')
            case Some((outerBinaryName, innerSimpleName)) if outerBinaryName.isEmpty || innerSimpleName.isEmpty =>
                // Anonymous or local: outer is unknown or name is absent. Keep '$' form.
                binaryName.replace('/', '.')
            case Some((outerBinaryName, innerSimpleName)) =>
                // Named inner class: recurse on outer.
                val outerFullName = toFullName(outerBinaryName, innerClassTable)
                outerFullName + "." + innerSimpleName

    /** Convert a dotted fully-qualified name back to JVM binary name (with '/' separators).
      *
      * Requires a pre-built reverse index (dotted fully-qualified name -> binary name with '/') that the caller constructs from the innerClassTable at
      * ClassfileResult construction time.
      *
      * If the full name is not in the reverse index, falls back to replacing '.' with '/' (correct for top-level classes where the fully-qualified name equals the
      * binary name with '.' instead of '/').
      */
    def toBinaryName(fullName: String, reverseIndex: Map[String, String]): String =
        reverseIndex.getOrElse(fullName, fullName.replace('.', '/'))

    /** Build a reverse index from a forward innerClassTable.
      *
      * For each entry in innerClassTable, compute the dotted fully-qualified name via toFullName and map it back to the binary name (the key). Used by
      * ClassfileUnpickler to construct a reverse lookup for binaryName resolution.
      */
    def buildReverseIndex(innerClassTable: Map[String, (String, String)]): Map[String, String] =
        val builder = Map.newBuilder[String, String]
        for binaryName <- innerClassTable.keys do
            val resolvedName = toFullName(binaryName, innerClassTable)
            builder += (resolvedName -> binaryName)
        end for
        builder.result()
    end buildReverseIndex

end FullNameCanonicalizer
