package kyo.internal.tasty.symbol

/** Maps compiler-mangled FQNs to source-form FQNs, and identifies synthetic compiler-generated names.
  *
  * Scala 3 encodes several source-level constructs under mangled binary names. This normalizer converts
  * those binary forms back to the names a user would write in source code.
  *
  * The canonical source FQN is used as the primary key in user-facing indexes (findClass, findTrait,
  * findObject). The binary FQN is preserved as a secondary key for backward compatibility.
  *
  * Synthetic names (anon functions, proxies, trait forwarders, etc.) have no source representation
  * and are filtered out of user-facing indexes but kept in cp.symbols for introspection.
  *
  * Rule application order (first match wins, applied repeatedly until fixpoint):
  *   1. Opaque-type companion: segments matching NAME$package$.X are stripped.
  *   2. Trailing $ on objects: strip the trailing dollar sign.
  *   3. Object-nested member: replace $. with . throughout.
  *   4. Inner-class separator: replace remaining mid-string $ with . (after Rules 1-3).
  */
private[kyo] object FqnNormalizer:

    private val ValueClassFqns: Set[String] = Set(
        "scala.Int",
        "scala.Long",
        "scala.Double",
        "scala.Float",
        "scala.Boolean",
        "scala.Byte",
        "scala.Short",
        "scala.Char",
        "scala.Unit"
    )

    /** Map a compiler-mangled FQN back to its source-form FQN.
      *
      * Rules applied in order, iterated to fixpoint (max 8 passes to prevent infinite loops):
      *   1. Strip `$package$.` segments (opaque-type companion wrapper).
      *   2. Strip trailing `$` (object companion suffix).
      *   3. Replace `$.` with `.` (object-nested member separator).
      *   4. Replace remaining mid-string `$` with `.` (inner-class separator).
      *
      * Examples:
      *   "kyo.Maybe$package$.Maybe"      -> "kyo.Maybe"
      *   "scala.Predef$"                 -> "scala.Predef"
      *   "kyo.Tasty$.Symbol"             -> "kyo.Tasty.Symbol"
      *   "kyo.Foo$Bar"                   -> "kyo.Foo.Bar"
      *   "scala.Option"                  -> "scala.Option"  (no-op)
      */
    def canonicalSourceFqn(binary: String): String =
        var s    = binary
        var i    = 0
        var prev = ""
        while i < 8 && s != prev do
            prev = s
            // Rule 1: strip $package$. segments
            val pkgSuffix = "$package$."
            var idx       = s.indexOf(pkgSuffix)
            while idx >= 0 do
                // Remove the segment from its nearest preceding dot (or start) up to and including "$package$."
                val dotBefore = s.lastIndexOf('.', idx - 1)
                val start     = if dotBefore >= 0 then dotBefore + 1 else 0
                s = s.substring(0, start) + s.substring(idx + pkgSuffix.length)
                idx = s.indexOf(pkgSuffix)
            end while
            // Rule 2: strip trailing $
            if s.endsWith("$") && s.length > 1 then s = s.dropRight(1)
            // Rule 3: replace $. with .
            s = s.replace("$.", ".")
            // Rule 4: replace remaining mid-string $ with . (not at end, not part of $package or $$)
            // Only replace $ that are surrounded by non-$ characters to avoid breaking $$ patterns.
            s = replaceInnerDollar(s)
            i += 1
        end while
        s
    end canonicalSourceFqn

    /** Replace single $ separators (inner-class convention) with dots.
      *
      * Skips: $$ (double-dollar synthetic patterns), trailing $, leading $.
      * Only replaces a $ when the character before it is not $ and the character after it is not $.
      *
      * Uses explicit `i == 0` / `i == len - 1` boundary checks instead of an in-band sentinel
      * character. A sentinel approach would mis-classify a real sentinel-value character adjacent
      * to `$` as a boundary, preventing replacement in pathological inputs.
      */
    private def replaceInnerDollar(s: String): String =
        val len = s.length
        if len == 0 then return s
        val sb = new java.lang.StringBuilder(len)
        var i  = 0
        while i < len do
            val c = s.charAt(i)
            if c == '$' then
                val atStart      = i == 0
                val atEnd        = i == len - 1
                val prevIsDollar = !atStart && s.charAt(i - 1) == '$'
                val nextIsDollar = !atEnd && s.charAt(i + 1) == '$'
                // Replace $ only when: not at start/end and not adjacent to another $.
                if !atStart && !atEnd && !prevIsDollar && !nextIsDollar then
                    sb.append('.')
                else
                    sb.append('$')
                end if
            else
                sb.append(c)
            end if
            i += 1
        end while
        sb.toString
    end replaceInnerDollar

    /** Return true if this FQN is a compiler-synthetic name with no source representation.
      *
      * Synthetic names are kept in cp.symbols (for introspection) but excluded from user-facing
      * indexes (findClass, findTrait, findObject, topLevelClasses, allClasses).
      *
      * Patterns (each drives a test leaf):
      *   "$anonfun$"   - anonymous function lifted to a method
      *   "$proxy$"     - proxy method synthesized for inline expansion
      *   "$_trait_"    - trait static forwarder
      *   "$$Lambda$"   - JDK lambda thunk
      *   "$$anon$"     - anonymous class
      *   "$$anonfun$"  - alternative anonfun form
      *   "$adapted$"   - value-class adapter
      *   "$default$"   - default-argument synthesizer
      *
      * CRITICAL: "kyo.Maybe$package$.Maybe" is NOT synthetic; it is an opaque-type companion.
      * The opaque pattern must NOT be classified as synthetic.
      */
    def isSyntheticName(fqn: String): Boolean =
        fqn.contains("$anonfun$") ||
            fqn.contains("$proxy$") ||
            fqn.contains("$_trait_") ||
            fqn.contains("$$Lambda$") ||
            fqn.contains("$$anon$") ||
            fqn.contains("$$anonfun$") ||
            fqn.contains("$adapted$") ||
            fqn.contains("$default$")
    end isSyntheticName

    /** Return true if the given source FQN corresponds to a known Scala value class.
      *
      * Value classes (scala.Int, scala.Long, etc.) use scala.AnyVal as their synthetic default parent
      * for default-parent injection when no explicit parent is present in TASTy.
      */
    def isValueClass(sourceFqn: String): Boolean = ValueClassFqns.contains(sourceFqn)

end FqnNormalizer
