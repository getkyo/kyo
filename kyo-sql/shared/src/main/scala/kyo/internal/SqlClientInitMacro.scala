package kyo.internal

import java.nio.charset.StandardCharsets
import kyo.Absent
import kyo.Maybe
import kyo.Present
import kyo.db.Backend
import scala.quoted.*
import scala.util.control.NonFatal

/** Warns at compile time when no backend on the compile classpath can open a URL.
  *
  * Which backends an application has is decided by which artifacts it depends on, so when the URL is a literal both halves of the question
  * are known while compiling: the factories declared in `META-INF/services/kyo.db.Backend`, and the scheme being asked for. Two mistakes
  * are then surfaced at the call site instead of at first connect: depending on no backend at all, and asking for a scheme none of the
  * ones present claims. Each is a warning rather than an error, because the compile classpath is strong evidence and not proof: runtime
  * discovery ([[kyo.db.Backend.register]]) can still supply the backend the warning says is missing, and forbidding the program would
  * forbid that legitimate arrangement. When discovery finds nothing either, the open fails typed at run time.
  *
  * Everything this check cannot establish defers to runtime rather than guessing. A URL computed at runtime carries no scheme to check, an
  * unparseable literal is [[kyo.SqlConfig.Url.parse]]'s to report, and a factory the compiler's own classloader cannot construct is still
  * evidence that the artifact declaring it is on the compile classpath. In each case the open aborts with
  * [[kyo.SqlConnectionUnsupportedSchemeException]] or [[kyo.SqlConnectionUrlParseException]], typed either way.
  *
  * That last case is a state of its own rather than a smaller answer, and it is what keeps both refusals honest. A factory that cannot be
  * constructed withholds every scheme it claims, so a scheme set that is empty and a scheme set missing the one asked for are equally
  * unproven while one factory is outstanding: the artifact IS a dependency, and the classloader compiling a call site is not the one that
  * will run it. Neither refusal fires until every declared factory has answered, which is why [[Verdict]] has four cases and not three.
  */
private[kyo] object SqlClientInitMacro:

    /** Checks `rawUrl` against the backends on the compile classpath, warning at the call site when nothing there can open it. */
    inline def checkScheme(inline rawUrl: String): Unit = ${ checkSchemeImpl('rawUrl) }

    private val servicesPath = "META-INF/services/kyo.db.Backend"

    def checkSchemeImpl(rawUrl: Expr[String])(using Quotes): Expr[Unit] =
        import quotes.reflect.*
        // Absent is a URL that is not a compile-time constant, or one with no scheme in it: both checks defer to runtime.
        val refusal = Maybe.fromOption(rawUrl.value).flatMap(schemeOf).flatMap { scheme =>
            refusalFor(scheme, verdictFor(scheme, probeFactories()))
        }
        refusal match
            case Present(message) =>
                report.warning(message)
                '{ () }
            case Absent => '{ () }
        end match
    end checkSchemeImpl

    /** What the factories declared on the compile classpath answered when asked which schemes they claim.
      *
      * `schemes` carries every scheme and alias the factories that could be constructed named. `unreadable` names the factories that are
      * declared and could not be constructed here, each of which may claim schemes `schemes` does not carry, which is why they are kept
      * rather than dropped. Both empty means the services resources declared no factory at all, since a factory that answers contributes at
      * least its own scheme.
      */
    final private[kyo] case class Declared(schemes: Set[String], unreadable: List[String])

    /** What the compile classpath proves about one scheme.
      *
      * Two of the four are refusals, and what separates them from the other two is proof rather than degree. A scheme some factory claims
      * is openable, and a scheme nothing claims is unopenable only once every declared factory has answered: until then the classpath
      * carries an artifact whose claims are unknown, which is not the same fact as an artifact that claims something else.
      */
    private[kyo] enum Verdict derives CanEqual:

        /** A factory that answered claims the scheme, so the open resolves to it. */
        case Claimed

        /** Nothing that answered claims the scheme, and a declared factory did not answer, so the classpath proves neither refusal. */
        case Undecided

        /** The services resources declare no backend factory at all. */
        case NoBackend

        /** Every declared factory answered and none claims the scheme. `available` is the complete set they do claim. */
        case Unclaimed(available: Set[String])

    end Verdict

    /** Decides `scheme` against `declared`, refusing only what the declaration is complete enough to disprove.
      *
      * The order of the tests is the order of what is provable. A factory that answered and claims the scheme settles it whatever else
      * failed to answer, since a backend that claims a scheme is a backend that opens it. After that an unread factory outranks both
      * refusals, because it withholds exactly the claims that would decide between them.
      */
    private[kyo] def verdictFor(scheme: String, declared: Declared): Verdict =
        if declared.schemes.contains(scheme) then Verdict.Claimed
        else if declared.unreadable.nonEmpty then Verdict.Undecided
        else if declared.schemes.isEmpty then Verdict.NoBackend
        else Verdict.Unclaimed(declared.schemes)
    end verdictFor

    /** The compile error `verdict` calls for when asked about `scheme`, or [[kyo.Absent]] when it calls for none.
      *
      * The two refusals say different things because they are different facts, and neither says anything the verdict does not carry. The
      * empty classpath names no engine and no artifact, since backends are discovered rather than listed here and a message naming kyo's
      * own two misdirects everyone using a third. The unclaimed scheme names what IS available, which is a complete set by the time this is
      * reached: a classpath still holding an unread factory answers [[Verdict.Undecided]] and produces no error at all.
      */
    private[kyo] def refusalFor(scheme: String, verdict: Verdict): Maybe[String] =
        verdict match
            case Verdict.Claimed | Verdict.Undecided => Absent
            case Verdict.NoBackend =>
                Present(
                    s"No SQL backend is on the compile classpath, so '$scheme://...' can only open through a backend registered at run time. " +
                        "Add the kyo-sql backend artifact for the engine this URL names."
                )
            case Verdict.Unclaimed(available) =>
                Present(
                    s"No SQL backend on the compile classpath claims the scheme '$scheme'. " +
                        s"Available: ${available.toSeq.sorted.mkString(", ")}. " +
                        "The open fails at run time unless a backend claiming it registers at startup."
                )
    end refusalFor

    /** The scheme `url` names, or [[kyo.Absent]] when it carries no `scheme://` prefix for the parser to find. */
    private def schemeOf(url: String): Maybe[String] =
        url.indexOf("://") match
            case -1 => Absent
            // Per-char lowercasing, matching SqlConfig.Url.parse's locale-independent scheme normalisation.
            case end => Present(url.substring(0, end).map(_.toLower))

    /** Asks every factory the compile classpath declares which schemes it claims, recording the ones that could not answer.
      *
      * Each factory is constructed on the compiler's classloader and asked, which the SPI contract permits: a factory ships a public
      * zero-argument constructor with no side effects precisely because the derivation constructs one at every call site. A factory that
      * cannot be constructed here is carried as unreadable rather than dropped, so a classloader that does not carry the backend's bytes
      * cannot turn a working program into a compile error.
      */
    private def probeFactories(): Declared =
        val (claimed, unread) =
            factoryNames().foldLeft((Set.empty[String], List.empty[String])) { case ((schemes, unreadable), fqn) =>
                claimedBy(fqn) match
                    case Present(claims) => (schemes ++ claims, unreadable)
                    case Absent          => (schemes, fqn :: unreadable)
            }
        Declared(claimed, unread.reverse)
    end probeFactories

    /** The schemes the factory `fqn` names claims, or [[kyo.Absent]] when the compiler's classloader cannot construct it. */
    private def claimedBy(fqn: String): Maybe[Set[String]] =
        try
            // Erasure boundary: reflective construction is typed Object, and a services entry that names a class
            // which is not a Factory is a broken artifact, so the ClassCastException is caught below with the rest.
            val factory = Class.forName(fqn, true, this.getClass.getClassLoader)
                .getDeclaredConstructor()
                .newInstance()
                .asInstanceOf[Backend]
            Present(factory.aliases + factory.scheme)
        catch
            case _: LinkageError               => Absent
            case ex: Throwable if NonFatal(ex) => Absent
        end try
    end claimedBy

    /** The factory class names declared across every services resource, following the `ServiceLoader` file convention. */
    private def factoryNames(): List[String] =
        val resources = this.getClass.getClassLoader.getResources(servicesPath)
        val names     = List.newBuilder[String]
        while resources.hasMoreElements do
            val stream = resources.nextElement().openStream()
            try
                val text = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                text.linesIterator.foreach { line =>
                    val name = line.takeWhile(_ != '#').trim
                    if name.nonEmpty then names += name
                }
            finally stream.close()
            end try
        end while
        names.result().distinct
    end factoryNames

end SqlClientInitMacro
