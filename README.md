# clawfare

Flight price investigation tracker.

## Setup

```bash
# Use the project's git hooks
git config core.hooksPath .githooks

# Build
./gradlew build
```

## Development

```bash
# Run lint
./gradlew ktlintCheck

# Auto-fix lint issues
./gradlew ktlintFormat

# Run tests with coverage
./gradlew test koverVerify

# View coverage report
./gradlew koverHtmlReport
open build/reports/kover/html/index.html

# All checks (what CI runs)
./gradlew codeCheck
```

## Requirements

- JDK 21+
- 80% test coverage minimum
- ktlint must pass
