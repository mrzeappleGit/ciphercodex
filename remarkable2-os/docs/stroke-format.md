# CipherCodex stroke format (open format, schema v1)

Strokes live in a plain SQLite database at `/home/root/ciphercodex/data.db`
(WAL journal, `synchronous=FULL`, foreign keys ON). Anyone can read it with
stock `sqlite3` plus the BLOB layout below — no proprietary container.

## Schema (v1, `schema_version` table records the version)

```sql
CREATE TABLE schema_version(version INTEGER NOT NULL);
CREATE TABLE notebooks(id INTEGER PRIMARY KEY, title TEXT NOT NULL,
  created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE TABLE pages(id INTEGER PRIMARY KEY, notebook_id INTEGER NOT NULL
  REFERENCES notebooks(id) ON DELETE CASCADE, seq INTEGER NOT NULL);
CREATE TABLE strokes(id INTEGER PRIMARY KEY, page_id INTEGER NOT NULL
  REFERENCES pages(id) ON DELETE CASCADE, tool INTEGER NOT NULL DEFAULT 0,
  base_width REAL NOT NULL, points BLOB NOT NULL, created_at INTEGER NOT NULL);
CREATE INDEX strokes_page ON strokes(page_id);
```

Timestamps are milliseconds since the Unix epoch. `tool`: 0 = pencil
(1 = eraser reserved for later phases). `base_width` is the nominal stroke
width in pixels at full pressure; rendering applies the squared pressure curve.

## `points` BLOB

Little-endian packed records, **18 bytes per point**, no header, no padding.
Point count = `length(points) / 18`.

| Offset | Type | Field    | Meaning |
|--------|------|----------|---------|
| 0      | f32  | x        | page-normalized 0..1 |
| 4      | f32  | y        | page-normalized 0..1 |
| 8      | u16  | pressure | 0–4095 (Wacom range, measured on device) |
| 10     | i16  | tilt_x   | −9000..9000 |
| 12     | i16  | tilt_y   | −9000..9000 |
| 14     | u32  | t_ms     | milliseconds since stroke start |

Coordinates are page-normalized 0..1 on a portrait page of 1404×1872 aspect
(226 DPI panel). `t_ms` is relative to the first point of the stroke, so
strokes replay independently of wall-clock time.

Note: the Phase 1 contract prose said "16 bytes/point"; its field list
(authoritative) totals 18 bytes, which is what is implemented here.
