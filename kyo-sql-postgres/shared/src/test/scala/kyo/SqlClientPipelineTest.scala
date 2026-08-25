package kyo

/** Pins the shape [[SqlClient.pipeline]] and [[SqlClient.PipelineBuilder]] present to a caller, and what reaches the connection SPI.
  *
  * Driven by [[SqlClientProbe]] rather than a server: the subject is what the client renders and routes, which the probe reports exactly,
  * so every assertion here is a value comparison. The end-to-end behavior on a real server is [[kyo.postgres.PipelineIntegrationTest]]'s.
  */
class SqlClientPipelineTest extends Test:

    import SqlClientProbe.Call
    import SqlClientProbe.Statement

    "registering a raw statement sends it as written" in {
        SqlClientProbe.withClient() { (client, recorded) =>
            client.pipeline { p =>
                p.execute("INSERT INTO t (id) VALUES (1)").andThen(p.query("SELECT id FROM t"))
            }.flatMap { results =>
                recorded().map { calls =>
                    assert(
                        calls == Chunk(Call.Pipelined(Chunk(
                            Statement("INSERT INTO t (id) VALUES (1)", Chunk.empty),
                            Statement("SELECT id FROM t", Chunk.empty)
                        ))),
                        s"expected both statements in one batch, in order; saw $calls"
                    )
                    assert(results.size == 2, s"one result per statement; got ${results.size}")
                }
            }
        }
    }

    "registering a DSL statement carries its bind values" in {
        SqlClientProbe.withClient() { (client, recorded) =>
            val id   = 42
            val name = "kyo"
            client.pipeline { p =>
                p.execute(sql"INSERT INTO t (id, name) VALUES ($id, $name)")
            }.andThen {
                recorded().map { calls =>
                    // Postgres numbers its placeholders, so the rendered text names both binds and the
                    // params chunk carries their values in position order.
                    assert(
                        calls == Chunk(Call.Pipelined(Chunk(
                            Statement("INSERT INTO t (id, name) VALUES ($1, $2)", Chunk(42, "kyo"))
                        ))),
                        s"expected the fragment's binds to reach the SPI; saw $calls"
                    )
                }
            }
        }
    }

    "a query and an execute registration are the same registration" in {
        SqlClientProbe.withClient() { (client, recorded) =>
            client.pipeline(p => p.execute("SELECT 1")).andThen {
                client.pipeline(p => p.query("SELECT 1")).andThen {
                    recorded().map { calls =>
                        assert(
                            calls == Chunk(
                                Call.Pipelined(Chunk(Statement("SELECT 1", Chunk.empty))),
                                Call.Pipelined(Chunk(Statement("SELECT 1", Chunk.empty)))
                            ),
                            s"execute and query must produce the same batch entry; saw $calls"
                        )
                    }
                }
            }
        }
    }

    "an empty body reaches no connection at all" in {
        SqlClientProbe.withClient() { (client, recorded) =>
            client.pipeline(_ => ()).flatMap { results =>
                recorded().map { calls =>
                    assert(results == Chunk.empty, s"an empty pipeline returns no results; got $results")
                    assert(calls == Chunk.empty, s"an empty pipeline must not reach the SPI; saw $calls")
                }
            }
        }
    }

    "running one pipeline value twice registers its statements once per run" in {
        SqlClientProbe.withClient() { (client, recorded) =>
            val twice = client.pipeline(p => p.execute("INSERT INTO t (id) VALUES (1)"))
            twice.andThen(twice).andThen {
                recorded().map { calls =>
                    val one = Call.Pipelined(Chunk(Statement("INSERT INTO t (id) VALUES (1)", Chunk.empty)))
                    // A builder shared across runs would make the second batch carry two statements.
                    assert(calls == Chunk(one, one), s"each run must send exactly its own statement; saw $calls")
                }
            }
        }
    }

    "the affected-row count of each statement is reported at its own position" in {
        SqlClientProbe.withClient() { (client, _) =>
            client.pipeline { p =>
                Kyo.foreachDiscard(1 to 3)(i => p.execute(s"UPDATE t SET v = $i"))
            }.map { results =>
                val counts = results.map {
                    case Result.Success(outcome) => Present(outcome.affectedRowCount)
                    case _                       => Absent
                }
                assert(counts == Chunk(Present(1L), Present(2L), Present(3L)), s"expected one outcome per position, got $counts")
            }
        }
    }

    // ── a registration carries its own binds, so there is no params overload ──

    "a registration takes a statement or an Executable, never a separate params chunk" in {
        typeCheck("(p: kyo.SqlClient.PipelineBuilder) => p.execute(\"SELECT 1\")")
        typeCheck("(p: kyo.SqlClient.PipelineBuilder) => p.query(\"SELECT 1\")")
        typeCheck("(p: kyo.SqlClient.PipelineBuilder) => p.execute(kyo.Sql.Fragment.lit[Any](\"SELECT 1\"))")
        typeCheck("(p: kyo.SqlClient.PipelineBuilder) => p.query(kyo.Sql.Fragment.lit[Any](\"SELECT 1\"))")
        typeCheckFailure(
            "(p: kyo.SqlClient.PipelineBuilder) => p.execute(\"SELECT $1\", kyo.Chunk.empty[kyo.Sql.BoundValue[?]])"
        )
        typeCheckFailure(
            "(p: kyo.SqlClient.PipelineBuilder) => p.query(\"SELECT $1\", kyo.Chunk.empty[kyo.Sql.BoundValue[?]])"
        )
    }

    "a registration is an effect, so a discarded one registers nothing" in {
        SqlClientProbe.withClient() { (client, recorded) =>
            client.pipeline { p =>
                // The registration's value decides whether the statement is registered. Building one and
                // dropping it leaves the pipeline empty, which is what makes `Unit < Sync` the honest type:
                // a plain `Unit` could not express the difference.
                val ignored = p.execute("INSERT INTO t (id) VALUES (1)")
                discard(ignored)
                ()
            }.flatMap { results =>
                recorded().map { calls =>
                    assert(results == Chunk.empty, s"a dropped registration must leave the pipeline empty; got $results")
                    assert(calls == Chunk.empty, s"nothing may reach the SPI; saw $calls")
                }
            }
        }
    }

end SqlClientPipelineTest
