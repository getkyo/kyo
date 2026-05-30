# Phase 21d Decisions

## Section header byte layout

Each entry in the TASTy section table follows the format:

```
Section = NameRef Length Bytes
```

- `NameRef`: a base-128 Nat (readNat). Last byte has 0x80 SET; continuation bytes have 0x80 CLEAR.
- `Length`: a base-128 Nat (readNat) giving the payload byte count.
- `Bytes`: the raw section payload, `Length` bytes long.

Both `NameRef` and `Length` use the same single-byte encoding when the value is less than 128
(value | 0x80 in the last byte). The test uses values 0, 1, 10, and 20, all fitting in one byte.

Offset arithmetic for the two-section test:
- Section 1 header: 2 bytes (offset 0..1). Payload starts at offset 2, length=10.
- Section 2 header: 2 bytes (offset 12..13). Payload starts at offset 14, length=20.

## Lookup return-type reconciliation

`SectionIndex.get(name: String): Maybe[(Int, Int)]`

The plan referred to the method as `lookup`; the actual public API is `get`. The return type is
`Maybe[(Int, Int)]` using kyo's `Present`/`Absent` (not `Option`/`Some`/`None`). The test uses
`Present((offset, length))` and `Absent` accordingly, satisfying [[feedback_json_use_option]].

The internal storage is `Map[String, (Int, Int)]` populated by `readSync`; `get` wraps the
`Option` from `Map.get` into `Maybe` via a pattern match.
