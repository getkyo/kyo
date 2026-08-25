package kyo

/** Pins the element type of [[PostgresClient.copyIn]] and [[PostgresClient.copyOut]].
  *
  * `COPY` moves bytes, and a byte stream in kyo is `Stream[Byte, S]`: the same type `Path.readBytesStream` produces and
  * [[MysqlClient.loadLocalInfile]] consumes, so a file can be copied into a table without a repackaging step. A `Span[Byte]` element would
  * make the caller responsible for framing and compose with no other byte source. The framing behavior behind this surface is
  * [[kyo.internal.postgres.exchange.CopyExchangeTest]]'s, and the round-trip against a server is [[kyo.postgres.CopyIntegrationTest]]'s.
  */
class PostgresClientCopyTest extends Test:

    "copyIn takes a byte stream" in {
        typeCheck("(c: kyo.PostgresClient) => c.copyIn(\"COPY t FROM STDIN\", kyo.Stream.empty[Byte])")
        typeCheckFailure(
            "(c: kyo.PostgresClient) => c.copyIn(\"COPY t FROM STDIN\", kyo.Stream.empty[kyo.Span[Byte]])"
        )
    }

    "copyIn accepts a file's bytes with no repackaging" in {
        // The point of the element type: the stream a Path hands out is the stream copyIn takes.
        typeCheck(
            "(c: kyo.PostgresClient, p: kyo.Path) => c.copyIn(\"COPY t FROM STDIN\", p.readBytesStream)"
        )
    }

    "copyOut is a stream of bytes" in {
        typeCheck(
            "(c: kyo.PostgresClient) => " +
                "(c.copyOut(\"COPY t TO STDOUT\"): kyo.Stream[Byte, kyo.Async & kyo.Abort[kyo.SqlException] & kyo.Scope])"
        )
        typeCheckFailure(
            "(c: kyo.PostgresClient) => " +
                "(c.copyOut(\"COPY t TO STDOUT\"): kyo.Stream[kyo.Span[Byte], kyo.Async & kyo.Abort[kyo.SqlException] & kyo.Scope])"
        )
    }

end PostgresClientCopyTest
