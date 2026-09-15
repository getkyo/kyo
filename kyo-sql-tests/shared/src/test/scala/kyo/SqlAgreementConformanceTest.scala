package kyo

import kyo.internal.SqlTestBackend.ColumnType

/** Behaviours the engines were MEASURED to agree on, guarded so they keep agreeing.
  *
  * Every other conformance suite here is written from a divergence. This one is written from the opposite result, because an agreement
  * nothing asserts is a coincidence that has held so far rather than a guarantee. Each leaf below came back identical on both engines and
  * had no test anywhere, which is precisely what makes a future divergence in one of them easy to miss.
  *
  * Written through the typed API, so a dialect change that altered how any of these render fails here too.
  */
class SqlAgreementConformanceTest extends SqlBackendTest:

    case class Maybe0(id: Int, v: Maybe[Int]) derives SqlSchema, CanEqual
    case class Padded(v: String) derives SqlSchema, CanEqual
    case class Astral(v: String) derives SqlSchema, CanEqual

    /** Membership selects by value, and an absence predicate selects exactly the rows holding no value.
      *
      * Absence is where three-valued logic makes a query mean something other than it reads: an absent value is not equal to anything,
      * including another absent value, so it can only be selected by an absence predicate and never by a comparison. Both engines implement
      * that identically today. It is guarded because the behaviour is surprising enough that a future change to make it intuitive is
      * plausible, and would be a conformance break on whichever engine received it.
      *
      * The membership half is asserted beside it so the leaf cannot pass on a table that failed to seed: if the insert had not landed, both
      * assertions would answer zero rows and only one of them expects that.
      */
    "membership selects by value and absence selects the rows holding none" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE maybe0 (id ${backend.columnType(ColumnType.Int)}, v ${backend.columnType(ColumnType.Int)})"
                )
                _        <- Sql.insert[Maybe0].values(Maybe0(1, Present(1)), Maybe0(2, Present(2)), Maybe0(3, Absent)).run
                included <- Sql.from[Maybe0]("m").where(_.m.id.in(1, 2)).run
                absent   <- Sql.from[Maybe0]("m").where(_.m.v == Absent).run
            yield
                assert(included.size == 2, s"${backend.label}: expected two rows by id, got ${included.size}")
                assert(
                    absent.map(_.id) == Chunk(3),
                    s"${backend.label}: expected only the absent row, got ${absent.map(_.id)}"
                )
        }
    }

    /** A unique index permits more than one absent value.
      *
      * Two absent values are not equal to each other, so a uniqueness constraint does not collide them. Both engines agree, and this is worth
      * a guard because it is the one place where "absent behaves like a value" would be a plausible and wrong simplification.
      */
    "a unique index permits several absent values" - {
        forEachBackend() { (backend, client, _) =>
            for
                _ <- client.executeRaw(
                    s"CREATE TABLE maybe0 (id ${backend.columnType(ColumnType.Int)} PRIMARY KEY, v ${backend.columnType(ColumnType.Int)} UNIQUE)"
                )
                _    <- Sql.insert[Maybe0].values(Maybe0(1, Absent), Maybe0(2, Absent), Maybe0(3, Present(7))).run
                rows <- Sql.from[Maybe0]("m").run
            yield assert(
                rows.size == 3,
                s"${backend.label}: a unique column must accept several absent values, got ${rows.size} rows"
            )
        }
    }

    /** A trailing space is part of the value, so a padded string does not equal its unpadded form.
      *
      * This one is conditional rather than universal, which is why it is guarded rather than assumed. The engines agree only while the text
      * column is on a NO PAD collation; the legacy collations on one engine are PAD SPACE, where `'a'` equals `'a '` and a unique index
      * rejects the pair. The descriptor pins a NO PAD collation, so this leaf also guards that pinning from being quietly dropped.
      */
    "a trailing space is significant in equality" - {
        forEachBackend() { (backend, client, _) =>
            for
                _         <- client.executeRaw(s"CREATE TABLE padded (v ${backend.textColumnType} NOT NULL)")
                _         <- Sql.insert[Padded].values(Padded("a"), Padded("a ")).run
                unpadded  <- Sql.from[Padded]("p").where(_.p.v == "a").run
                distinct0 <- Sql.from[Padded]("p").select(_.p.v).distinct.run
            yield
                assert(
                    unpadded.size == 1,
                    s"${backend.label}: 'a' must not match 'a ', got ${unpadded.size} rows"
                )
                assert(
                    distinct0.size == 2,
                    s"${backend.label}: the padded and unpadded forms are distinct values, got ${distinct0.size}"
                )
        }
    }

    /** A character outside the basic plane survives a round trip, and counts as one character.
      *
      * Four-byte UTF-8 is where an encoding assumption shows up: a connection negotiated at a three-byte charset cannot store it at all, and a
      * length that counted bytes would answer four. Both engines round-trip it intact today.
      */
    "a four-byte character round-trips and counts as one" - {
        forEachBackend() { (backend, client, _) =>
            val value = "a😀b"
            for
                _       <- client.executeRaw(s"CREATE TABLE astral (v ${backend.textColumnType} NOT NULL)")
                _       <- Sql.insert[Astral].values(Astral(value)).run
                rows    <- Sql.from[Astral]("a").run
                lengths <- Sql.from[Astral]("a").select(_.a.v.length).run
            yield
                assert(rows.head.v == value, s"${backend.label}: expected $value, got ${rows.head.v}")
                assert(lengths.head == 3, s"${backend.label}: three characters, six bytes, got ${lengths.head}")
            end for
        }
    }

end SqlAgreementConformanceTest
