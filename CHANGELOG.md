# Changelog

All notable changes to the ABsmartly Scala SDK are documented in this file.

## [Unreleased]

### Added

- **Assignment rules** (`assignmentRules` on an experiment), matching the JS
  and Java SDKs. `assign` rules give a fixed variant; `split` rules divide
  matching units across variants by percentages, hashed with the experiment's
  seed. Rules can be scoped to environments, and their conditions are JSON
  expressions evaluated against the same attributes as audiences. The first
  matching rule wins over audiences, traffic, full-on and custom assignments;
  only `setOverride()` beats it. Rule assignments are published with the new
  exposure field `ruleOverride: true`.
- **Semver operators** for audiences and rule conditions: `semver_eq`,
  `semver_gt`, `semver_gte`, `semver_lt` and `semver_lte`.
- **Maven Central publishing** from CI with sbt-ci-release: a `v*` tag publishes
  that version, pushes to `main` publish snapshots. CI now also checks
  formatting (scalafmt) and packaging.

### Changed

- `X-Agent` is now `absmartly-scala-sdk/<version>` (was `absmartly-scala-sdk`),
  with the version taken from the build.
- The version is derived from git tags (sbt-dynver) instead of `build.sbt`.
- An audience filter that throws during evaluation now counts as a mismatch, so
  a strict audience gives variant 0 (it previously counted as no decision).

### Changed

- **Audiences are now always evaluated with `application` and `environment`.**
  Every context now evaluates audience filters against the `application` (the
  application name) and `environment` (the environment name, exactly as
  configured on the SDK client) in addition to any user-set attributes. User-set
  attributes always win: if you call `setAttribute("environment", ...)` — even
  after the context is created — your value overrides the system one and any
  assignment cached beforehand is re-evaluated. System values with an empty name
  are skipped (the key is left out rather than set to `""`). These system values
  are used for evaluation only; they are never added to the publish payload (the
  collector already attaches them server-side), so no new attribute entries are
  published.

  **This changes assignment for running experiments.** A unit whose experiment
  has Audience enforced (`audienceStrict: true`) and an `environment` or
  `application` condition currently gets variant 0, because the SDK never
  supplied those attributes and the strict mismatch beat the full-on variant.
  After upgrading, such a unit is randomised normally. Those experiments are
  broken today; this release fixes them, but the assignment they produce changes.

- `app_version` / `application_version` is never injected into the audience
  evaluation map. Unlike `application`/`environment`, it is not added
  automatically, so a filter on `app_version` does not match unless a caller
  sets it explicitly with `setAttribute("app_version", ...)`. The SDK still
  sends the `X-Application-Version` request header for the collector; that
  header is unrelated to the evaluation map.
