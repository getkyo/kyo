package kyo.internal.crypto

import kyo.*
import scala.annotation.tailrec

/** Ed25519 signature verification (RFC 8032 section 5.1.7), over `BigInt` field arithmetic shared by every platform.
  *
  * A signature `R || S` verifies under the public key `A` when `S` is below the group order `L`, both `A` and `R` decode to curve points
  * under the strict decoding of section 5.1.3 (a `y` not below `p`, a `y` with no matching `x`, and `x = 0` with the sign bit set are all
  * rejected), and `[S]B = R + [k]A` holds for `k = SHA-512(R || A || M) mod L`.
  *
  * IMPORTANT: the group equation is the cofactorless one. RFC 8032 also allows `[8][S]B = [8]R + [8][k]A`; the two differ only when `A`
  * or `R` carries a small-order component, and the cofactorless equation is the one used by the verifiers Discord documents for its
  * interactions endpoint (tweetnacl, libsodium, Tink) and by Go, BoringSSL and OpenSSL. Changing it changes which forged edge cases
  * verify, not which honest signatures do. Small-order and mixed-order `A` and `R` are accepted whenever the equation holds, as RFC 8032
  * has no small-order blocklist: of the ed25519-speccheck cases, 0 to 3 verify and 4 to 11 do not, the row BouncyCastle and Hacl* answer;
  * libsodium additionally rejects cases 0 to 2 through its own blocklist.
  *
  * Note: verification handles only public values (the key, the message and the signature), so it is not constant time and does not need
  * to be.
  */
private[kyo] object Ed25519:

    private val P: BigInt = (BigInt(1) << 255) - 19

    private val L: BigInt = (BigInt(1) << 252) + BigInt("27742317777372353535851937790883648493")

    private val D: BigInt = (BigInt(-121665) * inverse(BigInt(121666))).mod(P)

    private val D2: BigInt = (D * 2).mod(P)

    private val SqrtMinusOne: BigInt = BigInt(2).modPow((P - 1) / 4, P)

    private val SqrtExponent: BigInt = (P - 5) / 8

    final private[crypto] case class Point(x: BigInt, y: BigInt, z: BigInt, t: BigInt)

    private val Identity: Point = Point(BigInt(0), BigInt(1), BigInt(1), BigInt(0))

    private val Base: Point =
        val y = (BigInt(4) * inverse(BigInt(5))).mod(P)
        recoverX(y, sign = false)
            .map(x => Point(x, y, BigInt(1), (x * y).mod(P)))
            .getOrElse(throw new IllegalStateException("the Ed25519 base point does not decode"))
    end Base

    /** Whether `signature` is a valid Ed25519 signature of `message` under `publicKey`. A key that is not 32 bytes or a signature that is
      * not 64 bytes is not valid. No argument is modified.
      */
    def verify(publicKey: Array[Byte], message: Array[Byte], signature: Array[Byte]): Boolean =
        publicKey.length == 32 && signature.length == 64 && {
            val s = littleEndian(signature, 32, 32)
            s < L && decode(publicKey, 0).flatMap { a =>
                decode(signature, 0).map { r =>
                    val encodedR = java.util.Arrays.copyOfRange(signature, 0, 32)
                    val k        = littleEndian(Sha512.hashChunks(Seq(encodedR, publicKey, message)), 0, 64).mod(L)
                    samePoint(multiply(s, Base), add(r, multiply(k, a)))
                }
            }.getOrElse(false)
        }

    private[crypto] def decode(bytes: Array[Byte], offset: Int): Maybe[Point] =
        val sign = (bytes(offset + 31) & 0x80) != 0
        val y    = littleEndian(bytes, offset, 32).clearBit(255)
        if y >= P then Absent
        else recoverX(y, sign).map(x => Point(x, y, BigInt(1), (x * y).mod(P)))
    end decode

    private def recoverX(y: BigInt, sign: Boolean): Maybe[BigInt] =
        val y2        = (y * y).mod(P)
        val u         = (y2 - 1).mod(P)
        val v         = (D * y2 + 1).mod(P)
        val v3        = (v * v * v).mod(P)
        val v7        = (v3 * v3 * v).mod(P)
        val candidate = (u * v3 * (u * v7).modPow(SqrtExponent, P)).mod(P)
        val check     = (v * candidate * candidate).mod(P)
        val root      =
            if check == u then Present(candidate)
            else if check == (-u).mod(P) then Present((candidate * SqrtMinusOne).mod(P))
            else Absent
        root.flatMap { x =>
            if x == 0 && sign then Absent
            else if x.testBit(0) != sign then Present(P - x)
            else Present(x)
        }
    end recoverX

    private def add(p: Point, q: Point): Point =
        val a = ((p.y - p.x) * (q.y - q.x)).mod(P)
        val b = ((p.y + p.x) * (q.y + q.x)).mod(P)
        val c = (p.t * D2 * q.t).mod(P)
        val d = (p.z * 2 * q.z).mod(P)
        val e = b - a
        val f = d - c
        val g = d + c
        val h = b + a
        Point((e * f).mod(P), (g * h).mod(P), (f * g).mod(P), (e * h).mod(P))
    end add

    private def multiply(scalar: BigInt, point: Point): Point =
        @tailrec def loop(bit: Int, acc: Point): Point =
            if bit < 0 then acc
            else
                val doubled = add(acc, acc)
                loop(bit - 1, if scalar.testBit(bit) then add(doubled, point) else doubled)
        loop(scalar.bitLength - 1, Identity)
    end multiply

    private def samePoint(p: Point, q: Point): Boolean = (p.x * q.z - q.x * p.z).mod(P) == 0 && (p.y * q.z - q.y * p.z).mod(P) == 0

    private def littleEndian(bytes: Array[Byte], offset: Int, length: Int): BigInt =
        BigInt(1, Array.tabulate(length)(i => bytes(offset + length - 1 - i)))

    private def inverse(value: BigInt): BigInt =
        value.modPow(P - 2, P)

end Ed25519
