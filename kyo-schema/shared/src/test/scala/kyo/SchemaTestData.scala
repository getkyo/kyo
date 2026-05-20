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

// Discriminator test types
sealed trait MTStatus derives CanEqual
case object MTActive                   extends MTStatus derives CanEqual
case class MTSuspended(reason: String) extends MTStatus derives CanEqual
