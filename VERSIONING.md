# Versioning

How turborel-java's releases are numbered, what protocol each speaks, and
how a release is published.

[Release numbers](#release-numbers) · [Alpha](#alpha) ·
[The protocol](#the-protocol) · [Where the version is](#where-the-version-is) ·
[Publishing](#publishing)

---

## Release numbers

The driver has no version scheme of its own. Its version is the turborel
release it is made for, the same number as the server's: a turborel
release and each driver's release share the number, and turborel-jdbc
0.0.0 is made for turborel 0.0.0. turborel numbers its releases
**MAJOR.MINOR.PATCH**, as in
[Semantic Versioning 2.0.0](https://semver.org/spec/v2.0.0.html), and says
what each kind of release may change in its own `VERSIONING.md`.

## Alpha

turborel is in alpha, version **0.0.0**, and so is the driver. While the
version is 0.y.z nothing is promised: the protocol, the driver's API and
its behaviour may change in any build. The first stable release is
**1.0.0**, with turborel's.

## The protocol

| Release | Protocol version | Defined in |
|---|---|---|
| 0.0.0 (alpha) | 1 | turborel's `doc/client/05-protocol.md` |

Version 1 grows by capabilities, never by a new version: the driver asks
for those it knows, and the server grants those it has, so a driver and a
server of different builds agree on what both have. A server refuses a
Hello of a version it does not speak (SQLSTATE 08004).

## Where the version is

- `build.gradle.kts` gives the version, `0.0.0`, and the Maven coordinates,
  `com.turborel:turborel-jdbc` (RULES.md D6).
- The jar's POM carries the same version.

## Publishing

Nothing is published until every turborel driver is done. Then the owner
publishes them together, each to its language's registry. turborel-jdbc
goes to [Maven Central](https://central.sonatype.com), through the Sonatype
Central Portal, under the namespace `com.turborel`, which a DNS record on
turborel.com verifies.

A release is published from a clean checkout of a pushed commit that has
passed `./gradlew check` on every platform in CI, by the `release`
workflow, run by hand:

1. `./gradlew check publishMavenPublicationToStagingRepository` builds the
   jar, its sources and Javadoc jars and the POM, checks them, and writes
   them to `build/staging-deploy`, as a Maven repository in a directory.
   Nothing leaves the machine.
2. The workflow keeps that directory as the run's artifact, to be looked
   over.
3. Its last step signs the files and uploads them to the Central Portal.
   It is written but disabled: the owner enables it, and gives the
   workflow the signing key and the Portal's token as secrets, when the
   drivers are published.

A version published to Maven Central cannot be changed or deleted. A fix is
a new release.
