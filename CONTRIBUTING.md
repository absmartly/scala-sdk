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

## Code Style

- Follow standard Scala style guidelines
- Use 2-space indentation
- Maximum line length: 120 characters
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
