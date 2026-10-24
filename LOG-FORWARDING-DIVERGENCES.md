# Log forwarding: divergences from the spec

Against `mobile/shared/plans/client-log-forwarding.md` in the developer-documentation repo. Casing,
concurrency primitives and whether something is a class, object or extension are not divergences and
are not listed. Everything below either changes what the spec names, adds to it, or resolves
something the spec leaves open.

## 1. `lostBytes` is `-1` where the byte count is not knowable

**Spec**: `Frame.lostBytes` is "destroyed by rotation before we read them", the meta record's severity
is `WARNING` when `lost_bytes > 0`, and the file-lifecycle table says to "report `lostBytes`" when a
needed inode is gone.

**Here**: `lostBytes` is `-1` in the one case where the figure cannot be derived: the cursor was
`Complete` and its recorded successor was destroyed before a single byte of it was read. Nothing was
ever observed about that file, so no count exists. The meta record's severity test is `lostBytes != 0`
rather than `> 0`, the `lost_bytes` attribute carries `-1`, and the body reads `unknown bytes lost`.
The query the spec gives, `| tag="forwarder" | lost_bytes!="0"`, still selects it.

**Why**: the alternative is reporting `0`, which the spec itself warns against reading as "no gap".

## 2. The cursor document carries `size`

**Spec**: the cursor document holds `file`, `offset`, `committed`, `successor`, `chased` and
`declaredTimezone`, and says "`complete` is not stored. It follows from `successor` being set and
`committed` standing at EOF."

**Here**: the cursor document also holds `size`, the tracked file's end as last observed, updated in
the same durable write as the offset. `complete` is still derived, from `successor != null &&
committed >= size`.

**Why**: the file-lifecycle table needs `Complete` for a file that is *gone*, and EOF is not readable
once the inode is unlinked. Without a persisted size the two rows "`Complete` no, its own file,
absent" (loss) and "`Complete` yes, successor present" (no gap) cannot be told apart across a process
restart, which is the only way that case arises. The same field makes divergence 1's count exact for
the common case, leaving `-1` for the destroyed-successor case alone.

## 3. `read` never passes `acceptWindowEnd` true; the chase closes a superseded file

**Spec**, two statements that cannot both hold: "`read` ... passes `acceptWindowEnd` false", and
"A superseded file's EOF is final, so the read ships whatever is there, truncated or not." The last
record of a superseded file is bounded by nothing, so with `acceptWindowEnd` false it is never
returned and would be skipped when the reader moves on.

**Here**: `read` always passes false, as the spec states. A superseded file whose trailing record is
unbounded comes back `HoldingPartial` with a non-null `successor`, which the run loop already chases
immediately and without the settle interval. `readLastElseRead` takes that record and returns
`FileComplete` rather than `CaughtUp`, so the run moves on to the successor inside the same pass
instead of ending.

Consequence: `Cursor.Chased`, which the spec derives from `status == CaughtUp`, is false for a
superseded file's chase even though that span was chased. It is only read as a diagnostic for the
next read beginning on headerless text, and after a file change the next read begins at offset 0 on a
header, so nothing depends on it there.

## 4. Trailing headerless bytes on a superseded file are consumed

**Spec** covers headerless text by seeking forward to the next header. It does not say what happens
when a superseded file *ends* in headerless bytes, which is reachable: a chase ships a truncated
record and commits past it, leaving the remainder with no header, and the file then rotates.

**Here**: the reader advances past them to EOF, reports the discard at `WARNING` through the normal
logging API like any other orphan, and returns `FileComplete`. Taken literally the spec loops: the
file can never reach `Complete`, so the reader re-reads the same span forever.

On the active file the same situation returns `CaughtUp` with nothing consumed, because the bytes may
still turn out to be the head of a record that is still being written.

## 5. `read` and `readLastElseRead` return null where there is no log file

**Spec**: both return a `Frame`, and `Frame.cursor` is non-null.

**Here**: both return `Frame?`. Null means no log file exists to read from at all, so there is no
cursor for a frame to carry. The run loop treats it as the end of the run. In this app the condition
is unreachable once `Factory.enableLogCollection` has run, which is before anything else in
`LinphoneApplication.onCreate`.

## 6. `FileId` is sourced from `Os.stat`/`Os.fstat`, not `fileKey()`

**Spec**: "Identity is the inode: `fileKey()` on Android".

**Here**: `android.system.Os.stat` for a path and `Os.fstat` for an open descriptor, rendered as
`(dev=<st_dev>,ino=<st_ino>)`. That is byte-for-byte what `UnixFileKey.toString()` produces, so
`cursor_file` on the wire is unchanged.

**Why**: `InodeUtils.idOf(handle)` is specified over an *open descriptor*, which is the whole point of
addressing by identity, and NIO's `fileKey()` is only reachable from a path. One source for both
keeps them comparable.

`FileId` stays opaque: it wraps the rendered string and is never parsed. Persisting and reloading it
round-trips that string rather than its fields.

## 7. Signatures that carry an argument the spec's caller does not have

- `Outbox.write(frameId, bodies)` rather than `write(frame, bodies)`. The directory is named for the
  frame id, which the formatter computes and the `Frame` does not carry; `RenderedFrame` gained a
  `frameId` field to hand it over.
- `Outbox.drop(body, id)` rather than `drop(file)`. The spec requires the drop to be by identity;
  Android has no unlink-by-inode, so the identity is verified with a stat before the path is
  unlinked. Passing null skips the check, which is what the split path does for a body it has just
  written itself.
- `Outbox.split(file)` returns `File?`. Null is the spec's "a single-record body cannot split: drop
  it", which it does before returning.
- `LogReader` takes a `DocumentStore`, and `LogForwarder` takes `url`, `store`, `settings`, `replay`
  and a `rebuild` factory, alongside the five the spec names. These are the collaborators the
  described behaviour needs; nothing the spec names was removed or renamed.
- `Frame` carries `firstRecord`, the first record the frame traversed, kept or filtered. The meta
  record is "timestamped at the first record the frame traversed, kept or filtered", and
  `Frame.records` is kept-only, so the formatter has no other way to reach it.

## 8. `Log.setLogDomain("linphone-app")` is set at startup

**Spec** states as fact that the app layer's subsystem is `linphone-app`, "on Android through
`Log.setLogDomain`". It was not: the SDK wrapper's default, `linphone-android`, was in force, because
nothing called `Log.setLogDomain` and `Factory.setLoggerDomain` governs a different path.

**Here**: `LinphoneApplication.onCreate` calls it, before the first `Log.i`. Without it the timezone
anchors and the forwarder's own lines carry a subsystem that does not match the meta record's, and
the spec's closed subsystem vocabulary does not hold. This is outside the scope given for this work,
which named only the capture level, so it is recorded here as well as in the commit.

## 9. Tenant and extension are read from linphonerc, not from a Core

The spec puts `tenant` and `extension` on the meta record without saying where they come from, and
says the forwarder reads its settings "from a `Config`, not from a Core". The same is done for these:
`sip/default_proxy` selects the proxy section and `reg_identity` is parsed into user and domain. A
background run therefore needs no Core and no Core-thread hop.

## 10. Values the spec leaves to the implementation

Recorded so the two platforms can be compared rather than because they conflict with anything.

- Initial read window 256 KB, doubling where a record does not fit, capped at 32 MB.
- Dispatch backoff: base 10 s, doubling, +/-20% jitter, capped at `max_backoff`. `Retry-After`
  replaces the computed value where present.
- The three documents and the outbox live in `filesDir/log-forwarding/`, excluded from cloud backup
  and device transfer.
- The binary marker is `<Skipped N bytes of binary data>`.
- The meta record's record count is the number of records the frame ships.
- `frame_id` hashes the file id, the two offsets, the sorted settings terms and the content records
  rendered without their own `frame_id` attribute, newline-separated.
