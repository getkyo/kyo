package kyo.internal.mysql

/** MySQL client capability flags negotiated during the handshake.
  *
  * These bit flags are exchanged in the HandshakeV10 / HandshakeResponse41 packets. The server advertises what it supports; the client
  * picks a subset and echoes it back. The intersection governs the rest of the session.
  *
  * Reference: MySQL Internals Manual — Capability Flags (§14.1.3.1)
  *
  * Note: [[CLIENT_DEPRECATE_EOF]] is the modern default — when negotiated, EOF packets are replaced by OK packets everywhere, eliminating
  * the 0xFE ambiguity for payloads >= 9 bytes. kyo-sql always negotiates this flag.
  */
object Capabilities:

    /** Use the improved version of Old Password Authentication. */
    val CLIENT_LONG_PASSWORD: Long = 1L

    /** Send found rows instead of affected rows in EOF_Packet. */
    val CLIENT_FOUND_ROWS: Long = 1L << 1

    /** Longer flags in Protocol::ColumnDefinition320. */
    val CLIENT_LONG_FLAG: Long = 1L << 2

    /** Database (schema name) can be specified on connect in Handshake Response Packet. */
    val CLIENT_CONNECT_WITH_DB: Long = 1L << 3

    /** Do not allow database.table.column. */
    val CLIENT_NO_SCHEMA: Long = 1L << 4

    /** Compression protocol supported. */
    val CLIENT_COMPRESS: Long = 1L << 5

    /** Special handling of ODBC behaviour. */
    val CLIENT_ODBC: Long = 1L << 6

    /** Can use LOAD DATA LOCAL. */
    val CLIENT_LOCAL_FILES: Long = 1L << 7

    /** Ignore spaces before '('. */
    val CLIENT_IGNORE_SPACE: Long = 1L << 8

    /** New 4.1 protocol (required for kyo-sql; always set). */
    val CLIENT_PROTOCOL_41: Long = 1L << 9

    /** This is an interactive client. */
    val CLIENT_INTERACTIVE: Long = 1L << 10

    /** Use SSL encryption for the session. */
    val CLIENT_SSL: Long = 1L << 11

    /** Client only flag; do not use. */
    val CLIENT_IGNORE_SIGPIPE: Long = 1L << 12

    /** Client knows about transactions. */
    val CLIENT_TRANSACTIONS: Long = 1L << 13

    /** DEPRECATED: Old flag for 4.1 protocol. */
    val CLIENT_RESERVED: Long = 1L << 14

    /** Old flag for 4.1 authentication. Use CLIENT_PLUGIN_AUTH instead. */
    val CLIENT_RESERVED2: Long = 1L << 15

    /** Can send multiple statements per COM_QUERY and COM_STMT_PREPARE. */
    val CLIENT_MULTI_STATEMENTS: Long = 1L << 16

    /** Can send multiple result sets for COM_QUERY. */
    val CLIENT_MULTI_RESULTS: Long = 1L << 17

    /** Can send multiple result sets for COM_STMT_EXECUTE. */
    val CLIENT_PS_MULTI_RESULTS: Long = 1L << 18

    /** Client supports plugin-based authentication. */
    val CLIENT_PLUGIN_AUTH: Long = 1L << 19

    /** Client supports connection attributes. */
    val CLIENT_CONNECT_ATTRS: Long = 1L << 20

    /** Client can send variable-length auth data in Handshake Response. */
    val CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA: Long = 1L << 21

    /** Server can send expired-password error; client must reset it before doing anything else. */
    val CLIENT_CAN_HANDLE_EXPIRED_PASSWORDS: Long = 1L << 22

    /** Server can send session-state change information in OK packet. */
    val CLIENT_SESSION_TRACK: Long = 1L << 23

    /** Client no longer expects EOF packets; OK packets replace EOF everywhere (modern default).
      *
      * kyo-sql always negotiates this flag. When set, the 0xFE ambiguity in response parsing disappears: EOF packets are never sent.
      */
    val CLIENT_DEPRECATE_EOF: Long = 1L << 24

    /** Support optional metadata in resultset. */
    val CLIENT_OPTIONAL_RESULTSET_METADATA: Long = 1L << 25

    /** Client supports zstd compression algorithm. */
    val CLIENT_ZSTD_COMPRESSION_ALGORITHM: Long = 1L << 26

    /** Support load data with compression. */
    val CLIENT_QUERY_ATTRIBUTES: Long = 1L << 27

    /** Support multi-factor authentication (MFA). */
    val MULTI_FACTOR_AUTHENTICATION: Long = 1L << 28

    /** Client or server supports progress reporting for time-consuming commands (MariaDB extension). */
    val CLIENT_PROGRESS_OBSOLETE: Long = 1L << 29

    /** Client or server supports SSL. Stored in the same position as CLIENT_REMEMBER_OPTIONS. */
    val CLIENT_SSL_VERIFY_SERVER_CERT: Long = 1L << 30

    /** Reserved for future use. */
    val CLIENT_REMEMBER_OPTIONS: Long = 1L << 31

    /** The default capability flags that kyo-sql negotiates.
      *
      * Includes protocol 4.1, transactions, plugin auth, CLIENT_DEPRECATE_EOF (which replaces EOF packets with OK everywhere), and
      * CLIENT_LOCAL_FILES (required so the server sends a 0xFB LOCAL_INFILE_REQUEST packet for LOAD DATA LOCAL INFILE statements instead of
      * rejecting them outright).
      */
    val Default: Long =
        CLIENT_LONG_PASSWORD |
            CLIENT_LONG_FLAG |
            CLIENT_CONNECT_WITH_DB |
            CLIENT_PROTOCOL_41 |
            CLIENT_TRANSACTIONS |
            CLIENT_RESERVED2 |
            CLIENT_MULTI_RESULTS |
            CLIENT_PLUGIN_AUTH |
            CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA |
            CLIENT_SESSION_TRACK |
            CLIENT_DEPRECATE_EOF |
            CLIENT_LOCAL_FILES

end Capabilities
