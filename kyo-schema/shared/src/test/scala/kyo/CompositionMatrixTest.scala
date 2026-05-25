package kyo

import java.time.Instant

// ===================================================================
// Top-level fixtures (macro derivation needs package-level visibility)
// ===================================================================

// --- Shared shape sealed-trait (used by Sweeps A/B/C) ---
sealed trait CMShape derives CanEqual
object CMShape:
    final case class CMCircle(radius: Double)          extends CMShape derives CanEqual
    final case class CMRectangle(w: Double, h: Double) extends CMShape derives CanEqual

// --- Shared user case class (used by Sweeps B/C) ---
final case class CMUser(name: String, password: String) derives CanEqual

// --- Per-category P1 wrappers (one Wrap per category to avoid the generic-derives pitfall) ---
final case class CMWrapInt(value: Int) derives CanEqual
final case class CMWrapStr(value: String) derives CanEqual
final case class CMWrapInstant(value: Instant) derives CanEqual
final case class CMWrapListInt(value: List[Int]) derives CanEqual
final case class CMWrapMaybeInt(value: Maybe[Int]) derives CanEqual
final case class CMWrapEitherStrInt(value: Either[String, Int]) derives CanEqual
final case class CMWrapTupIS(value: (Int, String)) derives CanEqual
final case class CMWrapShape(value: CMShape) derives CanEqual

// --- Sweep B PT1 case-class outer wrappers (one per transform target) ---
final case class CMOuterShape(inner: CMShape)
final case class CMOuterUser(inner: CMUser)

// --- Sweep B PT7 two-level-deep wrappers ---
final case class CMMiddleShape(inner: CMShape)
final case class CMOuterMiddleShape(middle: CMMiddleShape)
final case class CMMiddleUser(inner: CMUser)
final case class CMOuterMiddleUser(middle: CMMiddleUser)

// --- Sweep C envelopes ---
final case class CMEnvelopeShape(result: CMShape)
final case class CMEnvelopeUser(value: CMUser)

// ===================================================================
// Composition matrix tests
// ===================================================================
class CompositionMatrixTest extends Test:

    // Broad CanEqual to support equality across all category types (Instant, nested generics, tuples, etc.).
    private given CanEqual[Any, Any] = CanEqual.derived

    // --- Sample values per category ---
    private val c1Int: Int                          = 42
    private val c2Str: String                       = "hello"
    private val c3Instant: Instant                  = Instant.parse("2024-01-15T10:30:00Z")
    private val c4ListInt: List[Int]                = List(1, 2, 3)
    private val c5MaybeInt: Maybe[Int]              = Present(42)
    private val c6EitherStrInt: Either[String, Int] = Right(42)
    private val c7TupIS: (Int, String)              = (1, "hi")
    private val c8Shape: CMShape                    = CMShape.CMCircle(5.0)

    // --- Round-trip helper ---
    private def rt[T](value: T)(using schema: Schema[T]): T =
        val js = Json.encode(value)
        Json.decode[T](js).getOrThrow

    // ===============================================================
    // Sweep A — Type-at-position (48 leaves)
    // 8 categories x 6 positions
    // ===============================================================
    "Sweep A" - {

        // ---- P1: case-class field ----
        "P1 case-class field — C1 Int" in {
            assert(rt(CMWrapInt(c1Int)).value == c1Int)
        }
        "P1 case-class field — C2 String" in {
            assert(rt(CMWrapStr(c2Str)).value == c2Str)
        }
        "P1 case-class field — C3 Instant" in {
            assert(rt(CMWrapInstant(c3Instant)).value == c3Instant)
        }
        "P1 case-class field — C4 List[Int]" in {
            assert(rt(CMWrapListInt(c4ListInt)).value == c4ListInt)
        }
        "P1 case-class field — C5 Maybe[Int]" in {
            assert(rt(CMWrapMaybeInt(c5MaybeInt)).value == c5MaybeInt)
        }
        "P1 case-class field — C6 Either[String, Int]" in {
            assert(rt(CMWrapEitherStrInt(c6EitherStrInt)).value == c6EitherStrInt)
        }
        "P1 case-class field — C7 (Int, String)" in {
            assert(rt(CMWrapTupIS(c7TupIS)).value == c7TupIS)
        }
        "P1 case-class field — C8 MTShape" in {
            assert(rt(CMWrapShape(c8Shape)).value == c8Shape)
        }

        // ---- P2: List element ----
        "P2 List element — C1 Int" in {
            assert(rt(List(c1Int, c1Int)) == List(c1Int, c1Int))
        }
        "P2 List element — C2 String" in {
            assert(rt(List(c2Str, c2Str)) == List(c2Str, c2Str))
        }
        "P2 List element — C3 Instant" in {
            assert(rt(List(c3Instant, c3Instant)) == List(c3Instant, c3Instant))
        }
        "P2 List element — C4 List[Int]" in {
            assert(rt(List(c4ListInt, c4ListInt)) == List(c4ListInt, c4ListInt))
        }
        "P2 List element — C5 Maybe[Int]" in {
            assert(rt(List(c5MaybeInt, c5MaybeInt)) == List(c5MaybeInt, c5MaybeInt))
        }
        "P2 List element — C6 Either[String, Int]" in {
            assert(rt(List(c6EitherStrInt, c6EitherStrInt)) == List(c6EitherStrInt, c6EitherStrInt))
        }
        "P2 List element — C7 (Int, String)" in {
            assert(rt(List(c7TupIS, c7TupIS)) == List(c7TupIS, c7TupIS))
        }
        "P2 List element — C8 MTShape" in {
            assert(rt(List(c8Shape, c8Shape)) == List(c8Shape, c8Shape))
        }

        // ---- P3: Maybe wrapped ----
        "P3 Maybe wrapped — C1 Int" in {
            assert(rt[Maybe[Int]](Present(c1Int)) == Present(c1Int))
        }
        "P3 Maybe wrapped — C1 Int Absent" in {
            assert(rt[Maybe[Int]](Absent) == Absent)
        }
        "P3 Maybe wrapped — C2 String" in {
            assert(rt[Maybe[String]](Present(c2Str)) == Present(c2Str))
        }
        "P3 Maybe wrapped — C3 Instant" in {
            assert(rt[Maybe[Instant]](Present(c3Instant)) == Present(c3Instant))
        }
        "P3 Maybe wrapped — C4 List[Int]" in {
            assert(rt[Maybe[List[Int]]](Present(c4ListInt)) == Present(c4ListInt))
        }
        "P3 Maybe wrapped — C5 Maybe[Int]" in {
            assert(rt[Maybe[Maybe[Int]]](Present(c5MaybeInt)) == Present(c5MaybeInt))
        }
        "P3 Maybe wrapped — C6 Either[String, Int]" in {
            assert(rt[Maybe[Either[String, Int]]](Present(c6EitherStrInt)) == Present(c6EitherStrInt))
        }
        "P3 Maybe wrapped — C7 (Int, String)" in {
            assert(rt[Maybe[(Int, String)]](Present(c7TupIS)) == Present(c7TupIS))
        }
        "P3 Maybe wrapped — C8 MTShape" in {
            assert(rt[Maybe[CMShape]](Present(c8Shape)) == Present(c8Shape))
        }

        // ---- P4: Either Right leg ----
        "P4 Either Right leg — C1 Int" in {
            val v: Either[String, Int] = Right(c1Int)
            assert(rt(v) == v)
        }
        "P4 Either Right leg — C2 String" in {
            val v: Either[String, String] = Right(c2Str)
            assert(rt(v) == v)
        }
        "P4 Either Right leg — C3 Instant" in {
            val v: Either[String, Instant] = Right(c3Instant)
            assert(rt(v) == v)
        }
        "P4 Either Right leg — C4 List[Int]" in {
            val v: Either[String, List[Int]] = Right(c4ListInt)
            assert(rt(v) == v)
        }
        "P4 Either Right leg — C5 Maybe[Int]" in {
            val v: Either[String, Maybe[Int]] = Right(c5MaybeInt)
            assert(rt(v) == v)
        }
        "P4 Either Right leg — C6 Either[String, Int]" in {
            val v: Either[String, Either[String, Int]] = Right(c6EitherStrInt)
            assert(rt(v) == v)
        }
        "P4 Either Right leg — C7 (Int, String)" in {
            val v: Either[String, (Int, String)] = Right(c7TupIS)
            assert(rt(v) == v)
        }
        "P4 Either Right leg — C8 MTShape" in {
            val v: Either[String, CMShape] = Right(c8Shape)
            assert(rt(v) == v)
        }

        // ---- P5: Map value ----
        "P5 Map value — C1 Int" in {
            val v = Map("k1" -> c1Int, "k2" -> c1Int)
            assert(rt(v) == v)
        }
        "P5 Map value — C2 String" in {
            val v = Map("k1" -> c2Str, "k2" -> c2Str)
            assert(rt(v) == v)
        }
        "P5 Map value — C3 Instant" in {
            val v = Map("k1" -> c3Instant, "k2" -> c3Instant)
            assert(rt(v) == v)
        }
        "P5 Map value — C4 List[Int]" in {
            val v = Map("k1" -> c4ListInt, "k2" -> c4ListInt)
            assert(rt(v) == v)
        }
        "P5 Map value — C5 Maybe[Int]" in {
            val v = Map("k1" -> c5MaybeInt, "k2" -> c5MaybeInt)
            assert(rt(v) == v)
        }
        "P5 Map value — C6 Either[String, Int]" in {
            val v = Map("k1" -> c6EitherStrInt, "k2" -> c6EitherStrInt)
            assert(rt(v) == v)
        }
        "P5 Map value — C7 (Int, String)" in {
            val v = Map("k1" -> c7TupIS, "k2" -> c7TupIS)
            assert(rt(v) == v)
        }
        "P5 Map value — C8 MTShape" in {
            val v = Map("k1" -> c8Shape, "k2" -> c8Shape)
            assert(rt(v) == v)
        }

        // ---- P6: Tuple slot ----
        "P6 Tuple slot — C1 Int" in {
            val v: (Int, Boolean) = (c1Int, true)
            assert(rt(v) == v)
        }
        "P6 Tuple slot — C2 String" in {
            val v: (String, Boolean) = (c2Str, true)
            assert(rt(v) == v)
        }
        "P6 Tuple slot — C3 Instant" in {
            val v: (Instant, Boolean) = (c3Instant, true)
            assert(rt(v) == v)
        }
        "P6 Tuple slot — C4 List[Int]" in {
            val v: (List[Int], Boolean) = (c4ListInt, true)
            assert(rt(v) == v)
        }
        "P6 Tuple slot — C5 Maybe[Int]" in {
            val v: (Maybe[Int], Boolean) = (c5MaybeInt, true)
            assert(rt(v) == v)
        }
        "P6 Tuple slot — C6 Either[String, Int]" in {
            val v: (Either[String, Int], Boolean) = (c6EitherStrInt, true)
            assert(rt(v) == v)
        }
        "P6 Tuple slot — C7 (Int, String)" in {
            val v: ((Int, String), Boolean) = (c7TupIS, true)
            assert(rt(v) == v)
        }
        "P6 Tuple slot — C8 MTShape" in {
            val v: (CMShape, Boolean) = (c8Shape, true)
            assert(rt(v) == v)
        }
    }

    // ===============================================================
    // Sweep B — Transform-at-position (28 leaves)
    // 4 transforms x 7 positions
    // ===============================================================
    "Sweep B" - {

        // ---- T1 : discriminator("type") on CMShape ----
        "PT1 parent field — T1 discriminator" in {
            given Schema[CMShape] = Schema.derived[CMShape].discriminator("type")
            val js                = Json.encode(CMOuterShape(CMShape.CMCircle(1.0)))(using Schema[CMOuterShape])
            assert(js.contains("\"type\":\"CMCircle\""), js)
        }
        "PT2 List element — T1 discriminator" in {
            given Schema[CMShape] = Schema.derived[CMShape].discriminator("type")
            val js                = Json.encode(List[CMShape](CMShape.CMCircle(1.0), CMShape.CMCircle(2.0)))
            assert(js.contains("\"type\":\"CMCircle\""), js)
        }
        "PT3 Maybe wrapped — T1 discriminator" in {
            given Schema[CMShape] = Schema.derived[CMShape].discriminator("type")
            val js                = Json.encode[Maybe[CMShape]](Present(CMShape.CMCircle(1.0)))
            assert(js.contains("\"type\":\"CMCircle\""), js)
        }
        "PT4 Either Right leg — T1 discriminator" in {
            given Schema[CMShape]          = Schema.derived[CMShape].discriminator("type")
            val v: Either[String, CMShape] = Right(CMShape.CMCircle(1.0))
            val js                         = Json.encode(v)
            assert(js.contains("\"type\":\"CMCircle\""), js)
        }
        "PT5 Map value — T1 discriminator" in {
            given Schema[CMShape] = Schema.derived[CMShape].discriminator("type")
            val js                = Json.encode(Map[String, CMShape]("k1" -> CMShape.CMCircle(1.0)))
            assert(js.contains("\"type\":\"CMCircle\""), js)
        }
        "PT6 Tuple slot — T1 discriminator" in {
            given Schema[CMShape]     = Schema.derived[CMShape].discriminator("type")
            val v: (CMShape, Boolean) = (CMShape.CMCircle(1.0), true)
            val js                    = Json.encode(v)
            assert(js.contains("\"type\":\"CMCircle\""), js)
        }
        "PT7 two-level deep — T1 discriminator" in {
            given Schema[CMShape] = Schema.derived[CMShape].discriminator("type")
            val v                 = CMOuterMiddleShape(CMMiddleShape(CMShape.CMCircle(1.0)))
            val js                = Json.encode(v)(using Schema[CMOuterMiddleShape])
            assert(js.contains("\"type\":\"CMCircle\""), js)
        }

        // ---- T2 : .drop("password") on CMUser ----
        "PT1 parent field — T2 drop" in {
            given Schema[CMUser] = Schema[CMUser].drop("password")
            val js               = Json.encode(CMOuterUser(CMUser("alice", "secret")))(using Schema[CMOuterUser])
            assert(!js.contains("password"), js)
            assert(js.contains("\"name\":\"alice\""), js)
        }
        "PT2 List element — T2 drop" in {
            given Schema[CMUser] = Schema[CMUser].drop("password")
            val js               = Json.encode(List(CMUser("alice", "secret"), CMUser("bob", "hunter2")))
            assert(!js.contains("password"), js)
            assert(js.contains("\"name\":\"alice\""), js)
        }
        "PT3 Maybe wrapped — T2 drop" in {
            given Schema[CMUser] = Schema[CMUser].drop("password")
            val js               = Json.encode[Maybe[CMUser]](Present(CMUser("alice", "secret")))
            assert(!js.contains("password"), js)
            assert(js.contains("\"name\":\"alice\""), js)
        }
        "PT4 Either Right leg — T2 drop" in {
            given Schema[CMUser]          = Schema[CMUser].drop("password")
            val v: Either[String, CMUser] = Right(CMUser("alice", "secret"))
            val js                        = Json.encode(v)
            assert(!js.contains("password"), js)
            assert(js.contains("\"name\":\"alice\""), js)
        }
        "PT5 Map value — T2 drop" in {
            given Schema[CMUser] = Schema[CMUser].drop("password")
            val js               = Json.encode(Map("k1" -> CMUser("alice", "secret")))
            assert(!js.contains("password"), js)
            assert(js.contains("\"name\":\"alice\""), js)
        }
        "PT6 Tuple slot — T2 drop" in {
            given Schema[CMUser]     = Schema[CMUser].drop("password")
            val v: (CMUser, Boolean) = (CMUser("alice", "secret"), true)
            val js                   = Json.encode(v)
            assert(!js.contains("password"), js)
            assert(js.contains("\"name\":\"alice\""), js)
        }
        "PT7 two-level deep — T2 drop" in {
            given Schema[CMUser] = Schema[CMUser].drop("password")
            val v                = CMOuterMiddleUser(CMMiddleUser(CMUser("alice", "secret")))
            val js               = Json.encode(v)(using Schema[CMOuterMiddleUser])
            assert(!js.contains("password"), js)
            assert(js.contains("\"name\":\"alice\""), js)
        }

        // ---- T3 : .rename("name", "userName") on CMUser ----
        "PT1 parent field — T3 rename" in {
            given Schema[CMUser] = Schema[CMUser].rename("name", "userName")
            val js               = Json.encode(CMOuterUser(CMUser("alice", "secret")))(using Schema[CMOuterUser])
            assert(js.contains("\"userName\":\"alice\""), js)
        }
        "PT2 List element — T3 rename" in {
            given Schema[CMUser] = Schema[CMUser].rename("name", "userName")
            val js               = Json.encode(List(CMUser("alice", "secret")))
            assert(js.contains("\"userName\":\"alice\""), js)
        }
        "PT3 Maybe wrapped — T3 rename" in {
            given Schema[CMUser] = Schema[CMUser].rename("name", "userName")
            val js               = Json.encode[Maybe[CMUser]](Present(CMUser("alice", "secret")))
            assert(js.contains("\"userName\":\"alice\""), js)
        }
        "PT4 Either Right leg — T3 rename" in {
            given Schema[CMUser]          = Schema[CMUser].rename("name", "userName")
            val v: Either[String, CMUser] = Right(CMUser("alice", "secret"))
            val js                        = Json.encode(v)
            assert(js.contains("\"userName\":\"alice\""), js)
        }
        "PT5 Map value — T3 rename" in {
            given Schema[CMUser] = Schema[CMUser].rename("name", "userName")
            val js               = Json.encode(Map("k1" -> CMUser("alice", "secret")))
            assert(js.contains("\"userName\":\"alice\""), js)
        }
        "PT6 Tuple slot — T3 rename" in {
            given Schema[CMUser]     = Schema[CMUser].rename("name", "userName")
            val v: (CMUser, Boolean) = (CMUser("alice", "secret"), true)
            val js                   = Json.encode(v)
            assert(js.contains("\"userName\":\"alice\""), js)
        }
        "PT7 two-level deep — T3 rename" in {
            given Schema[CMUser] = Schema[CMUser].rename("name", "userName")
            val v                = CMOuterMiddleUser(CMMiddleUser(CMUser("alice", "secret")))
            val js               = Json.encode(v)(using Schema[CMOuterMiddleUser])
            assert(js.contains("\"userName\":\"alice\""), js)
        }

        // ---- T4 : .add("computed")(u => u.name.length) on CMUser ----
        "PT1 parent field — T4 add" in {
            given Schema[CMUser] = Schema[CMUser].add("computed")((u: CMUser) => u.name.length)
            val js               = Json.encode(CMOuterUser(CMUser("alice", "secret")))(using Schema[CMOuterUser])
            assert(js.contains("\"computed\":5"), js)
        }
        "PT2 List element — T4 add" in {
            given Schema[CMUser] = Schema[CMUser].add("computed")((u: CMUser) => u.name.length)
            val js               = Json.encode(List(CMUser("alice", "secret")))
            assert(js.contains("\"computed\":5"), js)
        }
        "PT3 Maybe wrapped — T4 add" in {
            given Schema[CMUser] = Schema[CMUser].add("computed")((u: CMUser) => u.name.length)
            val js               = Json.encode[Maybe[CMUser]](Present(CMUser("alice", "secret")))
            assert(js.contains("\"computed\":5"), js)
        }
        "PT4 Either Right leg — T4 add" in {
            given Schema[CMUser]          = Schema[CMUser].add("computed")((u: CMUser) => u.name.length)
            val v: Either[String, CMUser] = Right(CMUser("alice", "secret"))
            val js                        = Json.encode(v)
            assert(js.contains("\"computed\":5"), js)
        }
        "PT5 Map value — T4 add" in {
            given Schema[CMUser] = Schema[CMUser].add("computed")((u: CMUser) => u.name.length)
            val js               = Json.encode(Map("k1" -> CMUser("alice", "secret")))
            assert(js.contains("\"computed\":5"), js)
        }
        "PT6 Tuple slot — T4 add" in {
            given Schema[CMUser]     = Schema[CMUser].add("computed")((u: CMUser) => u.name.length)
            val v: (CMUser, Boolean) = (CMUser("alice", "secret"), true)
            val js                   = Json.encode(v)
            assert(js.contains("\"computed\":5"), js)
        }
        "PT7 two-level deep — T4 add" in {
            given Schema[CMUser] = Schema[CMUser].add("computed")((u: CMUser) => u.name.length)
            val v                = CMOuterMiddleUser(CMMiddleUser(CMUser("alice", "secret")))
            val js               = Json.encode(v)(using Schema[CMOuterMiddleUser])
            assert(js.contains("\"computed\":5"), js)
        }
    }

    // ===============================================================
    // Sweep C — Composition invariant (5 leaves)
    // For each pair, encoding the child alone and encoding the parent
    // containing the same child value should produce a parent JSON that
    // contains the child's JSON as a verbatim substring.
    // ===============================================================
    "Sweep C" - {

        "Sweep C #1 — Envelope(CMShape) discriminator invariant" in {
            given Schema[CMShape] = Schema.derived[CMShape].discriminator("type")
            val childVal: CMShape = CMShape.CMCircle(1.0)
            val childJs           = Json.encode(childVal)
            val parentJs          = Json.encode(CMEnvelopeShape(childVal))(using Schema[CMEnvelopeShape])
            assert(parentJs.contains(childJs), s"parent=$parentJs childJs=$childJs")
        }

        "Sweep C #2 — List[CMShape] discriminator invariant" in {
            given Schema[CMShape] = Schema.derived[CMShape].discriminator("type")
            val childVal: CMShape = CMShape.CMCircle(2.5)
            val childJs           = Json.encode(childVal)
            val parentJs          = Json.encode(List(childVal, childVal))
            assert(parentJs.contains(childJs), s"parent=$parentJs childJs=$childJs")
        }

        "Sweep C #3 — Map[String, CMUser].drop(\"password\") invariant" in {
            given Schema[CMUser] = Schema[CMUser].drop("password")
            val childVal         = CMUser("alice", "secret")
            val childJs          = Json.encode(childVal)
            val parentJs         = Json.encode(Map("k1" -> childVal))
            assert(parentJs.contains(childJs), s"parent=$parentJs childJs=$childJs")
        }

        "Sweep C #4 — Maybe[CMUser].rename(\"name\", \"userName\") invariant" in {
            given Schema[CMUser] = Schema[CMUser].rename("name", "userName")
            val childVal         = CMUser("alice", "secret")
            val childJs          = Json.encode(childVal)
            val parentJs         = Json.encode[Maybe[CMUser]](Present(childVal))
            assert(parentJs.contains(childJs), s"parent=$parentJs childJs=$childJs")
        }

        "Sweep C #5 — (CMUser, Boolean).add(\"computed\") invariant" in {
            given Schema[CMUser]     = Schema[CMUser].add("computed")((u: CMUser) => u.name.length)
            val childVal             = CMUser("alice", "secret")
            val childJs              = Json.encode(childVal)
            val v: (CMUser, Boolean) = (childVal, true)
            val parentJs             = Json.encode(v)
            assert(parentJs.contains(childJs), s"parent=$parentJs childJs=$childJs")
        }
    }

end CompositionMatrixTest
