# kenter-keycard

kenter-keycard is a fork of [Status's Keycard applet](https://github.com/keycard-tech/status-keycard)
(status-keycard), originally developed by Bitgamma and Status. It adds what the
[Kenter](https://github.com/kenter-bank/kenter) wallet needs from a card, and keeps everything the
upstream applet does. Repository: https://github.com/kenter-bank/kenter-keycard

The Java package names (`im.status.keycard`) and the upstream AIDs are kept, so upstream tooling and
SDKs still talk to the Keycard applet.

## What this fork adds

- **Ed25519 signing for Stellar.** Pure Ed25519 (RFC 8032) with on-card SLIP-0010 derivation, through
  SIGN (P2 `0x01`) and EXPORT KEY (P2 `0x03`, public key only). It runs on cards without native EdDSA by
  using the card's EC engine on Wei25519 and a raw-RSA modular inversion. The duress PIN derives decoy
  Ed25519 keys. See [docs/ED25519.md](docs/ED25519.md).
- **On-card note store.** A separate package (`im.status.keycard.notes`, `notes/src`) that keeps opaque
  records of up to 8 KB on the card, reached through new Keycard commands (INS `B0`–`B9`) behind the
  secure channel and the PIN. It survives Keycard applet reinstalls, is tear-safe over NFC, and hides
  its notes from the duress PIN behind a fixed share. See [docs/NOTE_STORE.md](docs/NOTE_STORE.md).
- **On-card identity key.** The Ident applet generates the identity key on the card (GENERATE IDENTITY,
  INS `16`) and accepts only the 98-byte CA certificate for it, so the identity private key never leaves
  the card. See [docs/ON_CARD_IDENTITY.md](docs/ON_CARD_IDENTITY.md).
- **Session and PIN hardening.**
  - Opening a secure channel starts a new, unauthenticated session, so a PIN verified in one channel
    does not carry over to the next.
  - FACTORY RESET needs a verified PIN, unless the card is uninitialized or both PIN and PUK are blocked,
    and its status word is always sent in plain. It also wipes the note store.
  - INIT refuses a duress PIN equal to the main PIN, and CHANGE PIN refuses to set the main PIN to the
    duress PIN.
- **Fixes to upstream.** LOAD KEY with a plain key pair never reads past the command data, and the SELECT
  Application Info Template length matches its content.
- **Hardware probe.** A standalone applet that checks whether a card supports the primitives the
  Ed25519 code needs. See [probe/README.md](probe/README.md).
- **Simulator server.** `./gradlew simServer` runs the applets in jcardsim and serves raw APDUs over
  TCP, so a client can be tested without a card.

## Building the fork

You need OpenJDK 11 for Gradle and a JDK 8 for the Java Card converter (it compiles the note store's
`Shareable` proxies with javac and rejects JDK 11). Pass the JDK 8 location with
`-Pjdk8.home=<path>`.

    git clone --recurse-submodules https://github.com/kenter-bank/kenter-keycard
    ./gradlew convertJavacard -Pjdk8.home=<path>   # notes.cap, then keycard.cap
    ./gradlew install -Pjdk8.home=<path>           # note store first (kept if present), then Keycard
    ./gradlew test -Pim.status.keycard.test.target=simulator
    ./gradlew simServer                            # port 21345, or -Psim.port=NNN / SIM_PORT
    ./gradlew probeCap                             # build/probe/cap/.../probe.cap

`./gradlew install` keeps an existing note store and its notes. Add
`-Pim.status.keycard.notes.reinstall=true` to delete the store and every note first.

The jcardsim submodule points at a fork with the simulator fixes these tests need.

Beyond the upstream requirements below, Ed25519 needs `ALG_EC_SVDP_DH_PLAIN_XY` with custom curve
parameters and raw RSA with a 512-bit private key (see [docs/ED25519.md](docs/ED25519.md)). The fork is
developed and tested on NXP J3R200 (JCOP4 P71) cards.

## Licence

Apache License 2.0, as upstream. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

---

The rest of this README is the upstream Keycard README.

# What is Keycard?

Keycard is an implementation of a BIP-32 HD wallet running on Javacard 3.0.5 (see implementation notes)

It supports among others
- key generation, derivation and signing
- exporting keys defined in the context of EIP-1581 https://eips.ethereum.org/EIPS/eip-1581
- setting up a NFC NDEF tag

Communication with the Keycard happens through a simple APDU interface, together with a Secure Channel guaranteeing confidentiality, authentication and integrity of all commands. It supports both NFC and ISO7816 physical interfaces, meaning that it is compatible with any Android phone equipped with NFC, and all USB Smartcard readers.

The most obvious case for integration of Keycard is crypto wallets (ETH, BTC, etc), however it can be used in other systems where a BIP-32 key tree is used and/or you perform authentication/identification.

# Where to start?

A good place to start is our [documentation site](https://keycard.tech/docs/)

Keycard is a public good — contributions are welcome and highly encouraged!

You can also join the discussion about this project on our [discord channel](https://discord.gg/uJAXk7jFhZ)

Keycard is at the center of several projects, check the [ecosystem of projects](https://github.com/keycard-tech/keycard-ecosystem-projects/) or [good first issues](https://github.com/orgs/keycard-tech/projects/1/views/2?filterQuery=good+first+issue)

Should you wish to work on an issue, please claim it first by commenting on the GitHub issue that you want to work on it. This is to prevent duplicated efforts from contributors on the same issue. 

If you just want to use Keycard as your hardware wallet, you can choose from any compatible wallet in this list (apply the 'Keycard' filter): https://keycard.tech/wallets/

# How to build the project?

**YOU NEED OPENJDK11 TO BUILD THIS PROJECT**

make sure your `JAVA_HOME` environment variable points to a working OpenJDK 11 installation. This is a prerequisite for all of the following steps.

## Get the source

Clone this repository with `git clone --recurse-submodules https://github.com/keycard-tech/status-keycard`

## Compilation

Run `./gradlew convertJavacard`

## Installation

Make sure the card you are trying to use is using the T=1 protocol and SCP02 with either the GlobalPlatform default keys (404142434445464748494a4b4c4d4e4f) or the Keycard development cards key (c212e073ff8b4bbfaff4de8ab655221f), otherwise this installation method will not work and you will need to use an external tool like [GlobalPlatformPro](https://github.com/martinpaljak/GlobalPlatformPro).

1. Disconnect all card reader terminals from the system, except the one with the card where you want to install the applet
2. Run `./gradlew install`

## Testing

Run `./gradlew test`

if you want to test using the simulator instead of a real card, create a file named `gradle.properties` with the content:

```im.status.keycard.test.target=simulator```

before running the tests.

# What kind of smartcards can I use? 

* The applet requires JavaCard 3.0.5 or later.
* The class byte of the APDU is not checked since there are no conflicting INS code.
* The GlobalPlatform ISD keys are set to 404142434445464748494a4b4c4d4e4f (GlobalPlatform default keys) or c212e073ff8b4bbfaff4de8ab655221f (Keycard development cards default keys).

Following algorithms are part of JavaCard 3.0.4 and must be supported by the card:
* Cipher.ALG_AES_BLOCK_128_CBC_NOPAD
* Cipher.ALG_AES_CBC_ISO9797_M2
* KeyAgreement.ALG_EC_SVDP_DH_PLAIN
* KeyAgreement.ALG_EC_SVDP_DH_PLAIN_XY
* KeyPair.ALG_EC_FP (generation of 256-bit keys)
* MessageDigest.ALG_SHA_256
* MessageDigest.ALG_SHA_512
* RandomData.ALG_SECURE_RANDOM
* Signature.ALG_AES_MAC_128_NOPAD
* Signature.ALG_ECDSA_SHA_256

Following algorithm is part of JavaCard 3.0.5 and best performance is achieved if the card supports it:
* Signature.ALG_HMAC_SHA_512

# Other related repositories

Java SDK for Android and Desktop https://github.com/keycard-tech/status-keycard-java

Swift SDK for iOS13 and above https://github.com/keycard-tech/Keycard.swift

Keycard CLI for Desktop https://github.com/keycard-tech/keycard-cli
