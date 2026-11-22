# Note store

The note store keeps opaque records ("notes") of roughly 1–8 KB each on the card, next to the keys
that own them, so that the card alone can hold a complete wallet. It is a separate Java Card package,
`im.status.keycard.notes` (`notes/src`), reached only through the Keycard applet. The applet-side
commands are in `KeycardApplet.processNote`; the tests are in `NoteStoreTest`.

## What the store is, and is not

**It is byte custody.** The card keeps the bytes and returns them unchanged. A note is already signed
by whoever issued it, and its owner key is derived from the seed. The card never parses a note,
never checks its signatures and never assembles one. The host verifies a note before writing it and
after reading it.

**It is not secret storage.** A note without its owner key cannot be spent. The store is still
PIN-gated end to end, because the list of notes reveals what the holder owns.

**Non-goals:** parsing note formats, enforcing spend rules, and synchronising with other devices.
Spend authorizations are signed with the existing SIGN command, which the store does not change.

## Constraints it is designed for

The defaults are sized for an NXP J3R200 (JCOP4 P71):

| Constraint | Value | Consequence |
|---|---|---|
| Free NVM with the Keycard applet installed | about 85 KB | The pool is about 64 KB, which leaves room for code growth |
| Free `CLEAR_ON_RESET` RAM | under 100 B | **No new transient buffers.** I/O goes through the APDU buffer, a global array |
| Command payload in Secure Channel V2 | 255 − 5 (inner header) − 8 (CCM tag) = 242 B | Write chunks carry at most 240 B of data |
| Response payload in Secure Channel V2 | 247 − 2 (inner status word) = 245 B | Read chunks return at most 240 B |
| Short APDUs only | 255-byte class | A 1.5 KB note takes about 7 commands each way |
| No garbage collection | — | The whole pool is allocated once, at install |
| NFC tearing | The card can lose power at any point | Every multi-APDU operation must survive a tear at any point |

## Design

### A separate package

Java Card 3.0.5 has no in-place applet upgrade. Every update of the Keycard applet is a
GlobalPlatform delete plus a reinstall, which destroys all of the applet's persistent data. For the
seed that is acceptable, because it comes back from the recovery phrase. Notes come back only from
the wallet's own backup, and a note received since the last backup exists only on the card. So the
store is a separate package with a frozen, minimal interface (`NoteStore`). The Keycard package
depends on it; it depends on nothing. The Keycard applet can be replaced as often as needed while
the notes stay.

- **How it is reached.** The host talks only to the Keycard applet, which owns the secure channel
  and the PIN. The Keycard applet checks its own state and calls the store through the `Shareable`
  interface `NoteStore`. The store hands that interface only to clients whose AID starts with the
  Keycard applet's AID (`getShareableInterfaceObject`); the Cash and Ident applets in the same
  package have no PIN and do not get it. Data crosses the firewall in the APDU buffer.
- **Crypto stays in the Keycard applet.** The SHA-256 at COMMIT is computed by the Keycard applet
  with its existing `MessageDigest`, reading the note through the interface. The store has no
  crypto objects and needs no RAM for crypto state.
- **The cost:** one more package and applet instance, a few KB of NVM, and an interface that never
  changes. A changed interface means a reinstalled store, which loses every note. That is why the
  interface is minimal.

### Fixed 256-byte blocks with a chain table

The pool is `B` blocks of 256 bytes plus a `next[B]` table of shorts (`0x7FFF` ends a chain). A note
of `len` bytes uses `ceil(len / 256)` blocks chained from its directory entry. The largest note is
32 blocks, 8,192 bytes.

**There is no free list.** A block is free exactly when no live entry's chain reaches it. BEGIN
finds free blocks by walking the live chains, at most `B` steps, and marking a bitmap in the APDU
buffer past the command data (`B/8` bytes: 31 B for 248 blocks). Delete and wipe never touch the
chain table, and nothing has to be kept consistent across a tear. All blocks are the same size, so
the pool never fragments; the waste averages 128 B per note. Fixed-size slots would either refuse
large notes or waste most of their space on small ones.

Block storage is a set of `byte[8192]` arrays of 32 blocks each. That keeps every array under Java
Card's 32,767-byte limit and avoids per-object overhead for each block.

### Two-phase, resumable, tear-safe writes

1. **BEGIN** `(len, tag, meta)` picks free blocks and writes their chain cells non-atomically. A
   tear here leaves those cells unreachable, so they stay free. It then writes the entry as
   `WRITING` in one atomic step. It is refused if the tag is already held.
2. **WRITE** `(entry, offset, ≤240 B)` copies data into the blocks non-atomically. Writes are
   **addressed by offset**, so they can be sent in any order and repeated. After a tear the host
   lists the store, finds the `WRITING` entry and resends the missing chunks.
3. **COMMIT** `(entry, sha256)`: the Keycard applet hashes the `len` bytes and compares the result
   with the hash sent. The hash travels with COMMIT rather than BEGIN because the host has the note
   in hand, and storing a hash per entry would cost 32 B per entry. On a match the entry flips to
   `HELD` in one atomic write. A mismatch returns `6A80` and the entry stays `WRITING`.

A note becomes `HELD` only through that single atomic flip, so a tear can never leave a half-written
note that looks valid:

| Torn during | State afterwards | Recovery |
|---|---|---|
| BEGIN | The entry was not written, so the chain cells are unreachable and still free | Repeat BEGIN |
| WRITE | Entry `WRITING`, some blocks partly written | Resend chunks (the hash check catches gaps) |
| COMMIT | The flip is atomic: either `WRITING` or `HELD` | LIST shows which |
| DELETE | One status byte, atomic: either intact or gone | LIST shows which |
| Wipe (FACTORY RESET) | The epoch, atomic | Nothing to recover |

DELETE with the abort flag frees a `WRITING` entry. BEGIN does **not** reclaim stale `WRITING`
entries on its own, because they may be a resumable upload; the host decides when to abort them.

### Directory entry: 39 + M bytes

`M` is the meta length, an install parameter.

| Field | Size | Meaning |
|---|---|---|
| status | 1 | `FREE` (0), `WRITING` (1) or `HELD` (2) in the low bits. The top bit is the owning tree: 0 = main PIN, 1 = duress PIN |
| epoch | 2 | Store epoch at write. An entry whose epoch is not the current one is dead. When the epoch wraps (the 65,536th wipe), that wipe also sets every entry's status to `FREE`, so entries from the old epoch 0 cannot come back |
| len | 2 | Note length in bytes |
| first | 2 | First block of the chain |
| tag | 32 | Unique key chosen by the host. Entry indexes are not stable across a delete and re-add, so the tag is a note's identity |
| meta | M (16 by default) | Opaque to the card, rewritable with SET META |

`meta` lets a host show a balance or find a note without reading every note. It is the only field
the host can change after COMMIT, and the change is atomic.

### Access control

- **Every** command requires an open secure channel and a verified PIN, including LIST and STATUS:
  the same gate as SIGN and EXPORT KEY. Without a verified PIN the store returns `6985`.
- **The duress PIN hides the notes; it does not wipe them.** This matches standard Keycard, where
  the duress PIN opens a decoy wallet and destroys nothing. Each note records the tree that wrote it
  (main or duress PIN) in its status byte. Each tree:
  - lists, reads, counts and deletes only its own notes;
  - gets `6A88` (not found) for the other tree's entry indexes;
  - compares tags only against its own notes. Refusing a duplicate the other tree holds would reveal
    that the note exists.

  The duress PIN also derives decoy Ed25519 keys (see [ED25519.md](ED25519.md)), so the authority to
  spend stays hidden along with the bytes.
- **The duress tree has a fixed share, so its view gives nothing away.** It owns 1/8 of the blocks
  and entries (at least 1 of each; 31 blocks and 8 entries at the defaults); the main tree owns the
  rest. Each tree's STATUS is computed from its own share and its own notes only, and its share is
  the limit at BEGIN. Whatever the main tree holds, the duress PIN sees the same empty store with
  full free space. Without the share, the totals would be capacity minus the hidden notes, and anyone
  who knows the default sizes could do the arithmetic. The cost is about 12% of the main tree's
  capacity.
  - **Residual:** BEGIN walks every live chain to find free blocks, so its time varies slightly with
    what the card holds. The difference is far below NFC framing noise, but it is not exactly zero.
- **Limits inherent to a duress PIN.** Someone holding the duress PIN can deny the holder access but
  cannot reach the real wallet:
  - **Lock-out:** wrong guesses after a duress unlock still count against the main PIN, and the
    duress PIN does not reset that counter. A PUK changed from a duress session can only unblock the
    duress PIN. Notes and keys then come back from the wallet backup and the recovery phrase.
  - **Wipe:** FACTORY RESET is allowed under the duress PIN as under the main PIN, so the two stay
    indistinguishable. It wipes both trees.
- **FACTORY RESET wipes the store**, both trees, with one epoch increment. It needs a verified PIN
  unless the card is uninitialized or both PIN and PUK are blocked.

### Owner-index counter

A wallet that derives a fresh owner key per received note must never reuse an index: reuse points
two payers at one key and links their payments. A host with no state of its own cannot pick the next
index from the notes on the card. "1 + the highest index held" hands out a used index again as soon
as the highest-index note is deleted, or after a receive that was shown but never stored. So the
store keeps the counter:

- **NEXT INDEX** returns the next index and advances the counter. The advance is persisted, in one
  atomic write, **before** the response, so a response lost to a tear only skips an index; an index
  is never handed out twice. Gaps cost nothing; duplicates cost privacy. Every call spends an index,
  so a host that only wants to read the counter uses ADVANCE INDEX with 0.
- **ADVANCE INDEX** raises the counter to at least a value and never lowers it, so a host that
  issued indexes itself can bring the card up to date. A value of 0 just reads the counter.
- **One counter per tree,** so the duress PIN's indexes say nothing about the main tree.
- **It starts at 1** (0 is reserved for a reusable receive key) and stops after `0x7FFFFFFF`, the
  largest index a hardened path component can carry (`6A84` after that).
- **It is never reset:** not by FACTORY RESET and not by the duress PIN. A card restored from the
  same phrase would otherwise restart at 1 and reuse old indexes. Only reinstalling the store
  package resets it, the same event that loses the notes, so after a reinstall the host must
  ADVANCE it past every index it knows.

### Allocation and sizing

The pool is allocated when the store applet is installed, sized by the optional install parameters
(GlobalPlatform `C9` data) `blocks:2 entries:1 metaLen:1`. Limits: 2–1024 blocks, 2–254 entries, meta
length 0–64. Values outside them make the install fail with `6A80`; a pool too large for the card
fails the install with the platform's allocation error. Either way no half-built store is left on
the card. The wire format carries `metaLen` (STATUS reports
it; LIST and SET META use it), so changing the sizes is a reinstall, not an interface change.

Install the store package **before** the Keycard package, which imports it. `./gradlew install` does
this and keeps an existing store; see [Build and install](#build-and-install).

| Default (J3R200) | |
|---|---|
| Blocks | 248 × 256 B = 63,488 B |
| Chain table | 496 B |
| Directory | 64 × 55 B = 3,520 B (M = 16) |
| **Store total** | **about 67.5 KB**, leaving about 17 KB of NVM free |
| Main tree | 217 blocks / 56 entries |
| Duress tree | 31 blocks / 8 entries |

A card with more NVM takes larger install parameters from the same CAP.

## Commands

CLA `0x80`, all sent inside Secure Channel V2 to the Keycard applet. P1 is the entry index where a
command takes one.

| INS | Name | P1 | P2 | Data in | Data out |
|---|---|---|---|---|---|
| `B0` | STATUS | — | — | — | `version:1 blocksTotal:2 blocksFree:2 entriesTotal:1 entriesFree:1 metaLen:1`, for the active tree's share |
| `B1` | LIST | first entry | — | — | as many `[entry:1 status:1 len:2 tag:32 meta:M]` rows as fit in 239 B (4 with M = 16), then one byte: the next entry index to list, or `FF` |
| `B2` | BEGIN | — | — | `len:2 tag:32 meta:M` | `entry:1` |
| `B3` | WRITE | entry | — | `offset:2 data:≤240` | — |
| `B4` | COMMIT | entry | — | `sha256:32` | — |
| `B5` | READ | entry | — | `offset:2 length:1` (≤ 240) | data (shorter at the end of the note) |
| `B6` | DELETE | entry | `00` = delete `HELD`, `01` = abort `WRITING` | `tag:32` (must match) | — |
| `B7` | SET META | entry | — | `tag:32 meta:M` | — |
| `B8` | NEXT INDEX | — | — | — | `index:4`, big-endian: the index handed out |
| `B9` | ADVANCE INDEX | — | — | `value:4`, big-endian, at most `0x80000000` | `next:4`: the counter after raising it to at least `value`; `0` reads it |

LIST rows only include the active tree's live entries. The status byte in a row has the tree bit
cleared.

Status words:

| SW | Meaning |
|---|---|
| `6A84` | No free blocks or entries in the tree's share, or the owner-index counter is exhausted |
| `6A89` | Tag already held by this tree |
| `6A88` | Entry not found: free, dead (wiped epoch), out of range, or owned by the other tree |
| `6A80` | Hash mismatch at COMMIT, or a bad length or offset |
| `6985` | Wrong entry state, tag mismatch, or PIN not verified |
| `6A82` | Store package not installed |

The tag check on DELETE and SET META makes a stale entry index fail safely instead of acting on
another note.

## RAM and atomicity

No command allocates anything. Data moves between the APDU buffer and the blocks with
`arrayCopyNonAtomic`. The state of an open write lives in the persistent directory entry, not in RAM.
COMMIT's hash uses the Keycard applet's existing `MessageDigest` and reads the note in 240-byte
pieces through the APDU buffer. **The store adds no transient memory.**

Atomic writes are single-entry only: one entry at BEGIN (39 + M bytes), one status byte at COMMIT or
DELETE, 4 counter bytes at NEXT INDEX or ADVANCE INDEX, and the 2-byte epoch at a wipe, so the
commit buffer is never a constraint. BEGIN's free-block bitmap and entry assembly use scratch space
in the APDU buffer past the command data (from offset 128).

## A receive and a spend

```
RECEIVE  verify the note on the host → BEGIN(len, tag, meta) → WRITE × ceil(len/240)
         → COMMIT(sha256) → back up on the host → SET META (mark as backed up)
         (a tear at any point → LIST → resend the missing chunks → COMMIT)

SPEND    LIST / meta → READ × n → verify on the host → deliver the bytes to the payee
         → SIGN the spend authorization (existing command) → submit → SET META (mark as spent)
```

The card does not link SIGN to a note and never deletes anything on its own. The host decides when
a note is deleted; deleting a spent note is a separate step the holder chooses to free space, not
the last step of a spend.

## Use in Kenter

[Kenter](https://github.com/kenter-bank/kenter) uses the store for its bearer notes. Its host
conventions, which the card does not interpret:

- **tag:** the note's ephemeral public key, which is also the note's key on chain, so a LIST row
  maps straight to a ledger lookup.
- **meta (16 bytes):**

  | Field | Bytes | Meaning |
  |---|---|---|
  | amount | 8 | u64 face value |
  | owner_index | 4 | u32, the owner-key index in spend mode |
  | pairs | 1 | Number of node pairs, from which the host computes the redeemable amount |
  | exposure | 1 | Parties that may hold a redeemable copy (saturating) |
  | flags | 1 | spend mode, spent away, marked spent, backed up |
  | spare | 1 | — |

  The backed-up flag lets the wallet warn that a note exists only on the card: a lost or broken card,
  or a forced wipe, loses every note not yet backed up.

A Kenter note is about 692 B + 290 B per node pair. At the defaults the main tree holds:

| Pairs | Note size | Main-tree notes | Without the duress share |
|---|---|---|---|
| 2 | 1,272 B | 43 | 49 |
| 3 | 1,562 B | 31 | 35 |
| 4 | 1,852 B | 27 | 31 |
| 10 | 3,592 B | 14 | 16 |
| 24 | 7,652 B | 7 | 8 |

Entries run out only for notes under about 1 KB, so blocks, not entries, are the limit. See Kenter's
[custody](https://github.com/kenter-bank/kenter/blob/main/docs/architecture/custody.md) and
[spend mode](https://github.com/kenter-bank/kenter/blob/main/docs/architecture/spend-mode.md) docs
for how the wallet uses notes and owner indexes.

## Build and install

- `./gradlew notesCap` builds `build/notes/cap/im/status/keycard/notes/javacard/notes.cap` and the
  export file the Keycard CAP imports. The converter needs JDK 8 for the store's `Shareable`
  proxies; set `-Pjdk8.home=<path>`.
- `./gradlew install` loads the store package if it is not there, installs the store instance
  (package AID `A0000008040003`, applet AID `A000000804000301`, instance AID `A00000080400030101`),
  then the Keycard package. An existing store and its notes are kept. Passing
  `-Pim.status.keycard.notes.reinstall=true` deletes the store and every note first.

## Tests

`NoteStoreTest` runs on the simulator and on a card. It covers every command and status word, tear
recovery (chunks in any order, COMMIT refusing until every byte matches), duress hiding (each PIN
sees only its own notes, the duress view is fixed whatever the main tree holds, the duress share
fills on its own), the owner-index counter, filling to capacity, stale-index DELETE, the PIN gate,
FACTORY RESET, and a Keycard applet reinstall that leaves the notes intact. The tests that need a
fresh card (install parameters, INIT and CHANGE PIN checks, the reinstall) run in the simulator only.
