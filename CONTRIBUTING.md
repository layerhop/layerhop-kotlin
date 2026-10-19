# Contributing

Thanks for your interest in LayerHop!

## Where the code lives

This repository is **exported automatically** from the LayerHop monorepo
(`sdks/kotlin`), which is the single source of truth. Every sync commit is titled
`Sync from layerhop monorepo <sha>` and references the monorepo commit it was built from.

**Pull requests are welcome here.** A maintainer reviews your PR, applies the change upstream in the
monorepo, and the next sync brings it back into this repository with you credited in the PR. Because
of that, your PR may be closed rather than merged directly; that is expected and does not mean it was
rejected.

Issues are welcome too. For security problems, follow [SECURITY.md](SECURITY.md) instead.

## Development

See [README.md](README.md) for setup. CI (`.github/workflows/ci.yml`) runs the same checks you
should run locally before opening a PR.

## Releases

Bump `version` in `build.gradle.kts`, then push a `vX.Y.Z` tag. `.github/workflows/release.yml` runs the tests and publishes to Maven Central through the Central Portal (requires the `MAVEN_CENTRAL_USERNAME`, `MAVEN_CENTRAL_PASSWORD`, `SIGNING_KEY` and `SIGNING_KEY_PASSWORD` repository secrets); versions already on Maven Central are skipped. Local dev: `./gradlew test` (JDK 17+).
