# On-card identity

The identity key authenticates the card. Secure Channel V2 signs every channel-open transcript with
it (`SecureChannelV2.openSecureChannel`), and a client trusts the key only if the certificate the
Ident applet returns on SELECT (tag `8A` in the Keycard applet's SELECT response) recovers to a CA
public key the client has pinned.

In kenter-keycard the card **generates its identity key itself** and exports only the public key.
The CA signs the public key off card, and the host writes back only the 98-byte certificate. The
identity private key never leaves the card. The code is `IdentApplet` and `SharedMemory`; the tests
are `IdentityTest`, and `TestIdentity.provision` is the reference host flow.

## Why the card generates the key

If the personalization host creates the identity key and writes it to the card, every card's
private key exists, at least briefly, on that host. Anyone who compromises the host can clone a
certified identity, and so can anyone holding a copy of its output: logs, backups, a script's temp
file. A clone passes exactly the counterfeit check that CA pinning exists to make. With on-card
generation, the host only ever sees public keys and certificates, and nothing on it can clone a
card, even after the fact.

Properties kept from upstream Keycard:

- The identity survives FACTORY RESET and changes only when the applet package is reinstalled.
- The 98-byte certificate format and the SELECT response are unchanged.
- Personalization works while the card's GlobalPlatform (ISD) keys are still the defaults.

## States

Everything runs in the Ident applet, in plain APDUs. Secure Channel V2 cannot carry them, because
the channel needs the identity that is being created.

The state is the first byte of `SharedMemory.idCert`:

| Value | State | `idCert[1..33]` | `idCert[34..98]` | Identity key |
|---|---|---|---|---|
| `0x00` | EMPTY | — | — | none |
| `0x55` | GENERATED | compressed public key | — | on card, uncertified |
| `0xAA` | CERTIFIED (frozen) | compressed public key | CA signature `r ‖ s ‖ v` | on card, certified |

`KeycardApplet.process` refuses every command, SELECT included, with `6985` unless the state is
CERTIFIED. An uncertified card does nothing, so it fails closed.

## Commands

### GENERATE IDENTITY (`80 16 00 00`, no data)

- **CERTIFIED:** `6985`.
- **EMPTY or GENERATED:**
  1. Set the state to EMPTY. This is a single-byte write, so it is atomic.
  2. Generate a new secp256k1 key pair into the persistent identity key objects.
  3. Compress the public key in the APDU buffer: `02` or `03` by the parity of Y, then X.
  4. In one transaction, copy the 33 bytes to `idCert[1..33]` and set GENERATED.

  Clearing the state first means a tear during key generation cannot leave GENERATED pointing at a
  public key that no longer matches the private key. A torn GENERATE leaves EMPTY, and the host
  sends GENERATE again.
- **Response:** the 33-byte compressed public key, `9000`.

Generating again while GENERATED replaces the key, so a certificate made for the old key no longer
matches.

### STORE DATA (`80 E2 00 00`)

- **Not GENERATED** (EMPTY or CERTIFIED): `6985`. There is nothing to certify, or the certificate is
  already written; it is write-once.
- **Lc ≠ 98:** `6A80`. The upstream 130-byte form, certificate plus private key, is refused, so a
  host that tries to inject a key fails instead.
- **`cert[0..33]` ≠ `idCert[1..33]`:** `6A80`. The certificate must name the key this card holds.
- **Otherwise:** in one transaction, copy `cert[33..98]` to `idCert[34..98]` and set CERTIFIED.

The applet does not verify the CA signature. It has no CA key to verify against, and public-key
recovery always succeeds for some key. The client is the verifier, and the host runs the same check
before it finishes.

### SELECT on the Ident applet

- **CERTIFIED:** the 98-byte certificate.
- **GENERATED:** the 33-byte public key. The lengths tell the states apart, so a host that lost the
  link after GENERATE can resume without generating again, which would strand a signature the CA has
  already made.
- **EMPTY:** no data.

If certification never arrives, the card stays GENERATED (or EMPTY). The Keycard applet refuses all
commands, and the host can send GENERATE again at any time. No state is worse than "an uncertified
card that does nothing".

## Certificate

The certificate is 98 bytes: `pub (33) ‖ r (32) ‖ s (32) ‖ v (1)`, an ECDSA secp256k1 signature by
the CA over `SHA-256(pub)`, with `v` the recovery id. This is the upstream Keycard format.

The host must normalise the signature to low S (`s = n − s` if `s > n/2`) and compute `v` for the
normalised pair. A CA or HSM that returns DER `(r, s)` may produce a high `s`. Clients that recover
the CA key from `v`, as the upstream SDK does, or that enforce low S need the normalised form;
normalising costs nothing. Kenter's client normalises `s` itself and checks against each of its
pinned CA keys without using `v`, so it accepts either form. A client should accept a set of pinned
CA keys, so that a spare CA key can replace the active one.

The certificate binds only the public key: no serial number or batch id. A serial would only be a
label, since a client cannot check card data it reads over NFC against anything trustworthy; the
personalization record maps public keys to cards, so a batch can be revoked by denylisting its keys.
The CA key must sign nothing but identity public keys, which gives the same protection as a
domain-separated digest without changing the format.

## Who can generate or replace the identity

| Attacker holds | Before certification | After certification |
|---|---|---|
| The card only | Can send GENERATE again. The result is an uncertified card that does nothing, and it cannot be certified without the CA. Doing it mid-personalization makes the host's STORE DATA fail the public-key match (`6A80`), so it fails closed. | GENERATE and STORE DATA are refused (`6985`). No command leaves CERTIFIED. |
| Card + ISD keys | Same as above. | Can delete and reinstall the package. That destroys the identity, which lives in the package's static fields, and the Keycard state. The result is a new EMPTY identity that no pinned CA has signed, so clients reject it. |
| The personalization host | Sees only public keys and certificates. Nothing on it can clone a card. | Same. |
| The CA signing key, or a host driving it | Can sign a public key the attacker generated, which makes a counterfeit identity. | Same. This is the residual risk, below. |

So **identity integrity does not depend on locking the ISD.** The ISD keys give the power to wipe a
card or replace its code, and replaced code comes with a new, uncertified identity that the
certificate check catches. Locking the ISD still matters for other reasons: denial of service, and
code substitution on cards that are never checked.

GENERATE is not restricted to the ISD secure channel. Doing so would need the GlobalPlatform API
export files and weaken simulator coverage, and it would protect nothing the certificate check does
not, because the CA is the real gate.

**Erasing a wallet keeps the identity.** A holder erases a wallet with VERIFY PIN then FACTORY RESET
(no PIN is needed if PIN and PUK are both blocked), and re-provisions with INIT and then GENERATE
KEY, or LOAD KEY with a restored seed. None of these touches the identity, so the erased card keeps
its certified identity and clients accept it with no CA involvement. A card keeps one identity
across owners. Only a GlobalPlatform reinstall loses the identity; the card comes back EMPTY and
needs a new certificate from the CA to be accepted again.

**Residual risk: the CA signs whatever it is shown.** On-card generation removes the passive,
after-the-fact cloning path. It does not stop an active attacker who can put a public key in front
of the CA during personalization. Those controls belong to the personalization process, not the
applet:

- an offline personalization host;
- the CA key (HSM) and its PIN never stored with the personalization host;
- a personalization record per card: identity public key, card identifier, batch, time, kept off
  the public repository;
- signatures reconciled against cards per batch.

Revocation is a client-side denylist of identity public keys, or moving to a spare CA key.

## Key generation on JCOP4

- `KeyPair(ALG_EC_FP, 256)` with the secp256k1 parameters set on both halves
  (`SECP256k1.setCurveParameters`), then `genKeyPair()`. The Cash applet's constructor runs the same
  sequence. The Ident applet builds a persistent `ECPublicKey` next to `SharedMemory.idPrivate` so
  that `idPrivate` remains the key Secure Channel V2 signs with.
- The cost is one persistent public-key object, roughly 100–200 bytes of NVM, and no
  `CLEAR_ON_RESET` RAM: compression works in the APDU buffer.
- The platform draws the scalar from the chip's RNG and keeps it in `[1, n−1]`, so the applet needs
  no range check.
- The identity is plain secp256k1, which the chip supports natively. The workarounds used for
  Ed25519 (512-bit RSA inversion, Wei25519 cofactor 1) are not involved.

## Personalization flow

`TestIdentity.provision(channel, ca)` implements this for the tests and the simulator:

1. SELECT the Ident applet. If it returns 98 bytes, stop: the card is already certified.
2. If it returns 33 bytes, use that key. Otherwise send GENERATE IDENTITY.
3. Sign `SHA-256(pub)` with the CA, normalise to low S and compute `v`.
4. Send the 98-byte STORE DATA.
5. Verify the result the way a client does: SELECT the Keycard applet and open a secure channel
   pinned to the CA.

A production host adds the personalization record and the batch reconciliation above, and rotates
the ISD keys only after the identity is certified.

This differs from upstream Keycard's personalization, where the factory host generates identity
keys. Upstream tooling cannot personalise kenter-keycard cards, and the other way round.

## Tests

`IdentityTest` checks, in the simulator:

1. A new card has no identity: SELECT returns no data, STORE DATA is refused (`6985`), and the
   Keycard applet refuses everything.
2. GENERATE IDENTITY returns a compressed secp256k1 point, and SELECT then returns the same key.
3. Generating again replaces the key, and a certificate for the old key is refused (`6A80`).
4. STORE DATA with a matching certificate certifies the card; SELECT returns it, and it recovers to
   the CA.
5. STORE DATA refuses wrong lengths, the 130-byte certificate-plus-private-key form, and a
   certificate for another key.
6. A certified identity is frozen: GENERATE and STORE DATA are refused and the certificate stays.
   This one also runs on a card.
7. The secure channel opens under the certified key, and only for a client pinning the issuing CA.
8. FACTORY RESET leaves the identity: the same certificate, and the channel still opens under it.

The other suites provision the identity through `TestIdentity.provision`, so they run unchanged on
the simulator and on a card.
