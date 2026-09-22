# Arachne for ATAK

**Connect your ATAK team. No TAK Server required.**

Arachne is an ATAK plugin that connects your team's devices in a private
**workspace**. Share locations, messages, map points, drawings, feeds and
Data Packages through ATAK's existing tools. Every connection is encrypted
and authenticated over a secure mesh.

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/promo/assets/arachne-overview-dark.svg">
  <img src="docs/promo/assets/arachne-overview-light.svg" alt="ATAK runs on each phone. Arachne connects the phones in a private workspace for sharing locations, messages, maps and files. Every network path is encrypted.">
</picture>

## Get started

For this alpha, use **ATAK-CIV 5.8.0** on **Android 8.0 or newer**.
Devices need a usable connection over local Wi-Fi, a LAN or the internet.

> **Alpha evaluation build.** Current validation covers selected Android
> environments and development-scale membership tests. Evaluate on your devices
> and networks before operational use, following your organization's approved
> deployment process.

1. **Install.** [Download Arachne 0.0.2-alpha](https://github.com/arachne-systems/arachne-atak/releases)
   and install it alongside ATAK-CIV on each device.
2. **Create.** Open Arachne in ATAK and create a workspace for your team.
3. **Invite.** Share the invitation link or QR code with your teammates.
4. **Share.** Teammates join the workspace and use ATAK as usual.

![Arachne Workspaces panel inside ATAK](docs/promo/assets/atak-workspaces-promo-20260920.png)

## How it works

Arachne runs on each team member's device, handling workspace membership and
encrypted sharing. Devices connect directly when possible. When a direct path
is unavailable, a reachable relay forwards encrypted traffic without reading
the shared content.

<details>
<summary>See the connection paths</summary>

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/promo/assets/arachne-connections-dark.svg">
  <img src="docs/promo/assets/arachne-connections-light.svg" alt="Every connection is encrypted and authenticated. Devices connect directly over a local network or the internet when possible. When a direct path is unavailable, a reachable relay forwards encrypted traffic without reading shared content. A usable network path is required; internet and relay validation continues in the alpha.">
</picture>

</details>

The ATAK plugin is the first application on the Arachne fabric. External apps,
feeds and services can use the same communication model through its portable
Rust interface. Read the [technical architecture](docs/technical-architecture.md)
for protocols, security, delivery and qualification details.

<details>
<summary>Build and validation details</summary>

- Arachne: `0.0.2-alpha` (version code `5`)
- Host: ATAK-CIV `5.8.0` / SDK mapping `5.8.0.4`
- Android: API `26` or newer
- Architectures: `arm64-v8a` and `x86_64`
- A development test admitted 500 simulated members across three Android
  devices. This measures membership admission at that size; feature coverage
  for 500 active users and difficult-network testing continue.
- Public-internet and relay behavior, mixed ATAK versions, and large active
  deployments remain under validation.

</details>

## Feedback

Report bugs and product feedback in
[GitHub Issues](https://github.com/arachne-systems/arachne-atak/issues).
Include Arachne and ATAK versions, Android and device details, and steps to
reproduce the issue. Remove invitations, keys, locations, personal information
and other sensitive data from diagnostics.

## License and notices

See [LICENSE](LICENSE) and [NOTICE.md](NOTICE.md) for release terms and external
software requirements. ATAK and its SDK are obtained separately under their
own terms. Third-party components retain their own licenses and notices.

Arachne is an independent project unaffiliated with the TAK Product Center,
TAK.gov, and U.S. Government product lines.
