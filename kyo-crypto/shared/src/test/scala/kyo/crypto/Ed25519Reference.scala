package kyo.crypto

import kyo.*
import scala.annotation.tailrec

/** The `BigInt` Ed25519 of RFC 8032 sections 5.1.5 to 5.1.7, kept in the test sources as the oracle for the limb implementation.
  *
  * `verify` computes what `Ed25519.verify` computed with `BigInt` coordinates: strict decoding of the key and of `R`, `S < L`, the
  * cofactorless equation. `sign` and `publicKey` are the RFC's key generation and signing over the same arithmetic; they exist only so
  * tests can produce honest signatures under generated keys, and they validate themselves by reproducing the section 7.1 signatures
  * from the vendored RFC's secret keys. Nothing here is constant time, and nothing here handles a secret outside a test.
  */
object Ed25519Reference:

    val P: BigInt = (BigInt(1) << 255) - 19

    val L: BigInt = (BigInt(1) << 252) + BigInt("27742317777372353535851937790883648493")

    val D: BigInt = (BigInt(-121665) * inverse(BigInt(121666))).mod(P)

    val D2: BigInt = (D * 2).mod(P)

    val SqrtMinusOne: BigInt = BigInt(2).modPow((P - 1) / 4, P)

    private val SqrtExponent: BigInt = (P - 5) / 8

    final case class Point(x: BigInt, y: BigInt, z: BigInt, t: BigInt)

    val Identity: Point = Point(BigInt(0), BigInt(1), BigInt(1), BigInt(0))

    val Base: Point =
        val y = (BigInt(4) * inverse(BigInt(5))).mod(P)
        recoverX(y, sign = false)
            .map(x => Point(x, y, BigInt(1), (x * y).mod(P)))
            .getOrElse(throw new IllegalStateException("the Ed25519 base point does not decode"))
    end Base

    /** RFC 8032 section 5.1.7 over the raw key bytes, exactly as the `BigInt` verifier answered. */
    def verify(publicKey: Array[Byte], message: Array[Byte], signature: Array[Byte]): Boolean =
        publicKey.length == 32 && signature.length == 64 && {
            val s = littleEndian(signature, 32, 32)
            s < L && decode(publicKey, 0).flatMap { a =>
                decode(signature, 0).map { r =>
                    val encodedR = java.util.Arrays.copyOfRange(signature, 0, 32)
                    val k        = littleEndian(Sha512.hashArrays(Chunk(encodedR, publicKey, message)), 0, 64).mod(L)
                    samePoint(multiply(s, Base), add(r, multiply(k, a)))
                }
            }.getOrElse(false)
        }

    /** The public key of a 32-byte secret, RFC 8032 section 5.1.5. */
    def publicKey(secretKey: Array[Byte]): Array[Byte] =
        encode(multiply(scalar(secretKey), Base))

    /** The signature of `message` under a 32-byte secret, RFC 8032 section 5.1.6. */
    def sign(secretKey: Array[Byte], message: Array[Byte]): Array[Byte] =
        val hash   = Sha512.hashArray(secretKey)
        val s      = scalar(secretKey)
        val prefix = java.util.Arrays.copyOfRange(hash, 32, 64)
        val a      = encode(multiply(s, Base))
        val r      = littleEndian(Sha512.hashArrays(Chunk(prefix, message)), 0, 64).mod(L)
        val bigR   = encode(multiply(r, Base))
        val k      = littleEndian(Sha512.hashArrays(Chunk(bigR, a, message)), 0, 64).mod(L)
        val bigS   = (r + k * s).mod(L)
        bigR ++ littleEndianBytes(bigS, 32)
    end sign

    /** The clamped scalar of a secret: the low 32 bytes of its SHA-512 with bits 0 to 2 cleared, bit 254 set and bit 255 cleared. */
    def scalar(secretKey: Array[Byte]): BigInt =
        val hash  = Sha512.hashArray(secretKey)
        val bytes = java.util.Arrays.copyOfRange(hash, 0, 32)
        bytes(0) = (bytes(0) & 0xf8).toByte
        bytes(31) = ((bytes(31) & 0x7f) | 0x40).toByte
        littleEndian(bytes, 0, 32)
    end scalar

    /** The 32-byte encoding of section 5.1.2: `y` little-endian with the parity of `x` in bit 255. */
    def encode(point: Point): Array[Byte] =
        val zInverse = inverse(point.z)
        val x        = (point.x * zInverse).mod(P)
        val y        = (point.y * zInverse).mod(P)
        val bytes    = littleEndianBytes(y, 32)
        if x.testBit(0) then bytes(31) = (bytes(31) | 0x80).toByte
        bytes
    end encode

    def decode(bytes: Array[Byte], offset: Int): Maybe[Point] =
        if offset < 0 || bytes.length - offset < 32 then Absent
        else
            val sign = (bytes(offset + 31) & 0x80) != 0
            val y    = littleEndian(bytes, offset, 32).clearBit(255)
            if y >= P then Absent
            else recoverX(y, sign).map(x => Point(x, y, BigInt(1), (x * y).mod(P)))
        end if
    end decode

    def recoverX(y: BigInt, sign: Boolean): Maybe[BigInt] =
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

    def add(p: Point, q: Point): Point =
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

    def negate(p: Point): Point = Point((-p.x).mod(P), p.y, p.z, (-p.t).mod(P))

    def multiply(scalar: BigInt, point: Point): Point =
        @tailrec def loop(bit: Int, acc: Point): Point =
            if bit < 0 then acc
            else
                val doubled = add(acc, acc)
                loop(bit - 1, if scalar.testBit(bit) then add(doubled, point) else doubled)
        loop(scalar.bitLength - 1, Identity)
    end multiply

    def samePoint(p: Point, q: Point): Boolean = (p.x * q.z - q.x * p.z).mod(P) == 0 && (p.y * q.z - q.y * p.z).mod(P) == 0

    def littleEndian(bytes: Array[Byte], offset: Int, length: Int): BigInt =
        val bigEndian = new Array[Byte](length)
        var i         = 0
        while i < length do
            bigEndian(i) = bytes(offset + length - 1 - i)
            i += 1
        end while
        BigInt(1, bigEndian)
    end littleEndian

    /** `value` as exactly `length` little-endian bytes; `value` is below `2^(8 length)`. */
    def littleEndianBytes(value: BigInt, length: Int): Array[Byte] =
        val out = new Array[Byte](length)
        var i   = 0
        while i < length do
            out(i) = ((value >> (8 * i)) & 0xff).toByte
            i += 1
        end while
        out
    end littleEndianBytes

    def inverse(value: BigInt): BigInt =
        value.modPow(P - 2, P)

end Ed25519Reference
