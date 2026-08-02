package kyo.internal.mysql

/** MySQL client capability flags negotiated during the handshake.
  *
  * These bit flags are exchanged in the HandshakeV10 / HandshakeResponse41 packets. The server advertises what it supports; the client
  * picks a subset and echoes it back. The intersection governs the rest of the session.
  *
  * Only the flags kyo-sql negotiates or inspects are defined here, so a bit position with no entry is one the client neither sends nor
  * reads. Each flag keeps its protocol bit position, which is fixed by the wire format and never derived from the order of the entries
  * below.
  *
  * Reference: MySQL Internals Manual, Capability Flags (§14.1.3.1)
  *
  * Note: [[CLIENT_DEPRECATE_EOF]] is the modern default, when negotiated, EOF packets are replaced by OK packets everywhere, eliminating
  * the 0xFE ambiguity for payloads >= 9 bytes. kyo-sql always negotiates this flag.
  */
object Capabilities:

    /** Use the improved version of Old Password Authentication. */
    val CLIENT_LONG_PASSWORD: Long = 1L

    /** Longer flags in Protocol::ColumnDefinition320. */
    val CLIENT_LONG_FLAG: Long = 1L << 2

    /** Database (schema name) can be specified on connect in Handshake Response Packet. */
    val CLIENT_CONNECT_WITH_DB: Long = 1L << 3

    /** Can use LOAD DATA LOCAL. */
    val CLIENT_LOCAL_FILES: Long = 1L << 7

    /** New 4.1 protocol (required for kyo-sql; always set). */
    val CLIENT_PROTOCOL_41: Long = 1L << 9

    /** Use SSL encryption for the session. */
    val CLIENT_SSL: Long = 1L << 11

    /** Client knows about transactions. */
    val CLIENT_TRANSACTIONS: Long = 1L << 13

    /** Old flag for 4.1 authentication. Use CLIENT_PLUGIN_AUTH instead. */
    val CLIENT_RESERVED2: Long = 1L << 15

    /** Can send multiple result sets for COM_QUERY. */
    val CLIENT_MULTI_RESULTS: Long = 1L << 17

    /** Client supports plugin-based authentication. */
    val CLIENT_PLUGIN_AUTH: Long = 1L << 19

    /** Client can send variable-length auth data in Handshake Response. */
    val CLIENT_PLUGIN_AUTH_LENENC_CLIENT_DATA: Long = 1L << 21

    /** Server can send session-state change information in OK packet. */
    val CLIENT_SESSION_TRACK: Long = 1L << 23

    /** Client no longer expects EOF packets; OK packets replace EOF everywhere (modern default).
      *
      * kyo-sql always negotiates this flag. When set, the 0xFE ambiguity in response parsing disappears: EOF packets are never sent.
      */
    val CLIENT_DEPRECATE_EOF: Long = 1L << 24

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
