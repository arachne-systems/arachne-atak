# Arachne release boundary

This public repository is currently an Arachne ATAK release and feedback
channel. It contains release documentation, promotional material, and signed
APK releases; it does not currently publish Arachne source code.

The source tree is being rebuilt and separated from the portable core, SDK,
relay, and feed projects. Future source files will identify their applicable
license and copyright scope when they are published.

The Mozilla Public License 2.0 in [LICENSE](LICENSE) is retained for the
planned source boundary. It does not relicense material that is not identified
as Arachne Covered Software in a source release.

This repository does not include, and this license does not relicense:

- the ATAK host application;
- the ATAK/TAK SDK, SDK JARs or AARs;
- TAK developer tooling, private build services, signing keys, or restricted
  TAK material;
- ATAK source code or SDK sample code copied into Arachne;
- third-party libraries, fonts, maps, images, or other assets; or
- the Arachne name, logos, marks, official signing identity, or certification
  status.

## Building the plugin

A source build requires an authorized ATAK-CIV/TAK SDK obtained separately by
the builder. The SDK is an external build input and remains governed by the
terms that accompany that SDK:

- [TAK developer and distribution policy](https://tak.gov/pages/our-process)
- [ATAK-CIV license](https://github.com/TAK-Product-Center/atak-civ/blob/main/LICENSE.md)
- [ATAK-CIV third-party notices](https://raw.githubusercontent.com/TAK-Product-Center/atak-civ/main/THIRDPARTY.md)

Those links describe external software and permissions; they do not grant
Arachne permission to redistribute the SDK or ATAK host.

## Third-party materials

Third-party components retain their original licenses and notices. Before a
public source or binary release, the release package must identify the exact
dependency graph, include the applicable notices, and provide any required
covered-source access.

Source publication and the final contribution process are intentionally
deferred until the architecture, provenance, dependency, security, and
licensing audit is complete.
