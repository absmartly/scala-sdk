# Contributing to ABsmartly Scala SDK

Thank you for your interest in contributing to the ABsmartly Scala SDK!

## Development Setup

### Prerequisites

- **JDK 11+** (OpenJDK recommended)
- **sbt 1.9+**
- **Git**

### Clone and Build

```bash
git clone https://github.com/absmartly/scala-sdk.git
cd scala-sdk

# Compile
sbt compile

# Run tests
sbt test

# Cross-compile for Scala 2.13 and 3
sbt +test
```

## Releasing

Releases are published to Maven Central by `.github/workflows/release.yml`
using [sbt-ci-release](https://github.com/sbt/sbt-ci-release). The version comes
from the git tag (sbt-dynver), so there is no version to bump in `build.sbt`:

- pushing a tag `v1.2.3` publishes `1.2.3`;
- every push to `main` publishes a `-SNAPSHOT`.

The workflow needs the `PGP_SECRET` (base64 private key), `PGP_PASSPHRASE`,
`SONATYPE_USERNAME` and `SONATYPE_PASSWORD` (a Central Portal user token)
repository secrets. The same version is sent in the `X-Agent` header
(`absmartly-scala-sdk/<version>`).

## Code Style

- Code is formatted with scalafmt (`.scalafmt.conf`): run `sbt scalafmtAll scalafmtSbt`
  before committing; CI runs `sbt scalafmtCheckAll scalafmtSbtCheck`
- Use meaningful variable names
- Add scaladoc comments for public APIs

## Testing

All contributions must include tests:

1. **Unit Tests**: Test individual components
2. **Integration Tests**: Test end-to-end behavior
3. **Test Coverage**: Maintain 100% coverage for new code

### Running Tests

```bash
# Run all tests
sbt test

# Run specific test suite
sbt "testOnly com.absmartly.sdk.ContextTest"

# Run with coverage
sbt coverage test coverageReport
```

## Submitting Changes

1. Fork the repository
2. Create a feature branch: `git checkout -b feature/my-feature`
3. Make your changes
4. Add tests
5. Ensure all tests pass: `sbt +test`
6. Commit with clear message: `git commit -m "feat: add new feature"`
7. Push to your fork: `git push origin feature/my-feature`
8. Submit a pull request

## Commit Message Format

Follow conventional commits:

- `feat:` - New feature
- `fix:` - Bug fix
- `docs:` - Documentation changes
- `test:` - Test additions/changes
- `refactor:` - Code refactoring
- `perf:` - Performance improvements
- `chore:` - Build/tooling changes

## Pull Request Process

1. Update README.md with any new features
2. Update CHANGELOG.md
3. Ensure all tests pass
4. Ensure no compiler warnings
5. Request review from maintainers

## Questions?

- Open an issue on GitHub
- Email: sdk@absmartly.com
- Documentation: https://docs.absmartly.com

Thank you for contributing! 🎉
