# Arachne for ATAK

**No TAK Server. A secure, decentralized mesh for ATAK.**

[![Release](https://img.shields.io/badge/release-0.0.2--alpha-f59e0b?style=for-the-badge&logo=android&logoColor=white)](https://github.com/arachne-systems/arachne-atak/releases)
[![Downloads](https://img.shields.io/github/downloads/arachne-systems/arachne-atak/total?style=for-the-badge&label=APK%20downloads)](https://github.com/arachne-systems/arachne-atak/releases)
[![Feedback](https://img.shields.io/badge/feedback-GitHub%20Issues-181717?style=for-the-badge&logo=github&logoColor=white)](https://github.com/arachne-systems/arachne-atak/issues)

Arachne connects ATAK devices through a decentralized mesh. Each phone runs an
Arachne endpoint. Every connection is encrypted and authenticated; devices
connect directly when possible, and a relay forwards ciphertext only when a
direct path is unavailable. Only group members can read shared traffic.

[**Download Arachne APK**](https://github.com/arachne-systems/arachne-atak/releases)

> Install Arachne alongside ATAK-CIV on each participating phone.

## The mesh

Each member's phone is a node in the mesh. Arachne protects group membership
and routes shared ATAK data among those nodes.

## Use ATAK normally

Your team continues to use ATAK's native workflows:

- Contacts and team membership
- Position sharing (PLI)
- Chat
- Map points and drawings
- Feeds and ATAK Data Packages

Arachne supplies the secure peer membership and transport underneath.

![Arachne workspaces in ATAK](docs/promo/assets/atak-workspaces-promo-20260920.png)

## Start a mesh

1. Install a compatible ATAK-CIV host and Arachne on each device.
2. In Arachne, create a group (called a **workspace** in the app).
3. Share the invitation link or QR code.
4. Join from the other devices and use ATAK normally.

> Devices exchange data over a local network, internet path, or reachable
> relay.

## When Arachne fits

Arachne suits temporary operations, volunteer teams, search and rescue,
exercises, and small deployments that need a quickly formed ATAK mesh.

Use TAK Server when you need centralized administration, established TAK
integration, or infrastructure your organization already operates.

## Current status

> [!WARNING]
> This is an alpha evaluation build. Current validation covers ATAK-CIV 5.8.0,
> selected Android environments, and development-scale membership tests.
> Evaluate compatibility, network behavior, and operational requirements for
> your deployment before relying on it in the field. Follow your organization's
> approved deployment process for operational systems.

<details>
<summary>Current build details</summary>

- Arachne: `0.0.2-alpha` (version code `5`)
- Host: ATAK-CIV `5.8.0` / SDK mapping `5.8.0.4`
- Android: API `26` or newer
- Architectures: `arm64-v8a` and `x86_64`
- A development test admitted 500 simulated members across three Android
  devices. This measures membership admission at that size; feature coverage
  for 500 active users and difficult-network testing continue.

</details>

## Technical details

For protocol, security, delivery, persistence, and qualification details, read
the [technical architecture](docs/technical-architecture.md).

The ATAK plugin is the first application on the Arachne fabric. External apps,
feeds, and services can use the same decentralized communication model through
the portable Rust interface.

## Feedback

Use [GitHub Issues](https://github.com/arachne-systems/arachne-atak/issues) for
bug reports and product feedback. Include Arachne, ATAK, Android, and device
versions, along with the exact steps and result. Redact invitations, keys,
locations, personal information, and other sensitive data from diagnostics.

## Licensing and external inputs

See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for the current release
terms.

Builders obtain the ATAK host, ATAK SDK, SDK JARs or AARs, TAK developer
tooling, signing keys, and restricted TAK material separately under their
applicable TAK terms. Third-party components retain their own licenses and
notices.

Arachne is an independent project unaffiliated with the TAK Product Center,
TAK.gov, and U.S. Government product lines.
