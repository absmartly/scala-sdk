# Scala SDK - Quick Start Guide

## Installation & Setup

### Prerequisites
```bash
# Install sbt
brew install sbt  # macOS
# OR see https://www.scala-sbt.org/download.html for other platforms
```

### Build & Test
```bash
# Navigate to SDK directory
cd /Users/joalves/git_tree/sdks/scala-sdk

# Compile
sbt compile

# Run tests (200+ tests)
sbt test

# Cross-compile for Scala 2.13 and 3
sbt +test
```

## Basic Usage

### 1. Configure SDK
```scala
import com.absmartly.sdk._
import scala.concurrent.ExecutionContext.Implicits.global

val config = SDKConfig(
  endpoint = "https://your-endpoint.absmartly.io/v1",
  apiKey = "your-api-key",
  application = "my-app",
  environment = "production"
)

val sdk = new SDK(config)
```

### 2. Create Context (Async)
```scala
import scala.concurrent.Await
import scala.concurrent.duration._

val contextFuture = sdk.createContext(
  units = Map("session_id" -> "user-abc-123")
)

val context = Await.result(contextFuture, 5.seconds)
```

### 3. Create Context (Sync - with pre-fetched data)
```scala
val data = ContextData(experiments = List(/* ... */))

val context = sdk.createContextWith(
  units = Map("session_id" -> "user-abc-123"),
  data = data
)
// Context is ready immediately!
```

### 4. Get Treatment
```scala
// Get variant and queue exposure
val variant = context.treatment("button_color_experiment")

// Use variant
if (variant == 1) {
  // Show blue button
} else {
  // Show default button
}
```

### 5. Peek (without exposure)
```scala
// Get variant WITHOUT queuing exposure
val variant = context.peek("button_color_experiment")
```

### 6. Set Attributes (for targeting)
```scala
import io.circe.Json

context.setAttribute("age", Json.fromInt(25))
context.setAttribute("country", Json.fromString("US"))
context.setAttribute("premium", Json.True)

// Or set multiple at once
context.setAttributes(Map(
  "age" -> Json.fromInt(25),
  "country" -> Json.fromString("US")
))
```

### 7. Get Variable Values
```scala
// Get variable and queue exposure
val buttonColor = context.variableValue("button_color", "blue")

// Peek variable without exposure
val buttonText = context.peekVariableValue("button_text", "Click Me")
```

### 8. Track Goals
```scala
// Simple goal
context.track("signup")

// Goal with properties (numeric only!)
context.track("purchase", Some(Map(
  "amount" -> Json.fromDouble(99.99).get,
  "quantity" -> Json.fromInt(3)
)))
```

### 9. Publish Events
```scala
// Publish queued events
val publishFuture = context.publish()
Await.result(publishFuture, 5.seconds)
```

### 10. Finalize Context
```scala
// Publish and seal context (no more operations allowed)
val finalizeFuture = context.finalize()
Await.result(finalizeFuture, 5.seconds)
```

## Advanced Features

### Overrides (Force Variants)
```scala
// Force specific variant
context.setOverride("button_color_experiment", 1)

// Or multiple
context.setOverrides(Map(
  "experiment_a" -> 1,
  "experiment_b" -> 2
))
```

### Custom Assignments
```scala
// Custom variant assignment
context.setCustomAssignment("special_test", 1)
```

### Multiple Units
```scala
// Set multiple unit types
context.setUnits(Map(
  "session_id" -> "abc123",
  "user_id" -> "user456",
  "device_id" -> "device789"
))

// Get specific unit
val sessionId = context.getUnit("session_id") // Option[String]

// Get all units
val allUnits = context.getUnits() // Map[String, String]
```

### State Checks
```scala
context.isReady()      // Boolean
context.isFailed()     // Boolean
context.isFinalized()  // Boolean
context.pending()      // Int (queued events count)

// Get context data
val data = context.data() // ContextData

// List experiments
val experiments = context.experiments() // List[String]
```

## Cross-SDK Testing

### Build Docker Wrapper
```bash
cd /Users/joalves/git_tree/sdks/cross-sdk-tests

# Build wrapper
docker-compose build scala-sdk
```

### Run Tests
```bash
# Test Scala SDK specifically
./run-tests.sh --sdk scala-sdk

# Or test all SDKs
./run-tests.sh
```

**Expected:** 232+ tests passing ✅

## API Summary

| Category | Singular | Plural |
|----------|----------|--------|
| **Units** | `setUnit(type, uid)` | `setUnits(units)` |
| | `getUnit(type)` | `getUnits()` |
| **Attributes** | `setAttribute(name, value)` | `setAttributes(attrs)` |
| | `getAttribute(name)` | `getAttributes()` |
| **Overrides** | `setOverride(exp, variant)` | `setOverrides(map)` |
| **Custom** | `setCustomAssignment(exp, variant)` | `setCustomAssignments(map)` |
| **Treatment** | `treatment(exp)` → with exposure | - |
| | `peek(exp)` → no exposure | - |
| **Variables** | `variableValue(key, default)` | `variableKeys()` |
| | `peekVariableValue(key, default)` | - |
| **Goals** | `track(name, props?)` | - |
| **Lifecycle** | `publish()`, `finalize()`, `refresh()` | - |
| **State** | `isReady()`, `isFailed()`, `isFinalized()`, `pending()`, `data()`, `experiments()` | - |

## Dependencies

The SDK uses:
- **Google Guava**: Murmur3_32 hash (battle-tested)
- **Apache Commons Codec**: MD5 hashing
- **circe**: Type-safe JSON
- **sttp**: HTTP client
- **ScalaTest**: Testing framework

## Cross-Build

Supports both Scala 2.13 and Scala 3:
```bash
# Compile for current version
sbt compile

# Cross-compile
sbt +compile

# Test both versions
sbt +test
```

## Troubleshooting

### Compilation Errors
```bash
sbt clean compile 2>&1 | tee build.log
```

### Test Failures
```bash
sbt "testOnly *Murmur3Test" # Run specific test
sbt test 2>&1 | tee test.log # Run all tests
```

### Docker Build Issues
```bash
docker-compose build --no-cache --progress=plain scala-sdk 2>&1 | tee docker-build.log
```

## License

MIT

## Support

- GitHub Issues: https://github.com/absmartly/scala-sdk
- Documentation: https://docs.absmartly.com/
- Email: sdk@absmartly.com
