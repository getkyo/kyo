package kyo

case class MTPerson(name: String, age: Int) derives CanEqual
case class MTAddress(street: String, city: String, zip: String) derives CanEqual
case class MTPersonAddr(name: String, age: Int, address: MTAddress) derives CanEqual
case class MTTeam(name: String, lead: MTPersonAddr, members: List[MTPersonAddr]) derives CanEqual
case class MTCompany(name: String, hq: MTTeam) derives CanEqual
case class MTConfig(host: String, port: Int = 8080, ssl: Boolean = false) derives CanEqual
case class MTPair[A, B](first: A, second: B) derives CanEqual
case class MTOrder(id: Int, items: List[MTItem]) derives CanEqual
case class MTItem(name: String, price: Double) derives CanEqual
case class MTWrapper(value: String) derives CanEqual
case class MTEvil(get: String, set: Int, path: String, selectDynamic: Boolean) derives CanEqual
case class MTUser(name: String, age: Int, email: String, ssn: String) derives CanEqual

case class MTOptional(name: String, nickname: Option[String]) derives CanEqual
case class MTAllDefaults(a: Int = 1, b: String = "hello", c: Boolean = false) derives CanEqual
case class MTNestedDefault(name: String, address: MTAddress = MTAddress("", "", "")) derives CanEqual
case class MTWithDefault(name: String, age: Int, active: Boolean = true) derives CanEqual
case class MTThreeField(x: Int, y: String, z: Boolean) derives CanEqual
case class MTSmallTeam(lead: MTPerson, size: Int) derives CanEqual
case class MTNamedTeam(name: String, lead: MTPerson, size: Int) derives CanEqual

sealed trait MTShape derives CanEqual
case class MTCircle(radius: Double)                   extends MTShape derives CanEqual
case class MTRectangle(width: Double, height: Double) extends MTShape derives CanEqual
case class MTDrawing(title: String, shape: MTShape) derives CanEqual

case class MTDebugConfig(host: String, port: Int = 8080, debug: Boolean = false) derives CanEqual
case class MTPublicUser(name: String, age: Int) derives CanEqual
case class MTEmpty() derives CanEqual
case class MTLarge(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int, g: Int, h: Int, i: Int, j: Int) derives CanEqual
case class MTUserName(userName: String, age: Int) derives CanEqual
case class MTUserResponse(userName: String, age: Int, active: Boolean) derives CanEqual
case class MTEmailAge(email: String, age: Int) derives CanEqual
case class MTRegistration(username: String, age: Int, email: String, referralCode: Option[String]) derives CanEqual
case class MTDisplayUser(displayName: String, age: Int) derives CanEqual
case class MTStringAge(name: String, age: String) derives CanEqual

// ETL test types
case class MTRawEvent(userId: String, eventType: String, timestamp: Long, metadata: String) derives CanEqual
case class MTCleanEvent(user: String, kind: String, ts: String) derives CanEqual

// Migration test types
case class MTUserV1(name: String, age: Int, email: String) derives CanEqual
case class MTUserV2(fullName: String, age: Int, email: String, active: Boolean = true) derives CanEqual
case class MTUserV2NoDefault(fullName: String, age: Int, email: String, role: String) derives CanEqual
case class MTMigrated(user: String, age: Int, active: Boolean) derives CanEqual
case class MTMigratedStringAge(name: String, age: String) derives CanEqual

// Generic middleware test type
case class MTProduct(name: String, price: Double, sku: String) derives CanEqual

// Domain event test types
case class MTOrderEvent(orderId: String, action: String, amount: Double, timestamp: Long) derives CanEqual
case class MTAuditEntry(orderId: String, action: String, amount: String, time: Long) derives CanEqual

// Account test type
case class MTAccount(name: String, email: String, tier: String, score: Int) derives CanEqual

// Contact test type
case class MTContact(name: String, email: String, phone: String) derives CanEqual

// Versioning test types
case class MTUserV2WithRole(fullName: String, age: Int, email: String, active: Boolean, role: String) derives CanEqual
case class MTUserV2Renamed(displayName: String, age: Int, email: String, active: Boolean) derives CanEqual
case class MTUserV2StringAge(fullName: String, age: String, email: String) derives CanEqual

// Each test types
case class MTEachItem(name: String, price: Double, tags: Seq[String]) derives CanEqual
case class MTEachOrder(id: Int, items: Seq[MTEachItem], note: String) derives CanEqual
case class MTWarehouse(name: String, orders: Seq[MTEachOrder]) derives CanEqual
case class MTVecOrder(id: Int, items: Vector[MTEachItem]) derives CanEqual
case class MTListOrder(id: Int, items: List[MTEachItem]) derives CanEqual

// Focus composition test types
case class MTGallery(name: String, drawings: Seq[MTDrawing]) derives CanEqual
case class MTDepartment(name: String, team: MTTeam) derives CanEqual

// Non-derivable types for error message tests
class MTOpaque(val inner: Int)
trait MTOpenTrait

// Recursive test types
case class TreeNode(value: Int, children: List[TreeNode]) derives CanEqual

case class RTDepartment(name: String, manager: RTEmployee) derives CanEqual
case class RTEmployee(name: String, department: Maybe[RTDepartment]) derives CanEqual

object RTDepartment:
    given Schema[RTDepartment] = Schema.derived[RTDepartment]

object RTEmployee:
    given Schema[RTEmployee] = Schema.derived[RTEmployee]

sealed trait Expr derives CanEqual
case class Lit(value: Int)              extends Expr derives CanEqual
case class Add(left: Expr, right: Expr) extends Expr derives CanEqual
case class Neg(inner: Expr)             extends Expr derives CanEqual

// Protobuf map/nested-wrapper test types
case class MTProtoMapHolder(name: String, scores: Map[String, Int]) derives CanEqual
case class MTListOfOption(name: String, tags: List[Option[Int]]) derives CanEqual

// Intersection-type test fixtures
trait MTIxFoo:
    def a: Int
trait MTIxBar:
    def b: String
trait MTIxEmptyMarker
case class MTIxUser(name: String, age: Int) derives CanEqual
trait MTIxT1:
    def a: Int
trait MTIxT2:
    def b: String
trait MTIxT3:
    def c: Boolean
trait MTIxHasX1:
    def x: Int
trait MTIxHasX2:
    def x: Int
case class MTIxWrapped(value: Int) derives CanEqual

// Discriminator test types
sealed trait MTStatus derives CanEqual
case object MTActive                   extends MTStatus derives CanEqual
case class MTSuspended(reason: String) extends MTStatus derives CanEqual

// =========================================================================
// Nested transform composition fixtures (folded from NestedTransformTest)
// =========================================================================

// --- Reporter's repro: discriminator on a nested sealed-trait field ---
sealed trait NestedRO derives CanEqual
object NestedRO:
    final case class `string`(value: String) extends NestedRO derives CanEqual, Schema
    final case class `number`(value: Int)    extends NestedRO derives CanEqual, Schema
end NestedRO

given Schema[NestedRO] = Schema.derived[NestedRO].discriminator("type")
final case class NestedEnvelope(result: NestedRO) derives CanEqual, Schema

// --- Two-deep nesting of the same discriminator ---
final case class NestedTwoDeepMiddle(payload: NestedRO) derives CanEqual, Schema
final case class NestedTwoDeepOuter(middle: NestedTwoDeepMiddle) derives CanEqual, Schema

// --- .drop on nested schema ---
final case class NestedDropInner(visible: String, secret: String) derives CanEqual
given Schema[NestedDropInner] = Schema[NestedDropInner].drop("secret")
final case class NestedDropOuter(inner: NestedDropInner) derives CanEqual, Schema

// --- .rename on nested schema ---
final case class NestedRenameInner(x: Int) derives CanEqual
given Schema[NestedRenameInner] = Schema[NestedRenameInner].rename("x", "y")
final case class NestedRenameOuter(inner: NestedRenameInner) derives CanEqual, Schema

// --- .add on nested schema ---
final case class NestedAddInner(x: Int) derives CanEqual
given Schema[NestedAddInner] = Schema[NestedAddInner].add("derived")(_.x * 2)
final case class NestedAddOuter(inner: NestedAddInner) derives CanEqual, Schema

// --- discriminator + drop combined on nested schema ---
// Drop applies to the case-class variants (.drop on a sealed trait is not
// supported by design); discriminator applies to the trait. Together, when
// a value is encoded through the trait inside an envelope, the wire should
// carry the discriminator AND omit the dropped variant field.
sealed trait NestedDiscDropRO derives CanEqual
object NestedDiscDropRO:
    final case class `string`(value: String, metadata: String) extends NestedDiscDropRO derives CanEqual
    final case class `number`(value: Int, metadata: String)    extends NestedDiscDropRO derives CanEqual
end NestedDiscDropRO

given Schema[NestedDiscDropRO.`string`] = Schema[NestedDiscDropRO.`string`].drop("metadata")
given Schema[NestedDiscDropRO.`number`] = Schema[NestedDiscDropRO.`number`].drop("metadata")
given Schema[NestedDiscDropRO]          = Schema.derived[NestedDiscDropRO].discriminator("type")

final case class NestedDiscDropEnvelope(result: NestedDiscDropRO) derives CanEqual, Schema

// =========================================================================
// Composition matrix fixtures (folded from CompositionMatrixTest)
// =========================================================================

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
final case class CMWrapInstant(value: java.time.Instant) derives CanEqual
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
