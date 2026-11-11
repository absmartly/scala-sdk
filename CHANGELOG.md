# Changelog

All notable changes to the ABsmartly Scala SDK are documented in this file.

## [Unreleased]

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
