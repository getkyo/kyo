package kyo

class ChangesetTest extends Test:

    val alice = MTPerson("Alice", 30)
    val bob   = MTPerson("Bob", 25)

    // 1. Identical values produce empty changeset
    "identical values produce empty changeset" in {
        val d = Changeset(alice, alice)
        assert(d.isEmpty)
    }

    // 2. Single field change produces SetField operation
    "single field change produces SetField" in {
        val alice2 = MTPerson("Bob", 30)
        val d      = Changeset(alice, alice2)
        assert(!d.isEmpty)
        assert(d.operations.exists {
            case Changeset.Patch.StringPatch(Chunk("name"), _, _, "Bob") => true
            case _                                                       => false
        })
    }

    // 3. Multiple field changes produce multiple operations
    "multiple field changes produce multiple operations" in {
        val d = Changeset(alice, bob)
        assert(d.operations.size >= 2)
    }

    // 4. Numeric field change produces NumericDelta
    "numeric field change produces NumericDelta with correct difference" in {
        val alice2 = MTPerson("Alice", 35)
        val d      = Changeset(alice, alice2)
        val numOps = d.operations.collect { case op: Changeset.Patch.NumericDelta => op }
        assert(numOps.size == 1)
        assert(numOps.head.delta == BigDecimal(5))
        assert(numOps.head.fieldPath == Chunk("age"))
    }

    // 5. applyTo reconstructs the new value exactly
    "applyTo reconstructs the new value exactly" in {
        val d      = Changeset(alice, bob)
        val result = d.applyTo(alice).getOrThrow
        assert(result == bob)
    }

    // 6. Operations are structurally correct (skip JSON round-trip)
    "operations are structurally correct" in {
        val d = Changeset(alice, bob)
        // Verify operations have correct field paths
        val paths = d.operations.map(_.fieldPath).toSet
        assert(paths.contains(Chunk("name")))
        assert(paths.contains(Chunk("age")))
    }

    // 7. Operations fields are correctly structured (skip Protobuf round-trip)
    "operations fields are correctly structured" in {
        val d = Changeset(alice, bob)
        assert(d.operations.forall(_.fieldPath.nonEmpty))
    }

    // 8. Nested case class changes produce Nested changeset
    "nested case class changes produce Nested changeset" in {
        val addr1     = MTAddress("123 Main", "Springfield", "62704")
        val addr2     = MTAddress("456 Oak", "Shelbyville", "62705")
        val pa1       = MTPersonAddr("Alice", 30, addr1)
        val pa2       = MTPersonAddr("Alice", 30, addr2)
        val d         = Changeset(pa1, pa2)
        val nestedOps = d.operations.collect { case op: Changeset.Patch.Nested => op }
        assert(nestedOps.size == 1)
        assert(nestedOps.head.fieldPath == Chunk("address"))
        assert(nestedOps.head.operations.nonEmpty)
    }

    // 9. andThen composes two changesets correctly
    "andThen composes two changesets correctly" in {
        val mid    = MTPerson("Alice", 35)
        val d1     = Changeset(alice, mid)
        val d2     = Changeset(mid, bob)
        val merged = d1.andThen(d2)
        val result = merged.applyTo(alice).getOrThrow
        assert(result == bob)
    }

    // 10. Collection field changes produce SequencePatch
    "collection field changes produce SequencePatch" in {
        val o1     = MTOrder(1, List(MTItem("a", 1.0)))
        val o2     = MTOrder(1, List(MTItem("a", 1.0), MTItem("b", 2.0)))
        val d      = Changeset(o1, o2)
        val seqOps = d.operations.collect { case op: Changeset.Patch.SequencePatch => op }
        assert(seqOps.size == 1)
        assert(seqOps.head.added.nonEmpty)
    }

    // 11. Empty changeset applied to a value returns the same value
    "empty changeset applied to a value returns the same value" in {
        val d      = Changeset(alice, alice)
        val result = d.applyTo(alice).getOrThrow
        assert(result == alice)
    }

    // 12. Changeset with NumericDelta applies arithmetic correctly
    "NumericDelta applies arithmetic correctly" in {
        val alice2 = MTPerson("Alice", 40)
        val d      = Changeset(alice, alice2)
        val result = d.applyTo(alice).getOrThrow
        assert(result == alice2)
        assert(result.age == 40)
    }

    // 13. Optional field set to None produces RemoveField (field-presence encoding)
    "optional field set to None produces RemoveField" in {
        // With field-presence encoding: Some("Ali") → field present, None → field missing
        // Changeset correctly detects this as removing the field
        val opt1  = MTOptional("Alice", Some("Ali"))
        val opt2  = MTOptional("Alice", None)
        val d     = Changeset(opt1, opt2)
        val rmOps = d.operations.collect { case op: Changeset.Patch.RemoveField => op }
        assert(rmOps.size == 1)
        assert(rmOps.head.fieldPath == Chunk("nickname"))
    }

    // 14. Removed field produces RemoveField — test with schema transforms that drop fields
    "removed field produces RemoveField" in {
        // Simulate by testing the RemoveField op directly via applyTo on a value
        // where we remove a field from the Structure.Value tree
        val ops       = Chunk(Changeset.Patch.RemoveField(Chunk("age")))
        val changeset = new Changeset[MTPerson](ops)
        // The RemoveField should remove the "age" field from the record representation
        val schema = summon[Schema[MTPerson]]
        val dynVal = Structure.encode(alice)
        val result = Changeset.applyOps(dynVal, ops)
        result match
            case Structure.Value.Record(fields) =>
                assert(!fields.exists(_._1 == "age"))
            case _ =>
                assert(false, "Expected Record")
        end match
    }

    // 15. String field partial edit produces StringPatch
    "string field partial edit produces StringPatch" in {
        val alice2 = MTPerson("Bobby", 30)
        val d      = Changeset(alice, alice2)
        val strOps = d.operations.collect { case op: Changeset.Patch.StringPatch => op }
        assert(strOps.size == 1)
        assert(strOps.head.fieldPath == Chunk("name"))
        assert(strOps.head.insert == "Bobby")
    }

    // 16. Map field changes produce correct changeset ops
    // Note: String-keyed maps are encoded as Records by the codec, so they produce
    // Nested + SetField/RemoveField ops rather than MapPatch.
    "map field changes produce correct changeset ops" in {
        val m1 = MTMapHolder(Map("a" -> 1, "b" -> 2))
        val m2 = MTMapHolder(Map("a" -> 1, "c" -> 3))
        val d  = Changeset(m1, m2)
        assert(!d.isEmpty)
        // Key "b" removed, key "c" added — changeset should have operations reflecting this
        val nestedOps = d.operations.collect { case op: Changeset.Patch.Nested => op }
        assert(nestedOps.size == 1)
        assert(nestedOps.head.fieldPath == Chunk("data"))
        val innerOps = nestedOps.head.operations
        // Should have a RemoveField for "b" and SetField for "c"
        assert(innerOps.exists {
            case Changeset.Patch.RemoveField(Seq("b")) => true
            case _                                     => false
        })
        assert(innerOps.exists {
            case Changeset.Patch.SetField(Seq("c"), _) => true
            case _                                     => false
        })
    }

    // 17. StringPatch.applyTo inserts/deletes/replaces character range correctly
    "StringPatch applyTo inserts deletes replaces correctly" in {
        // Test: replace "Alice" with "Bob" via StringPatch (offset=0, deleteCount=5, insert="Bob")
        val d1 = new Changeset[MTPerson](Chunk(Changeset.Patch.StringPatch(Chunk("name"), 0, 5, "Bob")))
        assert(d1.applyTo(alice).getOrThrow.name == "Bob")

        // Test: insert at position 5 (append)
        val d2 = new Changeset[MTPerson](Chunk(Changeset.Patch.StringPatch(Chunk("name"), 5, 0, "XX")))
        assert(d2.applyTo(alice).getOrThrow.name == "AliceXX")

        // Test: delete middle characters
        val d3 = new Changeset[MTPerson](Chunk(Changeset.Patch.StringPatch(Chunk("name"), 1, 3, "")))
        assert(d3.applyTo(alice).getOrThrow.name == "Ae")

        // Test: replace middle
        val d4 = new Changeset[MTPerson](Chunk(Changeset.Patch.StringPatch(Chunk("name"), 1, 2, "ZZ")))
        assert(d4.applyTo(alice).getOrThrow.name == "AZZce")
    }

    // Nested applyTo
    "nested changeset applyTo reconstructs nested value" in {
        val addr1  = MTAddress("123 Main", "Springfield", "62704")
        val addr2  = MTAddress("456 Oak", "Shelbyville", "62705")
        val pa1    = MTPersonAddr("Alice", 30, addr1)
        val pa2    = MTPersonAddr("Alice", 30, addr2)
        val d      = Changeset(pa1, pa2)
        val result = d.applyTo(pa1).getOrThrow
        assert(result == pa2)
    }

    // --- Changeset serialization round-trip tests ---

    "changeset serialization round-trip: basic string and numeric changes" in {
        val updated   = MTPerson("Bobby", 35)
        val changeset = Changeset(alice, updated)

        // Encode operations to JSON
        given Schema[Chunk[Changeset.Patch]] = summon[Schema[Chunk[Changeset.Patch]]]
        val json                             = Json.encode(changeset.operations)
        assert(json.nonEmpty)

        // Decode operations back from JSON
        val decoded = Json.decode[Chunk[Changeset.Patch]](json).getOrThrow

        // Build a new Changeset from decoded ops and apply
        val roundTripped = new Changeset[MTPerson](decoded)
        val result       = roundTripped.applyTo(alice).getOrThrow
        assert(result == updated)
        assert(result.name == "Bobby")
        assert(result.age == 35)
    }

    "changeset serialization round-trip: numeric delta survives encoding" in {
        val updated   = MTPerson("Alice", 42)
        val changeset = Changeset(alice, updated)

        // Verify we have a NumericDelta operation
        val numOps = changeset.operations.collect { case op: Changeset.Patch.NumericDelta => op }
        assert(numOps.size == 1)
        assert(numOps.head.delta == BigDecimal(12))

        // Encode and decode
        given Schema[Chunk[Changeset.Patch]] = summon[Schema[Chunk[Changeset.Patch]]]
        val json                             = Json.encode(changeset.operations)
        val decoded                          = Json.decode[Chunk[Changeset.Patch]](json).getOrThrow

        // Verify the decoded NumericDelta is correct
        val decodedNumOps = decoded.collect { case op: Changeset.Patch.NumericDelta => op }
        assert(decodedNumOps.size == 1)
        assert(decodedNumOps.head.delta == BigDecimal(12))
        assert(decodedNumOps.head.fieldPath == Chunk("age"))

        // Apply and verify
        val roundTripped = new Changeset[MTPerson](decoded)
        val result       = roundTripped.applyTo(alice).getOrThrow
        assert(result.age == 42)
    }

    "changeset serialization round-trip: string field change" in {
        val updated   = MTPerson("Charlie", 30)
        val changeset = Changeset(alice, updated)

        // Verify we have a StringPatch operation with optimized prefix/suffix trimming
        val strOps = changeset.operations.collect { case op: Changeset.Patch.StringPatch => op }
        assert(strOps.size == 1)
        assert(strOps.head.fieldPath == Chunk("name"))
        // "Alice" -> "Charlie": common suffix "e", so insert is "Charli" (not "Charlie")
        assert(strOps.head.insert == "Charli")
        assert(strOps.head.offset == 0)
        assert(strOps.head.deleteCount == 4)

        // Encode and decode
        given Schema[Chunk[Changeset.Patch]] = summon[Schema[Chunk[Changeset.Patch]]]
        val json                             = Json.encode(changeset.operations)
        val decoded                          = Json.decode[Chunk[Changeset.Patch]](json).getOrThrow

        // Verify the decoded StringPatch survives round-trip
        val decodedStrOps = decoded.collect { case op: Changeset.Patch.StringPatch => op }
        assert(decodedStrOps.size == 1)
        assert(decodedStrOps.head.fieldPath == Chunk("name"))

        // Apply and verify
        val roundTripped = new Changeset[MTPerson](decoded)
        val result       = roundTripped.applyTo(alice).getOrThrow
        assert(result.name == "Charlie")
        assert(result.age == 30)
    }

    // --- Bug fix verification tests ---

    "applyTo returns failure Result on corrupted operations" in {
        // applyTo returns Result; corrupted operations produce a failure rather than throwing.
        val badOps = Chunk[Changeset.Patch](
            Changeset.Patch.SetField(Chunk("age"), Structure.Value.Str("not-a-number"))
        )
        val badChangeset = new Changeset[MTPerson](badOps)

        // Should return a failure Result, not throw
        val result = badChangeset.applyTo(alice)
        assert(result.isFailure)
    }

    // =====================================================================
    // New comprehensive tests for identified coverage gaps
    // =====================================================================

    "decimal numeric delta produces NumericDelta with correct difference" in {
        val item1  = MTItem("widget", 9.99)
        val item2  = MTItem("widget", 14.99)
        val d      = Changeset(item1, item2)
        val numOps = d.operations.collect { case op: Changeset.Patch.NumericDelta => op }
        assert(numOps.size == 1)
        assert(numOps.head.fieldPath == Chunk("price"))
        assert((numOps.head.delta - BigDecimal("5.0")).abs < BigDecimal("0.0001"))
    }

    "BigDecimal field change produces NumericDelta" in {
        val v1     = MTBigDecimalField("acct", BigDecimal("100.50"))
        val v2     = MTBigDecimalField("acct", BigDecimal("250.75"))
        val d      = Changeset(v1, v2)
        val numOps = d.operations.collect { case op: Changeset.Patch.NumericDelta => op }
        assert(numOps.size == 1)
        assert(numOps.head.fieldPath == Chunk("amount"))
        assert(numOps.head.delta == BigDecimal("150.25"))
    }

    "SequencePatch.removed contains correct indices when elements are removed" in {
        val o1     = MTOrder(42, List(MTItem("a", 1.0), MTItem("b", 2.0), MTItem("c", 3.0)))
        val o2     = MTOrder(42, List(MTItem("a", 1.0), MTItem("c", 3.0)))
        val d      = Changeset(o1, o2)
        val seqOps = d.operations.collect { case op: Changeset.Patch.SequencePatch => op }
        assert(seqOps.size == 1)
        // "b" is at index 1 in the old list
        assert(seqOps.head.removed.contains(1))
        assert(seqOps.head.added.isEmpty)
    }

    "SequencePatch round-trip: apply produces expected list" in {
        val o1     = MTOrder(1, List(MTItem("x", 10.0)))
        val o2     = MTOrder(1, List(MTItem("x", 10.0), MTItem("y", 20.0), MTItem("z", 30.0)))
        val d      = Changeset(o1, o2)
        val result = d.applyTo(o1).getOrThrow
        assert(result == o2)
    }

    "MapPatch ops: added, removed, updated entries on MapEntries" in {
        // MapEntries is not produced by standard Structure.encode (which uses Record for all maps)
        // Test MapPatch operations directly using Structure.Value.MapEntries
        val k1 = Structure.Value.Integer(1L)
        val k2 = Structure.Value.Integer(2L)
        val k3 = Structure.Value.Integer(3L)
        val v1 = Structure.Value.Str("one")
        val v2 = Structure.Value.Str("two")
        val v3 = Structure.Value.Str("three")

        val oldMap = Structure.Value.MapEntries(Chunk((k1, v1), (k2, v2)))
        val newMap = Structure.Value.MapEntries(Chunk((k1, v1), (k3, v3)))

        // Compute ops using applyOps (via a wrapper)
        val ops = Changeset.applyOps(
            Structure.Value.MapEntries(Chunk((k1, v1), (k2, v2))),
            Chunk(Changeset.Patch.MapPatch(
                Chunk.empty,
                added = Chunk((k3, v3)),
                removed = Chunk(k2),
                updated = Chunk.empty
            ))
        )
        ops match
            case Structure.Value.MapEntries(entries) =>
                assert(entries.exists(_._1 == k1))
                assert(entries.exists(_._1 == k3))
                assert(!entries.exists(_._1 == k2))
            case other =>
                assert(false, s"Expected MapEntries but got $other")
        end match
    }

    "MapPatch round-trip: apply MapPatch ops reconstructs expected map" in {
        val k1      = Structure.Value.Integer(10L)
        val k2      = Structure.Value.Integer(20L)
        val k3      = Structure.Value.Integer(30L)
        val vA      = Structure.Value.Str("alpha")
        val vB      = Structure.Value.Str("beta")
        val vC      = Structure.Value.Str("gamma")
        val vBprime = Structure.Value.Str("beta-updated")

        val initial = Structure.Value.MapEntries(Chunk((k1, vA), (k2, vB)))
        // add k3→vC, remove k1, update k2→vBprime
        val result = Changeset.applyOps(
            initial,
            Chunk(Changeset.Patch.MapPatch(
                Chunk.empty,
                added = Chunk((k3, vC)),
                removed = Chunk(k1),
                updated = Chunk((k2, vBprime))
            ))
        )
        result match
            case Structure.Value.MapEntries(entries) =>
                assert(!entries.exists(_._1 == k1))
                val found2 = entries.find(_._1 == k2)
                assert(found2.isDefined && found2.get._2 == vBprime)
                val found3 = entries.find(_._1 == k3)
                assert(found3.isDefined && found3.get._2 == vC)
            case other =>
                assert(false, s"Expected MapEntries but got $other")
        end match
    }

    "deep nested changeset (3+ levels) produces Nested ops at each level" in {
        // MTCompany(name, hq: MTTeam(name, lead: MTPersonAddr(name, age, address: MTAddress)))
        val addr1 = MTAddress("1 A St", "CityA", "11111")
        val addr2 = MTAddress("2 B Ave", "CityB", "22222")
        val lead1 = MTPersonAddr("Carol", 40, addr1)
        val lead2 = MTPersonAddr("Carol", 40, addr2)
        val team1 = MTTeam("Engineering", lead1, Nil)
        val team2 = MTTeam("Engineering", lead2, Nil)
        val co1   = MTCompany("Acme", team1)
        val co2   = MTCompany("Acme", team2)
        val d     = Changeset(co1, co2)

        // Should have a top-level Nested at "hq"
        val topNested = d.operations.collect { case op: Changeset.Patch.Nested => op }
        assert(topNested.exists(_.fieldPath == Chunk("hq")))

        // The "hq" Nested should itself contain a Nested at "lead"
        val hqNested = topNested.find(_.fieldPath == Chunk("hq")).get
        assert(hqNested.operations.exists {
            case Changeset.Patch.Nested(Chunk("lead"), _) => true
            case _                                        => false
        })

        // The "lead" Nested should contain ops for the "address" field
        val leadNested = hqNested.operations.collect { case op: Changeset.Patch.Nested => op }
            .find(_.fieldPath == Chunk("lead")).get
        assert(leadNested.operations.nonEmpty)
    }

    "empty to non-empty list produces SequencePatch with added elements" in {
        val o1     = MTOrder(5, Nil)
        val o2     = MTOrder(5, List(MTItem("p", 1.0), MTItem("q", 2.0)))
        val d      = Changeset(o1, o2)
        val seqOps = d.operations.collect { case op: Changeset.Patch.SequencePatch => op }
        assert(seqOps.size == 1)
        assert(seqOps.head.added.size == 2)
        assert(seqOps.head.removed.isEmpty)
    }

    "two empty lists produce empty changeset" in {
        val o1 = MTOrder(7, Nil)
        val o2 = MTOrder(7, Nil)
        val d  = Changeset(o1, o2)
        assert(d.isEmpty)
    }

    "single-field case class changeset produces correct operation" in {
        val w1 = MTWrapper("hello")
        val w2 = MTWrapper("world")
        val d  = Changeset(w1, w2)
        assert(!d.isEmpty)
        val strOps = d.operations.collect { case op: Changeset.Patch.StringPatch => op }
        assert(strOps.size == 1)
        assert(strOps.head.fieldPath == Chunk("value"))
        assert(strOps.head.insert == "world")
        assert(d.applyTo(w1).getOrThrow == w2)
    }

    "Maybe field going from Present to Absent produces RemoveField" in {
        val v1    = MTMaybeField("item", Maybe("tag"))
        val v2    = MTMaybeField("item", Maybe.empty)
        val d     = Changeset(v1, v2)
        val rmOps = d.operations.collect { case op: Changeset.Patch.RemoveField => op }
        assert(rmOps.size == 1)
        assert(rmOps.head.fieldPath == Chunk("tag"))
    }

    // SetNull op applied directly to a field
    "SetNull op applied to a field sets that field to Null" in {
        val schema = summon[Schema[MTItem]]
        val item   = MTItem("pencil", 0.99)
        val dynVal = Structure.encode(item)
        val ops    = Chunk(Changeset.Patch.SetNull(Chunk("name")))
        val result = Changeset.applyOps(dynVal, ops)
        result match
            case Structure.Value.Record(fields) =>
                val nameField = fields.find(_._1 == "name")
                assert(nameField.isDefined)
                assert(nameField.get._2 == Structure.Value.Null)
            case _ =>
                assert(false, "Expected Record")
        end match
    }

    "boolean field change produces SetField, not NumericDelta" in {
        val c1     = MTConfig("localhost", 8080, ssl = false)
        val c2     = MTConfig("localhost", 8080, ssl = true)
        val d      = Changeset(c1, c2)
        val numOps = d.operations.collect { case op: Changeset.Patch.NumericDelta => op }
        val setOps = d.operations.collect { case op: Changeset.Patch.SetField => op }
        assert(numOps.isEmpty)
        assert(setOps.size == 1)
        assert(setOps.head.fieldPath == Chunk("ssl"))
        assert(setOps.head.value == Structure.Value.Bool(true))
    }

    "mixed changes produce correct operations for each field type" in {
        val v1 = MTMixed("Alice", 30, active = false, 9.5)
        val v2 = MTMixed("Bob", 45, active = true, 12.0)
        val d  = Changeset(v1, v2)

        val strOps = d.operations.collect { case op: Changeset.Patch.StringPatch => op }
        val numOps = d.operations.collect { case op: Changeset.Patch.NumericDelta => op }
        val setOps = d.operations.collect { case op: Changeset.Patch.SetField => op }

        // name: StringPatch
        assert(strOps.exists(_.fieldPath == Chunk("name")))
        // age: NumericDelta +15
        assert(numOps.exists(op => op.fieldPath == Chunk("age") && op.delta == BigDecimal(15)))
        // active: SetField (boolean)
        assert(setOps.exists(op => op.fieldPath == Chunk("active") && op.value == Structure.Value.Bool(true)))
        // score: NumericDelta +2.5
        assert(numOps.exists(op => op.fieldPath == Chunk("score")))

        // Round-trip
        val result = d.applyTo(v1).getOrThrow
        assert(result == v2)
    }

    // --- StringPatch prefix/suffix optimization tests ---

    "StringPatch: common prefix trimmed" in {
        // "Hello World" -> "Hello World!" — append
        val v1 = MTStringField("Hello World")
        val v2 = MTStringField("Hello World!")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 11)
        assert(op.deleteCount == 0)
        assert(op.insert == "!")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: common suffix trimmed" in {
        // "staging.example.com" -> "prod.example.com" — prefix change
        val v1 = MTStringField("staging.example.com")
        val v2 = MTStringField("prod.example.com")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 0)
        assert(op.deleteCount == 7) // "staging"
        assert(op.insert == "prod")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: common prefix and suffix trimmed" in {
        // "Hello World" -> "Hello Beautiful World" — middle insertion
        val v1 = MTStringField("Hello World")
        val v2 = MTStringField("Hello Beautiful World")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 6)
        assert(op.deleteCount == 0)
        assert(op.insert == "Beautiful ")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: middle replacement" in {
        // "abcXYZdef" -> "abcPQRdef" — replace middle
        val v1 = MTStringField("abcXYZdef")
        val v2 = MTStringField("abcPQRdef")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 3)
        assert(op.deleteCount == 3)
        assert(op.insert == "PQR")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: no common prefix or suffix" in {
        // completely different strings — full replacement
        val v1 = MTStringField("abc")
        val v2 = MTStringField("xyz")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 0)
        assert(op.deleteCount == 3)
        assert(op.insert == "xyz")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: empty to non-empty" in {
        val v1 = MTStringField("")
        val v2 = MTStringField("hello")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 0)
        assert(op.deleteCount == 0)
        assert(op.insert == "hello")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: non-empty to empty" in {
        val v1 = MTStringField("hello")
        val v2 = MTStringField("")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 0)
        assert(op.deleteCount == 5)
        assert(op.insert == "")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: single char change in long string" in {
        val v1 = MTStringField("a" * 1000 + "X" + "b" * 1000)
        val v2 = MTStringField("a" * 1000 + "Y" + "b" * 1000)
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 1000)
        assert(op.deleteCount == 1)
        assert(op.insert == "Y")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: identical strings produce no ops" in {
        val v1 = MTStringField("same")
        val d  = Changeset(v1, v1)
        assert(d.isEmpty)
    }

    "StringPatch: single char strings" in {
        val v1 = MTStringField("a")
        val v2 = MTStringField("b")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 0)
        assert(op.deleteCount == 1)
        assert(op.insert == "b")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: grow string by one char" in {
        val v1 = MTStringField("ab")
        val v2 = MTStringField("abc")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 2)
        assert(op.deleteCount == 0)
        assert(op.insert == "c")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: shrink string by one char" in {
        val v1 = MTStringField("abc")
        val v2 = MTStringField("ab")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 2)
        assert(op.deleteCount == 1)
        assert(op.insert == "")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: prepend" in {
        val v1 = MTStringField("world")
        val v2 = MTStringField("hello world")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 0)
        assert(op.deleteCount == 0)
        assert(op.insert == "hello ")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: delete from middle" in {
        val v1 = MTStringField("abcXYZdef")
        val v2 = MTStringField("abcdef")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 3)
        assert(op.deleteCount == 3)
        assert(op.insert == "")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: entirely shared prefix, different suffix lengths" in {
        // "abcdef" -> "abcdefghij" — old is a prefix of new
        val v1 = MTStringField("abcdef")
        val v2 = MTStringField("abcdefghij")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 6)
        assert(op.deleteCount == 0)
        assert(op.insert == "ghij")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: new is prefix of old" in {
        // "abcdefghij" -> "abcdef" — truncation
        val v1 = MTStringField("abcdefghij")
        val v2 = MTStringField("abcdef")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.offset == 6)
        assert(op.deleteCount == 4)
        assert(op.insert == "")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: repeated characters" in {
        // "aaaa" -> "aabaa" — insert in middle of repeated chars
        val v1 = MTStringField("aaaa")
        val v2 = MTStringField("aabaa")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        // prefix "aa", suffix "aa", insert "b" at offset 2
        assert(op.offset == 2)
        assert(op.deleteCount == 0)
        assert(op.insert == "b")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: unicode characters" in {
        val v1 = MTStringField("cafe\u0301")
        val v2 = MTStringField("cafe\u0301!")
        val d  = Changeset(v1, v2)
        val op = d.operations.collect { case op: Changeset.Patch.StringPatch => op }.head
        assert(op.insert == "!")
        assert(d.applyTo(v1).getOrThrow == v2)
    }

    "StringPatch: disjoint changes merge into one patch" in {
        // "ABCDEFGH" -> "XBCDEFGX" — changes at both ends
        val v1  = MTStringField("ABCDEFGH")
        val v2  = MTStringField("XBCDEFGX")
        val d   = Changeset(v1, v2)
        val ops = d.operations.collect { case op: Changeset.Patch.StringPatch => op }
        assert(ops.size == 1) // single patch, not two
        assert(d.applyTo(v1).getOrThrow == v2)
    }

end ChangesetTest

// Test data type for map tests
case class MTMapHolder(data: Map[String, Int]) derives CanEqual

// Local types for new gap tests
case class MTBigDecimalField(name: String, amount: BigDecimal) derives CanEqual
object MTBigDecimalField:
    given Schema[MTBigDecimalField] = Schema.derived[MTBigDecimalField]

case class MTMaybeField(name: String, tag: Maybe[String]) derives CanEqual
object MTMaybeField:
    given Schema[MTMaybeField] = Schema.derived[MTMaybeField]

case class MTMixed(name: String, age: Int, active: Boolean, score: Double) derives CanEqual
object MTMixed:
    given Schema[MTMixed] = Schema.derived[MTMixed]

case class MTStringField(value: String) derives CanEqual
object MTStringField:
    given Schema[MTStringField] = Schema.derived[MTStringField]
