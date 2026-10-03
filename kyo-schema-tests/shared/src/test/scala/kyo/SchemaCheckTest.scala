package kyo

class SchemaCheckTest extends kyo.test.Test[Any]:

    given CanEqual[Any, Any] = CanEqual.derived

    "check" - {

        val person     = MTPerson("Alice", 30)
        val address    = MTAddress("123 Main St", "Portland", "97201")
        val personAddr = MTPersonAddr("Alice", 30, address)

        // --- Single field checks ---

        "check passes" in {
            val errors = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required").validate(person)
            assert(errors.isEmpty)
        }

        "check fails" in {
            val errors = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required").validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "check multiple on same field" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length < 100, "name too long")
            val errors = m.validate(person)
            assert(errors.isEmpty)
        }

        "check multiple both pass" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length < 100, "name too long")
            val errors = m.validate(MTPerson("Bob", 25))
            assert(errors.isEmpty)
        }

        "check multiple first fails" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length < 100, "name too long")
            val errors = m.validate(MTPerson("", 25))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "check multiple second fails" in {
            val longName = "x" * 101
            val m        = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length < 100, "name too long")
            val errors = m.validate(MTPerson(longName, 25))
            assert(errors.size == 1)
            assert(errors.head.message == "name too long")
        }

        "check multiple both fail" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.name)(_.length > 3, "name too short")
            val errors = m.validate(MTPerson("", 25))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "name too short"))
        }

        "check snaps back to root type" in {
            val m = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            // After check, we're back at root Schema; can focus on a different field
            val m2     = m.check(_.age)(_ > 0, "age positive")
            val errors = m2.validate(person)
            assert(errors.isEmpty)
        }

        // --- Multi-field validation ---

        "validate multi-field all pass" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 0, "age non-negative")
            val errors = m.validate(person)
            assert(errors.isEmpty)
        }

        "validate multi-field one fails" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 0, "age non-negative")
            val errors = m.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "validate multi-field both fail" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 0, "age non-negative")
            val errors = m.validate(MTPerson("", -1))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age non-negative"))
        }

        "validate collects all errors" in {
            val m = Schema[MTPersonAddr]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 18, "must be adult")
                .check(_.address.city)(_.nonEmpty, "city required")
            val errors = m.validate(MTPersonAddr("", 10, MTAddress("st", "", "z")))
            assert(errors.size == 3)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "must be adult"))
            assert(errors.exists(_.message == "city required"))
        }

        "validate empty rules" in {
            val m      = Schema[MTPerson]
            val errors = m.validate(person)
            assert(errors.isEmpty)
        }

        "validate with nested field" in {
            val m = Schema[MTPersonAddr]
                .check(_.address.city)(_.nonEmpty, "city required")
            val errors = m.validate(MTPersonAddr("Alice", 30, MTAddress("st", "", "z")))
            assert(errors.size == 1)
            assert(errors.head.path == List("address", "city"))
        }

        // --- Error structure ---

        "error has correct path" in {
            val errors = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required").validate(MTPerson("", 30))
            assert(errors.head.path == List("name"))
        }

        "error has correct message" in {
            val errors = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative").validate(MTPerson("Alice", -1))
            assert(errors.head.message == "age non-negative")
        }

        "error path for nested" in {
            val errors = Schema[MTPersonAddr]
                .check(_.address.city)(_.nonEmpty, "city required")
                .validate(MTPersonAddr("Alice", 30, MTAddress("st", "", "z")))
            assert(errors.head.path == List("address", "city"))
        }

        "error is ValidationFailedException" in {
            val errors = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required").validate(MTPerson("", 30))
            assert(errors.head.isInstanceOf[ValidationFailedException])
        }

        // --- Chain behavior ---

        "check returns root Schema type" in {
            val m1 = Schema[MTPerson]
            val m2 = m1.check(_.name)(_.nonEmpty, "name required")
            val m3 = m2.check(_.name)(_.length < 100, "name too long")
            // m2, m3 should be Schema[MTPerson] (root structural type), not Schema[MTPerson] { type Focused = String }
            val errors = m3.validate(person)
            assert(errors.isEmpty)
        }

        "check then focus get" in {
            val m = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            // After check we're at root, need to focus again to get a field
            val result = m.focus(_.name).get(person)
            assert(result == "Alice")
        }

        "check then focus set" in {
            val m = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            // After check we're at root, need to focus again to set a field
            val result = m.focus(_.name).set(person, "Bob")
            assert(result == MTPerson("Bob", 30))
        }

        "check then focus update" in {
            val m = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            // After check we're at root, need to focus again to update a field
            val result = m.focus(_.name).update(person)(_.toUpperCase)
            assert(result == MTPerson("ALICE", 30))
        }

        "focus check preserves path in error" in {
            val m      = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val errors = m.validate(MTPerson("", 30))
            assert(errors.head.path == List("name"))
        }

        "validate with separate metas via mergeChecks" in {
            val nameMeta = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val ageMeta  = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val combined = nameMeta.mergeChecks(ageMeta)
            val errors   = combined.validate(person)
            assert(errors.isEmpty)
        }

        // --- Composable focus+check ---

        "composable focus check multiple fields" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "required")
                .check(_.age)(_ > 0, "positive")
            val errors = m.validate(MTPerson("", 0))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "required"))
            assert(errors.exists(_.message == "positive"))
        }

        "composable cross-field check" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "required")
                .check(_.age)(_ > 0, "positive")
                .check(p => p.age >= 18 || p.name.nonEmpty, "need name or be adult")
            val errors = m.validate(MTPerson("Alice", 5))
            assert(errors.isEmpty)
            val errors2 = m.validate(MTPerson("", 5))
            assert(errors2.exists(_.message == "required"))
            assert(errors2.exists(_.message == "need name or be adult"))
        }

        "composable check then validate" in {
            val m = Schema[MTPersonAddr]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ >= 18, "must be adult")
                .check(_.address.city)(_.nonEmpty, "city required")
            // All pass
            val errors = m.validate(personAddr)
            assert(errors.isEmpty)
            // All fail
            val errors2 = m.validate(MTPersonAddr("", 10, MTAddress("st", "", "z")))
            assert(errors2.size == 3)
        }

        "composable check then transform" in {
            val m = Schema[MTUser]
                .check(_.name)(_.nonEmpty, "name required")
                .check(_.age)(_ > 0, "positive")
                .drop("ssn")
            // Checks survive transforms
            val errors = m.validate(MTUser("", 0, "alice@test.com", "123"))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "positive"))
        }

        "composable focus check then get" in {
            val m = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "required")
            // m is back at root, can focus again
            val result = m.focus(_.name).get(person)
            assert(result == "Alice")
        }

        "existing focus get still works" in {
            val result = Schema[MTPerson].focus(_.name).get(person)
            assert(result == "Alice")
        }

        "existing focus set still works" in {
            val result = Schema[MTPerson].focus(_.name).set(person, "Bob")
            assert(result == MTPerson("Bob", 30))
        }

        "cross-field check with Option field None passes" in {
            val m = Schema[MTRegistration]
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("alice", 25, "a@b.com", None))
            assert(errors.isEmpty)
        }

        "cross-field check with Option field Some valid passes" in {
            val m = Schema[MTRegistration]
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("alice", 25, "a@b.com", Some("ABCD1234")))
            assert(errors.isEmpty)
        }

        "cross-field check with Option field Some invalid fails" in {
            val m = Schema[MTRegistration]
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("alice", 25, "a@b.com", Some("short")))
            assert(errors.size == 1)
            assert(errors.head.message == "referral code must be 8 chars")
        }

        "chain 4 field checks plus 1 cross-field all fail" in {
            val m = Schema[MTRegistration]
                .check(_.username)(_.nonEmpty, "username required")
                .check(_.username)(_.length <= 20, "username too long")
                .check(_.age)(_ >= 13, "must be 13+")
                .check(_.email)(_.contains("@"), "invalid email")
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("", 10, "bad", Some("short")))
            assert(errors.size == 4)
            assert(errors.exists(_.message == "username required"))
            assert(errors.exists(_.message == "must be 13+"))
            assert(errors.exists(_.message == "invalid email"))
            assert(errors.exists(_.message == "referral code must be 8 chars"))
        }

        "chain 4 field checks plus 1 cross-field all pass" in {
            val m = Schema[MTRegistration]
                .check(_.username)(_.nonEmpty, "username required")
                .check(_.username)(_.length <= 20, "username too long")
                .check(_.age)(_ >= 13, "must be 13+")
                .check(_.email)(_.contains("@"), "invalid email")
                .check(r => r.referralCode.forall(_.length == 8), "referral code must be 8 chars")
            val errors = m.validate(MTRegistration("alice", 25, "a@b.com", None))
            assert(errors.isEmpty)
        }

        "validator is reusable across multiple instances" in {
            val m = Schema[MTRegistration]
                .check(_.username)(_.nonEmpty, "username required")
                .check(_.age)(_ >= 13, "must be 13+")
            val errors1 = m.validate(MTRegistration("", 10, "a@b.com", None))
            assert(errors1.size == 2)
            val errors2 = m.validate(MTRegistration("alice", 25, "a@b.com", None))
            assert(errors2.isEmpty)
        }

        // --- mergeChecks ---

        "mergeChecks combines checks from two metas" in {
            val nameChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val ageChecks  = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val combined   = nameChecks.mergeChecks(ageChecks)
            val errors     = combined.validate(MTPerson("", -1))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age non-negative"))
        }

        "mergeChecks all pass" in {
            val nameChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val ageChecks  = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val combined   = nameChecks.mergeChecks(ageChecks)
            val errors     = combined.validate(person)
            assert(errors.isEmpty)
        }

        "mergeChecks with no checks in other" in {
            val withChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val noChecks   = Schema[MTPerson]
            val combined   = withChecks.mergeChecks(noChecks)
            val errors     = combined.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "mergeChecks with no checks in this" in {
            val noChecks   = Schema[MTPerson]
            val withChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val combined   = noChecks.mergeChecks(withChecks)
            val errors     = combined.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
        }

        "mergeChecks preserves structure for focus" in {
            val nameChecks = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val ageChecks  = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val combined   = nameChecks.mergeChecks(ageChecks)
            // After mergeChecks, focus still works
            val result = combined.focus(_.name).get(person)
            assert(result == "Alice")
        }

        "mergeChecks with cross-field checks" in {
            val fieldChecks = Schema[MTPerson]
                .check(_.name)(_.nonEmpty, "name required")
            val crossChecks = Schema[MTPerson]
                .check(p => p.age >= 18 || p.name.nonEmpty, "need name or be adult")
            val combined = fieldChecks.mergeChecks(crossChecks)
            val errors   = combined.validate(MTPerson("", 5))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "need name or be adult"))
        }

        "mergeChecks from separate modules" in {
            val nameChecks = Schema[MTRegistration].check(_.username)(_.nonEmpty, "required")
            val ageChecks  = Schema[MTRegistration].check(_.age)(_ >= 13, "must be 13+")
            val combined   = nameChecks.mergeChecks(ageChecks)
            val errors     = combined.validate(MTRegistration("", 10, "a@b.com", None))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "required"))
            assert(errors.exists(_.message == "must be 13+"))
        }

        "mergeChecks chain three metas" in {
            val m1       = Schema[MTPerson].check(_.name)(_.nonEmpty, "name required")
            val m2       = Schema[MTPerson].check(_.age)(_ >= 0, "age non-negative")
            val m3       = Schema[MTPerson].check(_.age)(_ < 200, "age too large")
            val combined = m1.mergeChecks(m2).mergeChecks(m3)
            val errors   = combined.validate(MTPerson("", -1))
            assert(errors.size == 2)
            assert(errors.exists(_.message == "name required"))
            assert(errors.exists(_.message == "age non-negative"))
            // age too large should pass since -1 < 200
            val errors2 = combined.validate(MTPerson("Alice", 300))
            assert(errors2.size == 1)
            assert(errors2.head.message == "age too large")
        }

        "check on Option field with None skips" in {
            val m = Schema[MTOptional]
                .check(_.nickname)(_.nonEmpty, "nickname must be present")
            // With None, the Option is empty; check predicate receives None which is !nonEmpty
            val errorsNone = m.validate(MTOptional("Alice", None))
            // With Some, the Option is non-empty
            val errorsSome = m.validate(MTOptional("Alice", Some("Ali")))
            assert(errorsSome.isEmpty)
            // None.nonEmpty is false, so the check should fail
            assert(errorsNone.size == 1)
            assert(errorsNone.head.message == "nickname must be present")
        }

        "very long error message preserved" in {
            val longMsg = "x" * 500
            val m       = Schema[MTPerson].check(_.name)(_.nonEmpty, longMsg)
            val errors  = m.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == longMsg)
            assert(errors.head.message.length == 500)
        }

        "check predicate that throws propagates exception" in {
            val m = Schema[MTPerson].check(_.name)(_ => throw new RuntimeException("boom"), "unreachable")
            interceptThrown[RuntimeException] {
                m.validate(MTPerson("Alice", 30))
            }
        }

        // --- Reusable validation rules ---

        "same predicate on different types both report invalid email" in {
            val validEmail: String => Boolean = _.contains("@")
            val userValidator                 = Schema[MTUser]
                .check(_.email)(validEmail, "invalid email")
            val contactValidator = Schema[MTContact]
                .check(_.email)(validEmail, "invalid email")
            val userErrors    = userValidator.validate(MTUser("Alice", 30, "bad", "123"))
            val contactErrors = contactValidator.validate(MTContact("Alice", "bad", "555-0100"))
            assert(userErrors.size == 1)
            assert(userErrors.head.message == "invalid email")
            assert(contactErrors.size == 1)
            assert(contactErrors.head.message == "invalid email")
        }

        "error paths correct for both types" in {
            val validEmail: String => Boolean = _.contains("@")
            val userValidator                 = Schema[MTUser]
                .check(_.email)(validEmail, "invalid email")
            val contactValidator = Schema[MTContact]
                .check(_.email)(validEmail, "invalid email")
            val userErrors    = userValidator.validate(MTUser("Alice", 30, "bad", "123"))
            val contactErrors = contactValidator.validate(MTContact("Alice", "bad", "555-0100"))
            assert(userErrors.head.path == List("email"))
            assert(contactErrors.head.path == List("email"))
        }

        "shared predicate val applied to check" in {
            val nonEmpty: String => Boolean = _.nonEmpty
            val m                           = Schema[MTPerson]
                .check(_.name)(nonEmpty, "name required")
            val errors = m.validate(MTPerson("", 30))
            assert(errors.size == 1)
            assert(errors.head.message == "name required")
            val noErrors = m.validate(MTPerson("Alice", 30))
            assert(noErrors.isEmpty)
        }

        "validate with no checks returns empty" in {
            val errors = Schema[MTPerson].validate(MTPerson("", -1))
            assert(errors.isEmpty)
        }

        "separate validators can have different check sets" in {
            val nonEmpty: String => Boolean   = _.nonEmpty
            val validEmail: String => Boolean = _.contains("@")
            val userValidator                 = Schema[MTUser]
                .check(_.name)(nonEmpty, "name required")
                .check(_.email)(validEmail, "invalid email")
                .check(_.age)(_ >= 0, "age non-negative")
            val contactValidator = Schema[MTContact]
                .check(_.name)(nonEmpty, "name required")
                .check(_.email)(validEmail, "invalid email")
                .check(_.phone)(nonEmpty, "phone required")
            // User has 3 checks: name, email, age
            val userErrors = userValidator.validate(MTUser("", 30, "bad", "123"))
            assert(userErrors.size == 2) // name required, invalid email
            // Contact has 3 checks: name, email, phone
            val contactErrors = contactValidator.validate(MTContact("", "bad", ""))
            assert(contactErrors.size == 3) // name required, invalid email, phone required
            assert(contactErrors.exists(_.message == "name required"))
            assert(contactErrors.exists(_.message == "invalid email"))
            assert(contactErrors.exists(_.message == "phone required"))
        }
    }

end SchemaCheckTest
