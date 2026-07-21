package kyo.internal.postgres.unmarshaller

import kyo.*
import kyo.SqlException
import kyo.internal.postgres.Authentication
import kyo.internal.postgres.AuthenticationKind
import kyo.internal.postgres.PostgresBufferReader
import kyo.internal.postgres.Unmarshaller
import scala.annotation.tailrec

/** Unmarshaller for the [[Authentication]] message family.
  *
  * All authentication variants share type byte `'R'` and are discriminated by an Int32 sub-type code: 0 → AuthenticationOk 2 → KerberosV5 3
  * → CleartextPassword 5 → MD5Password (4-byte salt follows) 6 → SCMCredential 7 → GSS 8 → GSSContinue (data follows) 9 → SSPI 10 → SASL
  * (list of NUL-terminated mechanism names, double-NUL terminated) 11 → SASLContinue (data follows) 12 → SASLFinal (data follows)
  *
  * The reader passed in covers the message body only (after type byte and length have already been consumed by the dispatcher).
  *
  * Reference: PostgreSQL §55.7 "AuthenticationXxx"
  */
object AuthenticationUnmarshaller extends Unmarshaller[Authentication]:

    def read(buf: PostgresBufferReader)(using Frame): Authentication < Abort[SqlException.Decode] =
        buf.readInt32().flatMap { subType =>
            val kind: AuthenticationKind < Abort[SqlException.Decode] = subType match
                case 0 => AuthenticationKind.Ok
                case 2 => AuthenticationKind.KerberosV5
                case 3 => AuthenticationKind.CleartextPassword
                case 5 =>
                    buf.readBytes(4).map { salt =>
                        AuthenticationKind.MD5Password(salt)
                    }
                case 6 => AuthenticationKind.SCMCredential
                case 7 => AuthenticationKind.GSS
                case 8 =>
                    val data = buf.readAll()
                    AuthenticationKind.GSSContinue(data)
                case 9  => AuthenticationKind.SSPI
                case 10 =>
                    // List of NUL-terminated mechanism names, terminated by an extra NUL
                    readMechanisms(buf, Chunk.empty).map(AuthenticationKind.SASL(_))
                case 11 =>
                    val data = buf.readAll()
                    AuthenticationKind.SASLContinue(data)
                case 12 =>
                    val data = buf.readAll()
                    AuthenticationKind.SASLFinal(data)
                case n =>
                    Abort.fail(SqlException.Decode(s"Unknown Authentication sub-type: $n", Maybe.Absent, summon[Frame]))
            kind.map(Authentication(_))
        }
    end read

    private def readMechanisms(buf: PostgresBufferReader, acc: Chunk[String])(using
        Frame
    ): Chunk[String] < Abort[SqlException.Decode] =
        if buf.remaining == 0 then acc
        else
            val mech = buf.readString()
            if mech.isEmpty then acc // double-NUL: empty string from readString means we hit the terminator NUL
            else readMechanisms(buf, acc.appended(mech))

end AuthenticationUnmarshaller
