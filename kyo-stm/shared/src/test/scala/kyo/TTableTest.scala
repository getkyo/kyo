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
            yield assert(results == Chunk("name" ~ "Alice" & "age" ~ 30, "name" ~ "Alice" & "age" ~ 35))
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
                yield assertDoesNotCompile("""table.query("name" ~ "Alice")""")
            }

            "should not compile when querying partially indexed fields" in run {
                for table <- TTable.Indexed.init["name" ~ String & "age" ~ Int, "age" ~ Int]
                yield assertDoesNotCompile("""table.query("name" ~ "Alice" & "age" ~ 30)""")
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
