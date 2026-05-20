package kyo

class TTableTest extends Test:

    "basic operations" - {
        "insert and get" in run {
            for
                table  <- TTable.init["name" ~ String & "age" ~ Int]
                id     <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                record <- STM.run(table.get(id))
            yield
                assert(record.isDefined)
                assert(record.get.name == "Alice")
                assert(record.get.age == 30)
        }

        "update existing record" in run {
            for
                table  <- TTable.init["name" ~ String & "age" ~ Int]
                id     <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                prev   <- STM.run(table.update(id, "name" ~ "Alice" & "age" ~ 31))
                record <- STM.run(table.get(id))
            yield
                assert(prev.isDefined)
                assert(prev.get.age == 30)
                assert(record.isDefined)
                assert(record.get.age == 31)
        }

        "update non-existent record" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int]
                prev  <- STM.run(table.update(table.unsafeId(999), "name" ~ "Alice" & "age" ~ 30))
            yield assert(prev.isEmpty)
        }

        "upsert new record" in run {
            for
                table  <- TTable.init["name" ~ String & "age" ~ Int]
                _      <- STM.run(table.upsert(table.unsafeId(1), "name" ~ "Alice" & "age" ~ 30))
                record <- STM.run(table.get(table.unsafeId(1)))
            yield
                assert(record.isDefined)
                assert(record.get.name == "Alice")
                assert(record.get.age == 30)
        }

        "upsert existing record" in run {
            for
                table  <- TTable.init["name" ~ String & "age" ~ Int]
                id     <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _      <- STM.run(table.upsert(id, "name" ~ "Alice" & "age" ~ 31))
                record <- STM.run(table.get(id))
            yield
                assert(record.isDefined)
                assert(record.get.age == 31)
        }

        "remove record" in run {
            for
                table   <- TTable.init["name" ~ String & "age" ~ Int]
                id      <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                removed <- STM.run(table.remove(id))
                record  <- STM.run(table.get(id))
            yield
                assert(removed.isDefined)
                assert(record.isEmpty)
        }

        "remove non-existent record" in run {
            for
                table   <- TTable.init["name" ~ String & "age" ~ Int]
                removed <- STM.run(table.remove(table.unsafeId(999)))
            yield assert(removed.isEmpty)
        }
    }

    "size" - {
        "empty table" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int]
                size  <- STM.run(table.size)
                empty <- STM.run(table.isEmpty)
            yield
                assert(size == 0)
                assert(empty)
        }

        "non-empty table" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int]
                _     <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                size  <- STM.run(table.size)
                empty <- STM.run(table.isEmpty)
            yield
                assert(size == 1)
                assert(!empty)
        }
    }

    "snapshot" - {
        "empty table" in run {
            for
                table    <- TTable.init["name" ~ String & "age" ~ Int]
                snapshot <- STM.run(table.snapshot)
            yield assert(snapshot.isEmpty)
        }

        "non-empty table" in run {
            for
                table    <- TTable.init["name" ~ String & "age" ~ Int]
                id1      <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                id2      <- STM.run(table.insert("name" ~ "Bob" & "age" ~ 25))
                snapshot <- STM.run(table.snapshot)
            yield
                assert(snapshot.size == 2)
                assert(snapshot(id1).name == "Alice")
                assert(snapshot(id2).name == "Bob")
        }
    }

    "Indexed" - {
        given [A, B]: CanEqual[A, B] = CanEqual.derived

        "query by field" in run {
            for
                table   <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                _       <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _       <- STM.run(table.insert("name" ~ "Bob" & "age" ~ 25))
                _       <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 35))
                results <- STM.run(table.query("name" ~ "Alice"))
            yield
                val expected = Chunk("name" ~ "Alice" & "age" ~ 30, "name" ~ "Alice" & "age" ~ 35)
                assert(results.size == expected.size && results.zip(expected).forall((a, b) => a.is(b)))
        }

        "query by multiple fields" in run {
            for
                table   <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                _       <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _       <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 35))
                results <- STM.run(table.query("name" ~ "Alice" & "age" ~ 30))
            yield
                assert(results.size == 1)
                assert(results.head.name == "Alice")
                assert(results.head.age == 30)
        }

        "query with no matches" in run {
            for
                table   <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                _       <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                results <- STM.run(table.query("name" ~ "Bob"))
            yield assert(results.isEmpty)
        }

        "update indexed record" in run {
            for
                table        <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                id           <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _            <- STM.run(table.update(id, "name" ~ "Bob" & "age" ~ 30))
                aliceResults <- STM.run(table.query("name" ~ "Alice"))
                bobResults   <- STM.run(table.query("name" ~ "Bob"))
            yield
                assert(aliceResults.isEmpty)
                assert(bobResults.size == 1)
                assert(bobResults.head.name == "Bob")
        }

        "remove indexed record" in run {
            for
                table   <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                id      <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _       <- STM.run(table.remove(id))
                results <- STM.run(table.query("name" ~ "Alice"))
            yield assert(results.isEmpty)
        }

        "query validation" - {
            "should not compile when querying non-indexed fields" in run {
                for
                    table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                yield typeCheckFailure("""table.query("name" ~ "Alice")""")(
                    "Cannot query on fields that are not indexed."
                )
            }

            "should not compile when querying partially indexed fields" in run {
                for table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                yield typeCheckFailure("""table.query("name" ~ "Alice" & "age" ~ 30)""")(
                    "Cannot query on fields that are not indexed."
                )
            }
        }

        "Index cleanup" - {
            "removes index entries after all records are removed" in run {
                for
                    table               <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                    id1                 <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                    id2                 <- STM.run(table.insert("name" ~ "Bob" & "age" ~ 30))
                    initialQueryResults <- STM.run(table.query("age" ~ 30))
                    _                   <- STM.run(table.remove(id1))
                    _                   <- STM.run(table.remove(id2))
                    emptyQueryResults   <- STM.run(table.query("age" ~ 30))
                yield
                    assert(initialQueryResults.size == 2)
                    assert(emptyQueryResults.isEmpty)
            }

            "keeps old index values after updates" in run {
                for
                    table          <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                    id             <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                    initialResults <- STM.run(table.query("age" ~ 30))
                    _              <- STM.run(table.update(id, "name" ~ "Alice" & "age" ~ 31))
                    oldAgeResults  <- STM.run(table.query("age" ~ 30))
                    newAgeResults  <- STM.run(table.query("age" ~ 31))
                yield
                    assert(initialResults.size == 1)
                    assert(oldAgeResults.isEmpty)
                    assert(newAgeResults.size == 1)
            }

            "accumulates index entries through nested transaction rollbacks" in run {
                for
                    table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                    id    <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                    _ <- Abort.run {
                        STM.run {
                            for
                                _ <- table.update(id, "name" ~ "Alice" & "age" ~ 31)
                                _ <- STM.run {
                                    table.update(id, "name" ~ "Alice" & "age" ~ 32)
                                        .andThen(Abort.fail(new Exception("Deliberate failure")))
                                }
                            yield ()
                        }
                    }
                    age30Results <- STM.run(table.query("age" ~ 30))
                    age31Results <- STM.run(table.query("age" ~ 31))
                    age32Results <- STM.run(table.query("age" ~ 32))
                yield
                    assert(age30Results.size == 1)
                    assert(age31Results.isEmpty)
                    assert(age32Results.isEmpty)
            }
        }

        "indexing verification" - {
            "should only create specified indexes" in run {
                for
                    table <- TTable.Indexed.init["name" ~ String & "age" ~ Int & "email" ~ String, "name" ~ String & "age" ~ Int]
                    indexFields = table.indexFields
                yield
                    assert(indexFields.size == 2)
                    assert(indexFields.exists(_ == "name"))
                    assert(indexFields.exists(_ == "age"))
                    assert(!indexFields.exists(_ == "email"))
            }

            "should handle single index field" in run {
                for
                    table <- TTable.Indexed.init["name" ~ String & "age" ~ Int & "email" ~ String, "age" ~ Int]
                    indexFields = table.indexFields
                yield
                    assert(indexFields.size == 1)
                    assert(indexFields.exists(_ == "age"))
                    assert(!indexFields.exists(_ == "name"))
                    assert(!indexFields.exists(_ == "email"))
            }
        }
    }

    "Complex field manipulation" - {
        "update should preserve unmodified fields" in run {
            for
                table  <- TTable.init["name" ~ String & "age" ~ Int & "email" ~ String]
                id     <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30 & "email" ~ "alice@test.com"))
                _      <- STM.run(table.update(id, "name" ~ "Alice" & "age" ~ 31 & "email" ~ "alice@test.com"))
                record <- STM.run(table.get(id))
            yield
                assert(record.isDefined)
                assert(record.get.email == "alice@test.com")
                assert(record.get.age == 31)
                assert(record.get.name == "Alice")
        }

        "nested updates should maintain consistency" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int]
                id <- STM.run {
                    for
                        id <- table.insert("name" ~ "Alice" & "age" ~ 30)
                        _ <- STM.run {
                            for
                                _ <- table.update(id, "name" ~ "Bob" & "age" ~ 31)
                                _ <- STM.run(table.update(id, "name" ~ "Charlie" & "age" ~ 32))
                            yield ()
                        }
                    yield id
                }
                record <- STM.run(table.get(id))
            yield
                assert(record.isDefined)
                assert(record.get.name == "Charlie")
                assert(record.get.age == 32)
        }
    }

    "Edge cases" - {
        "operations on removed records" in run {
            for
                table  <- TTable.init["name" ~ String & "age" ~ Int]
                id     <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _      <- STM.run(table.remove(id))
                get    <- STM.run(table.get(id))
                update <- STM.run(table.update(id, "name" ~ "Bob" & "age" ~ 31))
                remove <- STM.run(table.remove(id))
            yield
                assert(get.isEmpty)
                assert(update.isEmpty)
                assert(remove.isEmpty)
        }

        "multiple removes of same id" in run {
            for
                table    <- TTable.init["name" ~ String & "age" ~ Int]
                id       <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                first    <- STM.run(table.remove(id))
                second   <- STM.run(table.remove(id))
                reinsert <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                result   <- STM.run(table.remove(reinsert))
            yield
                assert(first.isDefined)
                assert(second.isEmpty)
                assert(result.isDefined)
        }

        "operations with non-existent IDs" in run {
            for
                table  <- TTable.init["name" ~ String & "age" ~ Int]
                get    <- STM.run(table.get(table.unsafeId(999)))
                update <- STM.run(table.update(table.unsafeId(999), "name" ~ "Alice" & "age" ~ 30))
                remove <- STM.run(table.remove(table.unsafeId(999)))
            yield
                assert(get.isEmpty)
                assert(update.isEmpty)
                assert(remove.isEmpty)
        }
    }

    "Transaction rollback scenarios" - {
        "failed nested transaction should not affect outer transaction" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int]
                id    <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _ <- Abort.run {
                    STM.run {
                        for
                            _ <- table.update(id, "name" ~ "Bob" & "age" ~ 31)
                            _ <- STM.run {
                                for
                                    _ <- table.update(id, "name" ~ "Charlie" & "age" ~ 32)
                                    _ <- Abort.fail(new Exception("Test failure"))
                                yield ()
                            }
                        yield ()
                    }
                }
                record <- STM.run(table.get(id))
            yield
                assert(record.isDefined)
                assert(record.get.name == "Alice")
                assert(record.get.age == 30)
        }

        "partial updates within transaction should roll back" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int & "email" ~ String]
                id    <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30 & "email" ~ "alice@test.com"))
                _ <- Abort.run {
                    STM.run {
                        for
                            _ <- table.update(id, "name" ~ "Bob" & "age" ~ 31 & "email" ~ "bob@test.com")
                            _ <- STM.retryIf(true)
                        yield ()
                    }
                }
                record <- STM.run(table.get(id))
            yield
                assert(record.isDefined)
                assert(record.get.name == "Alice")
                assert(record.get.email == "alice@test.com")
        }
    }

    "Indexed table specific scenarios" - {
        "index consistency after failed updates" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String]
                id    <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _ <- Abort.run {
                    STM.run {
                        for
                            _ <- table.update(id, "name" ~ "Bob" & "age" ~ 31)
                            _ <- Abort.fail(new Exception("Test failure"))
                        yield ()
                    }
                }
                byOriginalName <- STM.run(table.query("name" ~ "Alice"))
                byNewName      <- STM.run(table.query("name" ~ "Bob"))
            yield
                assert(byOriginalName.size == 1)
                assert(byNewName.isEmpty)
                assert(byOriginalName.head.name == "Alice")
        }

        "multiple index values" in run {
            for
                table  <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                _      <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _      <- STM.run(table.insert("name" ~ "Bob" & "age" ~ 30))
                byName <- STM.run(table.query("name" ~ "Alice"))
                byAge  <- STM.run(table.query("age" ~ 30))
            yield
                assert(byName.size == 1)
                assert(byAge.size == 2)
                assert(byAge.exists(_.name == "Alice"))
                assert(byAge.exists(_.name == "Bob"))
        }

        "index updates with multiple matching records" in run {
            for
                table    <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                id1      <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                id2      <- STM.run(table.insert("name" ~ "Bob" & "age" ~ 30))
                _        <- STM.run(table.update(id1, "name" ~ "Alice" & "age" ~ 31))
                byOldAge <- STM.run(table.query("age" ~ 30))
                byNewAge <- STM.run(table.query("age" ~ 31))
            yield
                assert(byOldAge.size == 1)
                assert(byNewAge.size == 1)
                assert(byOldAge.head.name == "Bob")
                assert(byNewAge.head.name == "Alice")
        }

        "querying with non-existent index values" in run {
            for
                table                 <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                _                     <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                byNonExistentName     <- STM.run(table.query("name" ~ "NonExistent"))
                byNonExistentAge      <- STM.run(table.query("age" ~ 999))
                byMultipleNonExistent <- STM.run(table.query("name" ~ "NonExistent" & "age" ~ 999))
            yield
                assert(byNonExistentName.isEmpty)
                assert(byNonExistentAge.isEmpty)
                assert(byMultipleNonExistent.isEmpty)
        }
    }

    "TTable" - {
        given [A, B]: CanEqual[A, B] = CanEqual.derived

        "insert assigns sequential auto-incrementing IDs starting at 0" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int]
                id1   <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                id2   <- STM.run(table.insert("name" ~ "B" & "age" ~ 2))
                id3   <- STM.run(table.insert("name" ~ "C" & "age" ~ 3))
            yield
                assert(id1 == table.unsafeId(0))
                assert(id2 == table.unsafeId(1))
                assert(id3 == table.unsafeId(2))
        }

        "type Id is an opaque Int subtype yielding type-safe handles via unsafeId" in run {
            for
                table1 <- TTable.init["name" ~ String]
                table2 <- TTable.init["name" ~ String]
            yield
                val sameTable: table1.Id = table1.unsafeId(0)
                val widening: Int        = sameTable
                assert(widening == 0)
                assert((table1.unsafeId(7): Int) == 7)
                assert((table2.unsafeId(7): Int) == 7)
        }

        "unsafeId is currently externally callable and returns a usable Id handle" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int]
                handle = table.unsafeId(42)
                result <- STM.run(table.get(handle))
            yield assert(result.isEmpty)
        }

        "unsafeId accepts boundary Int values without truncation or wraparound" in run {
            for
                table <- TTable.init["name" ~ String]
            yield
                val zero = table.unsafeId(0)
                val neg  = table.unsafeId(-1)
                val maxV = table.unsafeId(Int.MaxValue)
                val minV = table.unsafeId(Int.MinValue)
                assert((zero: Int) == 0)
                assert((neg: Int) == -1)
                assert((maxV: Int) == Int.MaxValue)
                assert((minV: Int) == Int.MinValue)
        }

        "insert IDs are monotonically increasing across many records" in run {
            for
                table <- TTable.init["name" ~ String]
                ids   <- Kyo.foreach(0 until 10)(i => STM.run(table.insert("name" ~ s"r$i")))
            yield assert(ids.sliding(2).forall { case Seq(a, b) => (a: Int) < (b: Int) })
        }

        "a failed insert transaction rolls back its record but still consumes an id" in run {
            for
                table <- TTable.init["name" ~ String]
                preId <- STM.run(table.insert("name" ~ "pre"))
                _ <- Abort.run(STM.run {
                    table.insert("name" ~ "doomed").andThen(Abort.fail(new Exception("boom")))
                })
                postId <- STM.run(table.insert("name" ~ "post"))
                snap   <- STM.run(table.snapshot)
            yield
                // The id counter is a lock-free AtomicInt, not a transactional ref: the failed
                // attempt consumes an id, so postId is strictly greater than preId but need not
                // be preId + 1. The doomed record is still rolled back.
                assert((postId: Int) > (preId: Int))
                assert(!snap.values.exists(_.name == "doomed"), "failed insert must leave no record")
        }

        "re-insert after remove produces a fresh ID not equal to the removed one" in run {
            for
                table <- TTable.init["name" ~ String]
                id    <- STM.run(table.insert("name" ~ "Alice"))
                _     <- STM.run(table.remove(id))
                reId  <- STM.run(table.insert("name" ~ "Alice"))
            yield
                assert(reId != id)
                assert((reId: Int) > (id: Int))
        }

        "update of non-existent ID leaves size and snapshot unchanged" in run {
            for
                table <- TTable.init["name" ~ String]
                _     <- STM.run(table.insert("name" ~ "Alice"))
                prev  <- STM.run(table.update(table.unsafeId(999), "name" ~ "Bob"))
                size  <- STM.run(table.size)
                snap  <- STM.run(table.snapshot)
            yield
                assert(prev.isEmpty)
                assert(size == 1)
                assert(!snap.values.exists(_.name == "Bob"))
        }

        "upsert at custom unsafeId then insert produce two distinct records" in run {
            for
                table     <- TTable.init["name" ~ String]
                _         <- STM.run(table.upsert(table.unsafeId(1), "name" ~ "manual"))
                autoId    <- STM.run(table.insert("name" ~ "auto"))
                manualRec <- STM.run(table.get(table.unsafeId(1)))
                autoRec   <- STM.run(table.get(autoId))
                size      <- STM.run(table.size)
            yield
                assert(manualRec.exists(_.name == "manual"))
                assert(autoRec.exists(_.name == "auto"))
                assert(size == 2)
        }

        "Indexed.upsert of a new record makes it queryable through the index" in run {
            for
                table  <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                _      <- STM.run(table.upsert(table.unsafeId(5), "name" ~ "Dora" & "age" ~ 40))
                byName <- STM.run(table.query("name" ~ "Dora"))
                byAge  <- STM.run(table.query("age" ~ 40))
            yield
                assert(byName.size == 1 && byName.head.name == "Dora")
                assert(byAge.size == 1 && byAge.head.age == 40)
        }

        "Indexed.upsert overwriting an indexed field removes the prior index entry" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                id = table.unsafeId(1)
                _     <- STM.run(table.upsert(id, "name" ~ "Alice" & "age" ~ 30))
                _     <- STM.run(table.upsert(id, "name" ~ "Alice" & "age" ~ 31))
                stale <- STM.run(table.query("age" ~ 30))
                fresh <- STM.run(table.query("age" ~ 31))
            yield assert(stale.isEmpty && fresh.size == 1 && fresh.head.age == 31)
        }

        "remove in a transaction that aborts leaves the record intact" in run {
            for
                table <- TTable.init["name" ~ String]
                id    <- STM.run(table.insert("name" ~ "Alice"))
                _ <- Abort.run(STM.run(
                    table.remove(id).andThen(Abort.fail(new Exception("boom")))
                ))
                after <- STM.run(table.get(id))
                size  <- STM.run(table.size)
            yield
                assert(after.exists(_.name == "Alice"))
                assert(size == 1)
        }

        "size reports correct count after 100 inserts on both Base and Indexed" in run {
            for
                base        <- TTable.init["name" ~ String]
                indexed     <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String]
                _           <- Kyo.foreach(0 until 100)(i => STM.run(base.insert("name" ~ s"n$i")))
                _           <- Kyo.foreach(0 until 100)(i => STM.run(indexed.insert("name" ~ s"n$i" & "age" ~ i)))
                baseSize    <- STM.run(base.size)
                indexedSize <- STM.run(indexed.size)
            yield
                assert(baseSize == 100)
                assert(indexedSize == 100)
        }

        "Indexed.isEmpty is true on init and false after insert" in run {
            for
                table  <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                empty0 <- STM.run(table.isEmpty)
                _      <- STM.run(table.insert("name" ~ "x"))
                empty1 <- STM.run(table.isEmpty)
            yield
                assert(empty0)
                assert(!empty1)
        }

        "Indexed.snapshot returns the underlying store contents at any size" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                id1   <- STM.run(table.insert("name" ~ "A" & "age" ~ 10))
                snap1 <- STM.run(table.snapshot)
                _     <- STM.run(table.insert("name" ~ "B" & "age" ~ 20))
                _     <- STM.run(table.insert("name" ~ "C" & "age" ~ 30))
                snap3 <- STM.run(table.snapshot)
            yield
                assert(snap1.size == 1 && snap1(id1).name == "A")
                assert(snap3.size == 3)
                assert(snap3.values.map(_.age).toSet == Set(10, 20, 30))
        }

        "Indexed.init accepts Indexes that is a strict superset of F" in run {
            for
                table <- TTable.Indexed.init[
                    "name" ~ String & "age" ~ Int,
                    "name" ~ String & "age" ~ Int
                ]
                _      <- STM.run(table.insert("name" ~ "Eve" & "age" ~ 22))
                result <- STM.run(table.query("name" ~ "Eve"))
            yield
                assert(result.size == 1)
                assert(table.indexFields == Set("name", "age"))
        }

        "Indexed.Id is identical to its underlying store.Id" in run {
            for
                table      <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String]
                id         <- STM.run(table.insert("name" ~ "Eve" & "age" ~ 22))
                viaIndexed <- STM.run(table.get(id))
                viaStore   <- STM.run(table.store.get(id))
            yield
                assert(viaIndexed == viaStore)
                assert(viaIndexed.isDefined)
        }

        "Indexed.get returns the record matching the inserted ID" in run {
            for
                table   <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                id      <- STM.run(table.insert("name" ~ "Z" & "age" ~ 42))
                got     <- STM.run(table.get(id))
                missing <- STM.run(table.get(table.unsafeId(999)))
            yield
                assert(got.exists(_.name == "Z"))
                assert(missing.isEmpty)
        }

        "Indexed.update on a non-existent ID returns Absent and does not touch indexes" in run {
            for
                table      <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String]
                _          <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                prev       <- STM.run(table.update(table.unsafeId(999), "name" ~ "Bob" & "age" ~ 40))
                stillAlice <- STM.run(table.query("name" ~ "Alice"))
                noBob      <- STM.run(table.query("name" ~ "Bob"))
                size       <- STM.run(table.size)
            yield
                assert(prev.isEmpty)
                assert(stillAlice.size == 1)
                assert(noBob.isEmpty)
                assert(size == 1)
        }

        "Indexed.remove on a non-existent ID returns Absent and does not touch indexes" in run {
            for
                table      <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String]
                _          <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                removed    <- STM.run(table.remove(table.unsafeId(999)))
                stillAlice <- STM.run(table.query("name" ~ "Alice"))
                size       <- STM.run(table.size)
            yield
                assert(removed.isEmpty)
                assert(stillAlice.size == 1)
                assert(size == 1)
        }

        "Indexed.indexFields returns the index keys for a single-field table" in run {
            for
                table <- TTable.Indexed.init["x" ~ Int, "x" ~ Int]
                fields = table.indexFields
                _ <- STM.run(table.insert("x" ~ 1))
                q <- STM.run(table.query("x" ~ 1))
            yield
                assert(fields == Set("x"))
                assert(q.size == 1)
        }

        "Indexed.indexFields reports all fields when many fields are indexed" in run {
            for
                table <- TTable.Indexed.init[
                    "a" ~ Int & "b" ~ Int & "c" ~ Int & "d" ~ Int & "e" ~ Int &
                        "f" ~ Int & "g" ~ Int & "h" ~ Int & "i" ~ Int & "j" ~ Int,
                    "a" ~ Int & "b" ~ Int & "c" ~ Int & "d" ~ Int & "e" ~ Int &
                        "f" ~ Int & "g" ~ Int & "h" ~ Int & "i" ~ Int & "j" ~ Int
                ]
                fields = table.indexFields
            yield assert(fields == Set("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"))
        }

        "non-indexed query failure message names the filter and indexed field types" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
            yield
                typeCheckFailure("""table.query("name" ~ "Alice")""")("Filter fields:")
                typeCheckFailure("""table.query("name" ~ "Alice")""")("Indexed fields:")
        }

        "queryIds returns the IDs of matching records sorted ascending" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String]
                id1   <- STM.run(table.insert("name" ~ "X" & "age" ~ 1))
                id2   <- STM.run(table.insert("name" ~ "X" & "age" ~ 2))
                id3   <- STM.run(table.insert("name" ~ "Y" & "age" ~ 3))
                ids   <- STM.run(table.queryIds("name" ~ "X"))
            yield
                assert(ids == Chunk(id1, id2))
                val ints = ids.toSeq.map(id => (id: Int))
                assert(ints == ints.sorted)
        }

        "query whose filter matches all records returns the whole table" in run {
            for
                table  <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                _      <- STM.run(table.insert("name" ~ "A" & "age" ~ 10))
                _      <- STM.run(table.insert("name" ~ "B" & "age" ~ 10))
                _      <- STM.run(table.insert("name" ~ "C" & "age" ~ 10))
                result <- STM.run(table.query("age" ~ 10))
            yield
                assert(result.size == 3)
                assert(result.map(_.name).toSet == Set("A", "B", "C"))
        }

        "query skips ids whose underlying store entry was deleted" in run {
            for
                table  <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                id1    <- STM.run(table.insert("name" ~ "A"))
                id2    <- STM.run(table.insert("name" ~ "A"))
                _      <- STM.run(table.remove(id1))
                result <- STM.run(table.query("name" ~ "A"))
                ids    <- STM.run(table.queryIds("name" ~ "A"))
            yield
                assert(result.size == 1)
                assert(ids == Chunk(id2))
        }

        "query intersection handles partial removal across two indexes" in run {
            for
                table  <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                id1    <- STM.run(table.insert("name" ~ "T" & "age" ~ 1))
                id2    <- STM.run(table.insert("name" ~ "T" & "age" ~ 2))
                id3    <- STM.run(table.insert("name" ~ "T" & "age" ~ 3))
                _      <- STM.run(table.remove(id2))
                result <- STM.run(table.query("name" ~ "T"))
            yield
                assert(result.size == 2)
                assert(result.map(_.age).toSet == Set(1, 3))
        }

        "insert into Indexed with one indexed field populates the index" in run {
            for
                table <- TTable.Indexed.init["x" ~ Int & "y" ~ Int, "x" ~ Int]
                id    <- STM.run(table.insert("x" ~ 7 & "y" ~ 99))
                q     <- STM.run(table.query("x" ~ 7))
            yield
                assert(q.size == 1)
                assert(q.head.y == 99)
        }

        "remove after upsert-overwrite cleans index entries for all prior values" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                id = table.unsafeId(1)
                _            <- STM.run(table.upsert(id, "name" ~ "Z" & "age" ~ 10))
                _            <- STM.run(table.upsert(id, "name" ~ "Z" & "age" ~ 20))
                removed      <- STM.run(table.remove(id))
                stillAtAge10 <- STM.run(table.query("age" ~ 10))
                atAge20      <- STM.run(table.query("age" ~ 20))
            yield
                assert(removed.isDefined)
                assert(stillAtAge10.isEmpty)
                assert(atAge20.isEmpty)
        }

        "index map drops keys whose value-set becomes empty" in run {
            for
                table  <- TTable.Indexed.init["age" ~ Int, "age" ~ Int]
                id1    <- STM.run(table.insert("age" ~ 30))
                id2    <- STM.run(table.insert("age" ~ 30))
                _      <- STM.run(table.remove(id1))
                _      <- STM.run(table.remove(id2))
                result <- STM.run(table.query("age" ~ 30))
                empty  <- STM.run(table.isEmpty)
                size   <- STM.run(table.size)
            yield
                assert(result.isEmpty)
                assert(empty)
                assert(size == 0)
        }

        "update wholesale-replaces all fields and snapshot reflects exactly the passed record" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int & "email" ~ String]
                id    <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30 & "email" ~ "a@x"))
                newRec = "name" ~ "Alice2" & "age" ~ 99 & "email" ~ "b@x"
                _   <- STM.run(table.update(id, newRec))
                got <- STM.run(table.get(id))
            yield assert(got.exists(r => r.name == "Alice2" && r.age == 99 && r.email == "b@x"))
        }

        "nested-transaction rollbacks restore original indexed value (not accumulate)" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                id    <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                _ <- Abort.run(STM.run {
                    for
                        _ <- table.update(id, "name" ~ "Alice" & "age" ~ 31)
                        _ <- STM.run(
                            table.update(id, "name" ~ "Alice" & "age" ~ 32)
                                .andThen(Abort.fail(new Exception("boom")))
                        )
                    yield ()
                })
                a30 <- STM.run(table.query("age" ~ 30))
                a31 <- STM.run(table.query("age" ~ 31))
                a32 <- STM.run(table.query("age" ~ 32))
            yield
                assert(a30.size == 1)
                assert(a31.isEmpty)
                assert(a32.isEmpty)
        }

        "each name in indexFields makes a query on that field actually work" in run {
            for
                table  <- TTable.Indexed.init["name" ~ String & "age" ~ Int & "email" ~ String, "name" ~ String & "age" ~ Int]
                _      <- STM.run(table.insert("name" ~ "A" & "age" ~ 1 & "email" ~ "a@x"))
                byName <- STM.run(table.query("name" ~ "A"))
                byAge  <- STM.run(table.query("age" ~ 1))
            yield
                assert(byName.size == 1)
                assert(byAge.size == 1)
                assert(table.indexFields == Set("name", "age"))
        }

        "operations-on-removed-records: insert after remove yields a fresh non-colliding ID" in run {
            for
                table  <- TTable.init["name" ~ String & "age" ~ Int]
                id1    <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                _      <- STM.run(table.remove(id1))
                id2    <- STM.run(table.insert("name" ~ "B" & "age" ~ 2))
                gotOld <- STM.run(table.get(id1))
                gotNew <- STM.run(table.get(id2))
            yield
                assert(id1 != id2)
                assert(gotOld.isEmpty)
                assert(gotNew.exists(_.name == "B"))
        }

        "multi-table STM.run commits writes to both tables when no abort" in run {
            for
                table1 <- TTable.init["name" ~ String]
                table2 <- TTable.init["age" ~ Int]
                _ <- STM.run {
                    for
                        _ <- table1.insert("name" ~ "Alice")
                        _ <- table2.insert("age" ~ 30)
                    yield ()
                }
                s1 <- STM.run(table1.size)
                s2 <- STM.run(table2.size)
            yield
                assert(s1 == 1)
                assert(s2 == 1)
        }

        "snapshot returns a Map keyed by table.Id" in run {
            for
                table <- TTable.init["name" ~ String]
                id    <- STM.run(table.insert("name" ~ "x"))
                snap  <- STM.run(table.snapshot)
            yield
                val byId: Map[table.Id, Record["name" ~ String]] = snap
                assert(byId(id).name == "x")
                typeCheckFailure("""val m: Map[String, Record["name" ~ String]] = snap""")("Found:")
        }

        "insert and get of a Maybe-typed record field round-trips" in run {
            for
                table <- TTable.init["name" ~ String & "nick" ~ Maybe[String]]
                id1   <- STM.run(table.insert("name" ~ "A" & "nick" ~ Maybe("X")))
                id2   <- STM.run(table.insert("name" ~ "B" & "nick" ~ Maybe.empty[String]))
                r1    <- STM.run(table.get(id1))
                r2    <- STM.run(table.get(id2))
            yield
                assert(r1.exists(_.nick == Maybe("X")))
                assert(r2.exists(_.nick.isEmpty))
        }

        "Indexed.query against a Boolean indexed field returns correct partition" in run {
            for
                table      <- TTable.Indexed.init["name" ~ String & "active" ~ Boolean, "active" ~ Boolean]
                _          <- STM.run(table.insert("name" ~ "A" & "active" ~ true))
                _          <- STM.run(table.insert("name" ~ "B" & "active" ~ false))
                _          <- STM.run(table.insert("name" ~ "C" & "active" ~ true))
                onResults  <- STM.run(table.query("active" ~ true))
                offResults <- STM.run(table.query("active" ~ false))
            yield
                assert(onResults.size == 2)
                assert(offResults.size == 1)
        }

        "Indexed constructor is private; external new fails to compile" in run {
            for
                _ <- Kyo.unit
            yield typeCheckFailure("""
                val table: TTable[Any] = null
                new TTable.Indexed(table, Map.empty)
            """)("private")
        }

        "Base.nextId and Base.store are private; references fail to compile" in run {
            for
                table <- TTable.init["x" ~ Int]
            yield typeCheckFailure("""table.asInstanceOf[TTable.Base[?]].nextId""")("Base")
        }

        "Indexed.store is publicly accessible and delegates basic CRUD" in run {
            for
                table          <- TTable.Indexed.init["x" ~ Int, "x" ~ Int]
                id             <- STM.run(table.insert("x" ~ 5))
                gotFromStore   <- STM.run(table.store.get(id))
                gotFromIndexed <- STM.run(table.get(id))
            yield
                assert(gotFromStore == gotFromIndexed)
                assert(gotFromStore.exists(_.x == 5))
        }

        "queryIds inside the same STM.run as an insert observes the newly-inserted record" in run {
            for
                table <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                result <- STM.run {
                    for
                        id  <- table.insert("name" ~ "Eve")
                        ids <- table.queryIds("name" ~ "Eve")
                    yield (id, ids)
                }
                (id, ids) = result
            yield assert(ids == Chunk(id))
        }

        "query results equal at the Chunk[Record[F]] type level without loose CanEqual" in run {
            for
                table   <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String]
                _       <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                results <- STM.run(table.query("name" ~ "A"))
            yield
                assert(results.size == 1)
                assert(results.head.name == "A")
                assert(results.head.age == 1)
        }

        "queryIds with one-field filter does not throw on the single-element reduce" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                id1   <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                id2   <- STM.run(table.insert("name" ~ "A" & "age" ~ 2))
                ids   <- STM.run(table.queryIds("name" ~ "A"))
            yield assert(ids.toSet == Set(id1, id2))
        }

        "query result ordering is by ID regardless of filter field order" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                _     <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                _     <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                _     <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                r1    <- STM.run(table.query("name" ~ "A" & "age" ~ 1))
                r2    <- STM.run(table.query("age" ~ 1 & "name" ~ "A"))
            yield
                assert(r1.size == 3 && r2.size == 3)
                assert(r1.map(_.age) == r2.map(_.age))
                assert(r1.map(_.name) == r2.map(_.name))
        }

        "queryIds is sorted ascending regardless of filter field order" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String & "age" ~ Int]
                id1   <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                id2   <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                ids1  <- STM.run(table.queryIds("name" ~ "A" & "age" ~ 1))
                ids2  <- STM.run(table.queryIds("age" ~ 1 & "name" ~ "A"))
            yield
                assert(ids1 == ids2)
                assert(ids1 == Chunk(id1, id2))
        }

        "Indexed.insert in a failed transaction does not leak index entries" in run {
            for
                table <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                _ <- Abort.run(STM.run(
                    table.insert("name" ~ "Ghost").andThen(Abort.fail(new Exception("boom")))
                ))
                ids  <- STM.run(table.queryIds("name" ~ "Ghost"))
                size <- STM.run(table.size)
            yield
                assert(ids.isEmpty)
                assert(size == 0)
        }

        "Indexed.upsert in a failed transaction does not leak index entries" in run {
            for
                table <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                _ <- Abort.run(STM.run(
                    table.upsert(table.unsafeId(7), "name" ~ "Ghost")
                        .andThen(Abort.fail(new Exception("boom")))
                ))
                ids  <- STM.run(table.queryIds("name" ~ "Ghost"))
                size <- STM.run(table.size)
                get  <- STM.run(table.get(table.unsafeId(7)))
            yield
                assert(ids.isEmpty)
                assert(size == 0)
                assert(get.isEmpty)
        }

        "two Indexed.upserts with the same record value leave one entry per index" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                id = table.unsafeId(1)
                _     <- STM.run(table.upsert(id, "name" ~ "A" & "age" ~ 5))
                _     <- STM.run(table.upsert(id, "name" ~ "A" & "age" ~ 5))
                atAge <- STM.run(table.query("age" ~ 5))
                size  <- STM.run(table.size)
            yield
                assert(size == 1)
                assert(atAge.size == 1)
        }

        "insert with same value on two indexed fields appears once per index entry" in run {
            for
                table <- TTable.Indexed.init["a" ~ Int & "b" ~ Int, "a" ~ Int & "b" ~ Int]
                id    <- STM.run(table.insert("a" ~ 5 & "b" ~ 5))
                ra    <- STM.run(table.queryIds("a" ~ 5))
                rb    <- STM.run(table.queryIds("b" ~ 5))
                rab   <- STM.run(table.queryIds("a" ~ 5 & "b" ~ 5))
            yield
                assert(ra == Chunk(id))
                assert(rb == Chunk(id))
                assert(rab == Chunk(id))
        }

        "Indexed.update with same record value leaves observable index queries unchanged" in run {
            for
                table  <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                id     <- STM.run(table.insert("name" ~ "A" & "age" ~ 7))
                before <- STM.run(table.query("age" ~ 7))
                _      <- STM.run(table.update(id, "name" ~ "A" & "age" ~ 7))
                after  <- STM.run(table.query("age" ~ 7))
            yield
                assert(before.size == after.size && before.zip(after).forall((a, b) => a.is(b)))
                assert(after.size == 1)
        }

        "get(unsafeId(0)) on an empty table returns Absent" in run {
            for
                table <- TTable.init["name" ~ String]
                r0    <- STM.run(table.get(table.unsafeId(0)))
                rMax  <- STM.run(table.get(table.unsafeId(Int.MaxValue)))
                rNeg  <- STM.run(table.get(table.unsafeId(-1)))
            yield
                assert(r0.isEmpty)
                assert(rMax.isEmpty)
                assert(rNeg.isEmpty)
        }

        "Indexed.upsert overwriting with same indexed value: query still returns single record" in run {
            for
                table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "name" ~ String]
                id = table.unsafeId(1)
                _       <- STM.run(table.upsert(id, "name" ~ "Alice" & "age" ~ 30))
                _       <- STM.run(table.upsert(id, "name" ~ "Alice" & "age" ~ 31))
                results <- STM.run(table.query("name" ~ "Alice"))
            yield
                assert(results.size == 1)
                assert(results.head.age == 31)
        }

        "Indexed.update with a new indexed value evicts the old value from the index map" in run {
            for
                table    <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                id       <- STM.run(table.insert("name" ~ "X"))
                _        <- STM.run(table.update(id, "name" ~ "Y"))
                xResults <- STM.run(table.queryIds("name" ~ "X"))
                yResults <- STM.run(table.queryIds("name" ~ "Y"))
            yield
                assert(xResults.isEmpty)
                assert(yResults == Chunk(id))
        }

        "Indexed.size mirrors store.size across insert/remove/upsert sequence" in run {
            for
                table <- TTable.Indexed.init["x" ~ Int, "x" ~ Int]
                s0    <- STM.run(table.size)
                id1   <- STM.run(table.insert("x" ~ 1))
                s1    <- STM.run(table.size)
                _     <- STM.run(table.upsert(table.unsafeId(99), "x" ~ 99))
                s2    <- STM.run(table.size)
                _     <- STM.run(table.remove(id1))
                s3    <- STM.run(table.size)
            yield
                assert(s0 == 0)
                assert(s1 == 1)
                assert(s2 == 2)
                assert(s3 == 1)
        }

        "single-field Indexed.init supports insert/get/update/remove without deadlock" in run {
            for
                table    <- TTable.Indexed.init["k" ~ Int, "k" ~ Int]
                id       <- STM.run(table.insert("k" ~ 1))
                got      <- STM.run(table.get(id))
                _        <- STM.run(table.update(id, "k" ~ 2))
                got2     <- STM.run(table.get(id))
                _        <- STM.run(table.remove(id))
                gotEmpty <- STM.run(table.get(id))
            yield
                assert(got.exists(_.k == 1))
                assert(got2.exists(_.k == 2))
                assert(gotEmpty.isEmpty)
        }

        "Indexed.get on a never-inserted unsafeId returns Absent" in run {
            for
                table <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                r     <- STM.run(table.get(table.unsafeId(123)))
            yield assert(r.isEmpty)
        }

        "upsert at unsafeId(nextId) does not cause the next insert to overwrite it" in run {
            for
                table  <- TTable.init["x" ~ Int]
                _      <- STM.run(table.upsert(table.unsafeId(0), "x" ~ 7))
                autoId <- STM.run(table.insert("x" ~ 99))
                manual <- STM.run(table.get(table.unsafeId(0)))
                auto   <- STM.run(table.get(autoId))
                size   <- STM.run(table.size)
            yield assert(manual.exists(_.x == 7) && auto.exists(_.x == 99) && size == 2)
        }

        "Indexed.insert IDs increase monotonically" in run {
            for
                table <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                ids   <- Kyo.foreach(0 until 5)(i => STM.run(table.insert("name" ~ s"n$i")))
            yield assert(ids.sliding(2).forall { case Seq(a, b) => (a: Int) < (b: Int) })
        }

        "Indexed.snapshot matches store.snapshot after CRUD sequence" in run {
            for
                table       <- TTable.Indexed.init["x" ~ Int, "x" ~ Int]
                id1         <- STM.run(table.insert("x" ~ 1))
                id2         <- STM.run(table.insert("x" ~ 2))
                _           <- STM.run(table.remove(id1))
                indexedSnap <- STM.run(table.snapshot)
                storeSnap   <- STM.run(table.store.snapshot)
            yield
                assert(indexedSnap == storeSnap)
                assert(indexedSnap.size == 1)
                assert(indexedSnap(id2).x == 2)
        }

        "two inserts with identical record content produce distinct IDs and two snapshot entries" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int]
                id1   <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                id2   <- STM.run(table.insert("name" ~ "A" & "age" ~ 1))
                snap  <- STM.run(table.snapshot)
            yield
                assert(id1 != id2)
                assert(snap.size == 2)
        }

        "two records identical on indexed field both appear in queryIds" in run {
            for
                table <- TTable.Indexed.init["a" ~ Int, "a" ~ Int]
                id1   <- STM.run(table.insert("a" ~ 7))
                id2   <- STM.run(table.insert("a" ~ 7))
                ids   <- STM.run(table.queryIds("a" ~ 7))
            yield assert(ids.toSet == Set(id1, id2))
        }

        "upsert-then-upsert with changing indexed field: query on prior value returns empty" in run {
            for
                table <- TTable.Indexed.init["name" ~ String, "name" ~ String]
                id = table.unsafeId(1)
                _      <- STM.run(table.upsert(id, "name" ~ "a"))
                _      <- STM.run(table.upsert(id, "name" ~ "b"))
                oldHit <- STM.run(table.queryIds("name" ~ "a"))
                newHit <- STM.run(table.queryIds("name" ~ "b"))
            yield assert(oldHit.isEmpty && newHit == Chunk(id))
        }
    }

    "Complex transaction scenarios" - {
        "interleaved operations on same record" in run {
            for
                table <- TTable.init["name" ~ String & "age" ~ Int]
                id    <- STM.run(table.insert("name" ~ "Alice" & "age" ~ 30))
                result <- STM.run {
                    for
                        original    <- table.get(id)
                        _           <- table.update(id, "name" ~ "Bob" & "age" ~ 31)
                        updated     <- table.get(id)
                        _           <- table.remove(id)
                        afterRemove <- table.get(id)
                        _           <- table.insert("name" ~ "Charlie" & "age" ~ 32)
                    yield (original, updated, afterRemove)
                }
                (original, updated, afterRemove) = result
            yield
                assert(original.isDefined && original.get.name == "Alice")
                assert(updated.isDefined && updated.get.name == "Bob")
                assert(afterRemove.isEmpty)
        }

        "transaction isolation with multiple tables" in run {
            for
                table1 <- TTable.init["name" ~ String & "age" ~ Int]
                table2 <- TTable.init["name" ~ String & "age" ~ Int]
                _ <- Abort.run {
                    STM.run {
                        for
                            id1 <- table1.insert("name" ~ "Alice" & "age" ~ 30)
                            id2 <- table2.insert("name" ~ "Bob" & "age" ~ 31)
                            _   <- table1.update(id1, "name" ~ "Charlie" & "age" ~ 32)
                            _   <- Abort.fail(new Exception("Test failure"))
                        yield ()
                    }
                }
                size1 <- STM.run(table1.size)
                size2 <- STM.run(table2.size)
            yield
                assert(size1 == 0)
                assert(size2 == 0)
        }
    }

end TTableTest
