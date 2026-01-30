# ABsmartly SDK for Scala

[![Maven Central](https://img.shields.io/maven-central/v/com.absmartly/absmartly-sdk_2.13.svg)](https://search.maven.org/artifact/com.absmartly/absmartly-sdk)
[![Scaladoc](https://javadoc.io/badge2/com.absmartly/absmartly-sdk_2.13/javadoc.svg)](https://javadoc.io/doc/com.absmartly/absmartly-sdk_2.13)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A Scala SDK for [ABsmartly](https://www.absmartly.com) - A/B testing and feature flagging platform.

## Compatibility

The ABsmartly Scala SDK is compatible with Scala 2.13+ and Scala 3. It provides both asynchronous (Future-based) and synchronous interfaces for variant assignment and goal tracking. The SDK is built with type-safe JSON handling using circe and functional programming principles.

**Supported Scala Versions:**
- Scala 2.13.x
- Scala 3.3.x

## Installation

Add this to your `build.sbt`:

```scala
libraryDependencies += "com.absmartly" %% "absmartly-sdk" % "0.1.0"
```

For Maven, add this to your `pom.xml`:

```xml
<dependency>
  <groupId>com.absmartly</groupId>
  <artifactId>absmartly-sdk_2.13</artifactId>
  <version>0.1.0</version>
</dependency>
```

For Gradle:

```gradle
implementation 'com.absmartly:absmartly-sdk_2.13:0.1.0'
```

## Getting Started

Please follow the [installation](#installation) instructions before trying the following code.

### Initialization

This example assumes an API Key, an Application, and an Environment have been created in the ABsmartly web console.

```scala
import com.absmartly.sdk.{SDK, SDKConfig}
import scala.concurrent.ExecutionContext.Implicits.global

val config = SDKConfig(
  endpoint = "https://your-company.absmartly.io/v1",
  apiKey = "YOUR-API-KEY",
  environment = "development",
  application = "website"
)

val sdk = new SDK(config)
```

**SDK Options**

| Config       | Type     | Required? | Default | Description                                                                                                                                                                   |
|:-------------|:---------|:---------:|:-------:|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| endpoint     | `String` |  &#9989;  |   `""`  | The URL to your API endpoint. Most commonly `"your-company.absmartly.io"`                                                                                                     |
| apiKey       | `String` |  &#9989;  |   `""`  | Your API key which can be found on the Web Console.                                                                                                                           |
| environment  | `String` |  &#9989;  |   `""`  | The environment of the platform where the SDK is installed. Environments are created on the Web Console and should match the available environments in your infrastructure.   |
| application  | `String` |  &#9989;  |   `""`  | The name of the application where the SDK is installed. Applications are created on the Web Console and should match the applications where your experiments will be running. |
| retries      | `Int`    | &#10060;  |   `5`   | Number of retry attempts for failed HTTP requests                                                                                                                             |
| timeout      | `Int`    | &#10060;  | `3000`  | Connection timeout in milliseconds                                                                                                                                            |

### Creating a New Context (Async)

```scala
import com.absmartly.sdk.{SDK, SDKConfig}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._

val config = SDKConfig(
  endpoint = "https://your-company.absmartly.io/v1",
  apiKey = "YOUR-API-KEY",
  environment = "development",
  application = "website"
)

val sdk = new SDK(config)

// Define units for the context
val units = Map("session_id" -> "5ebf06d8cb5d8137290c4abb64155584fbdb64d8")

// Create context asynchronously
val contextFuture = sdk.createContext(units)

// Wait for context to be ready
val context = Await.result(contextFuture, 5.seconds)

// Context is ready to use
println(s"Context ready: ${context.isReady()}")
```

### Creating a New Context with Pre-fetched Data (Sync)

When doing full-stack experimentation with ABsmartly, we recommend creating a context only once on the server-side. Creating a context involves a round-trip to the ABsmartly event collector. We can avoid repeating the round-trip on the client-side by sending the server-side data embedded in the first document.

```scala
import com.absmartly.sdk.{SDK, SDKConfig, ContextData}
import io.circe.parser._

val sdk = new SDK(config)

// Define units for the context
val units = Map("session_id" -> "5ebf06d8cb5d8137290c4abb64155584fbdb64d8")

// Load context data from ABsmartly API (fetch from backend)
val contextData: ContextData = decode[ContextData](apiResponse).getOrElse(
  throw new RuntimeException("Failed to parse context data")
)

// Create context with pre-fetched data - no network round-trip needed
val context = sdk.createContextWith(units, contextData)
assert(context.isReady()) // Context is immediately ready
```

### Refreshing the Context with Fresh Experiment Data

For long-running contexts, the context can be refreshed manually to pull updated experiment data:

```scala
// Fetch new data
val newDataFuture = sdk.createContext(context.getUnits())
val newData = Await.result(newDataFuture, 5.seconds).data()

// Refresh the context
context.refresh(newData)
```

### Setting Extra Units for a Context

You can add additional units to a context by calling the `setUnit()` method. This method may be used, for example, when a user logs in to your application, and you want to use the new unit type in the context.

**Note:** You cannot override an already set unit type as that would be a change of identity. In this case, you must create a new context instead.

```scala
context.setUnit("db_user_id", "1000013")

// Or set multiple units at once
context.setUnits(Map(
  "db_user_id" -> "1000013",
  "user_type" -> "premium"
))
```

### Setting Context Attributes

Attributes are used for audience targeting. The `setAttribute()` method can be called before the context is ready. It accepts circe Json values:

```scala
import io.circe.Json
import io.circe.syntax._

// Set attributes using circe Json
context.setAttribute("user_agent", "Mozilla/5.0".asJson)
context.setAttribute("customer_age", "new_customer".asJson)
context.setAttribute("age", 25.asJson)
context.setAttribute("premium", true.asJson)

// Or set multiple attributes at once
context.setAttributes(Map(
  "user_agent" -> "Mozilla/5.0".asJson,
  "customer_age" -> "new_customer".asJson
))
```

### Selecting a Treatment

```scala
val variant = context.treatment("exp_test_experiment")

if (variant == 0) {
  // User is in control group (variant 0)
  println("Control group")
} else {
  // User is in treatment group
  println(s"Treatment group: variant $variant")
}
```

### Tracking a Goal Achievement

Goals are created in the ABsmartly web console.

```scala
import io.circe.Json
import io.circe.syntax._

// Track a simple goal (no properties)
context.track("payment")

// Track a goal with properties
context.track("purchase", Some(Map(
  "item_count" -> Json.fromInt(1),
  "total_amount" -> Json.fromDoubleOrNull(1999.99)
)))
```

### Publishing Pending Data

Sometimes it is necessary to ensure all events have been published to the ABsmartly collector before proceeding. You can explicitly call the `publish()` method.

```scala
import scala.concurrent.Await
import scala.concurrent.duration._

// Publish asynchronously
val publishFuture = context.publish()
Await.result(publishFuture, 5.seconds)
```

### Finalizing

The `finalizeContext()` method will ensure all events have been published to the ABsmartly collector, like `publish()`, and will also "seal" the context, preventing any further events from being tracked.

**Note:** The method is named `finalizeContext()` instead of `finalize()` to avoid conflicts with Java's Object.finalize().

```scala
import scala.concurrent.Await
import scala.concurrent.duration._

// Finalize the context
val finalizeFuture = context.finalizeContext()
Await.result(finalizeFuture, 5.seconds)

// Context is now sealed - no more treatments or goals can be tracked
assert(context.isFinalized())
```

## Basic Usage

### Peek at Treatment Variants

Although generally not recommended, it is sometimes necessary to peek at a treatment without triggering an exposure. The ABsmartly SDK provides a `peek()` method for that.

```scala
val variant = context.peek("exp_test_experiment")

if (variant == 0) {
  // User is in control group (variant 0)
} else {
  // User is in treatment group
}
```

### Overriding Treatment Variants

During development, for example, it is useful to force a treatment for an experiment. This can be achieved with the `setOverride()` method.

```scala
context.setOverride("exp_test_experiment", 1) // Force variant 1

// You can also set multiple overrides
context.setOverride("exp_another_experiment", 0)

// Or set all at once
context.setOverrides(Map(
  "exp_test_experiment" -> 1,
  "exp_another_experiment" -> 0
))
```

### Custom Assignments

Custom assignments allow you to set a specific variant for an experiment programmatically.

```scala
context.setCustomAssignment("exp_test_experiment", 1)

// Or set multiple custom assignments at once
context.setCustomAssignments(Map(
  "exp_test_experiment" -> 1,
  "exp_another_experiment" -> 0
))
```

## Variable Values

Get configuration values from experiments. Variables allow you to configure different values for each variant.

```scala
// Get a variable value with a default fallback
val buttonColor = context.variableValue("button.color", "blue")
println(s"Button color: $buttonColor")

// Get other types of variables
val showBanner = context.variableValue("banner.show", "false")
val maxItems = context.variableValue("cart.max_items", "10")

// You can also peek at variable values without triggering exposure
val peekedColor = context.peekVariableValue("button.color", "blue")
```

## Custom Event Logger

You can implement a custom event logger to handle SDK events. This is useful for debugging, analytics, or integrating with other systems.

```scala
import com.absmartly.sdk.EventLogger
import io.circe.Json

class CustomEventLogger extends EventLogger {
  override def logEvent(eventType: String, data: Json): Unit = {
    eventType match {
      case "exposure" =>
        println(s"Exposure event: ${data.noSpaces}")
      case "goal" =>
        println(s"Goal event: ${data.noSpaces}")
      case "error" =>
        println(s"Error event: ${data.noSpaces}")
      case "ready" =>
        println("Context ready")
      case "refresh" =>
        println("Context refreshed")
      case "publish" =>
        println("Events published")
      case "close" =>
        println("Context finalized")
      case _ =>
        println(s"Unknown event: $eventType")
    }
  }
}

// Usage (when event logger support is added to SDK)
// val config = SDKConfig(
//   endpoint = "https://your-company.absmartly.io/v1",
//   apiKey = "YOUR-API-KEY",
//   environment = "development",
//   application = "website",
//   eventLogger = Some(new CustomEventLogger())
// )
```

**Event Types**

| Event      | When                                         | Data                                 |
|------------|----------------------------------------------|--------------------------------------|
| `error`    | Context receives an error                    | Error details in JSON                |
| `ready`    | Context turns ready                          | ContextData used to initialize       |
| `refresh`  | `refresh()` method succeeds                  | ContextData used to refresh          |
| `publish`  | `publish()` method succeeds                  | PublishEvent sent to collector       |
| `exposure` | `treatment()` succeeds on first exposure     | Exposure enqueued for publishing     |
| `goal`     | `track()` method succeeds                    | Goal enqueued for publishing         |
| `close`    | `finalizeContext()` succeeds the first time  | Empty object                         |

## Advanced Usage

### Getting Experiment Data

You can retrieve information about experiments in the current context.

```scala
// Get all experiment names
val experiments: List[String] = context.experiments()
for (name <- experiments) {
  println(s"Experiment: $name")
}

// Get custom field value for an experiment (if available)
val customValue = context.customFieldValue("exp_test", "analyst")
customValue.foreach(value => println(s"Analyst: $value"))
```

### Checking Variable Keys

You can get all variable keys for experiments.

```scala
val keys: Map[String, List[String]] = context.variableKeys()
for ((key, experiments) <- keys) {
  println(s"Variable key: $key used in experiments: ${experiments.mkString(", ")}")
}
```

### Getting Context State

Check the current state of the context:

```scala
// Check if context is ready
println(s"Ready: ${context.isReady()}")

// Check if context failed to initialize
println(s"Failed: ${context.isFailed()}")

// Check if context is finalized
println(s"Finalized: ${context.isFinalized()}")

// Check if context is finalizing
println(s"Finalizing: ${context.isFinalizing()}")

// Check number of pending events
println(s"Pending events: ${context.pending()}")
```

### Getting Units and Attributes

Retrieve the current units and attributes:

```scala
// Get a specific unit
val sessionId: Option[String] = context.getUnit("session_id")
sessionId.foreach(id => println(s"Session ID: $id"))

// Get all units
val allUnits: Map[String, String] = context.getUnits()
println(s"All units: $allUnits")

// Get a specific attribute
val userAgent: Option[Json] = context.getAttribute("user_agent")
userAgent.foreach(ua => println(s"User agent: $ua"))

// Get all attributes
val allAttrs: Map[String, Json] = context.getAttributes()
println(s"All attributes: $allAttrs")
```

## Error Handling

The SDK uses Scala's standard error handling mechanisms. Methods that can fail will throw exceptions:

```scala
import scala.util.{Try, Success, Failure}

// Using Try for exception handling
Try {
  context.setUnit("user_id", "12345")
} match {
  case Success(_) =>
    println("Unit set successfully")
  case Failure(e: IllegalStateException) if e.getMessage.contains("already set") =>
    println(s"Cannot override unit type: ${e.getMessage}")
  case Failure(e) =>
    println(s"Error: ${e.getMessage}")
}

// Or use try/catch
try {
  context.setUnit("user_id", "12345")
  println("Unit set successfully")
} catch {
  case e: IllegalStateException if e.getMessage.contains("already set") =>
    println(s"Cannot override unit type: ${e.getMessage}")
  case e: IllegalStateException if e.getMessage.contains("finalized") =>
    println("Cannot modify finalized context")
  case e: Exception =>
    println(s"Error: ${e.getMessage}")
}
```

## Async Operations with Futures

The SDK uses Scala's `Future` for asynchronous operations. Here are best practices:

```scala
import scala.concurrent.{Future, Await}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

// Creating context asynchronously
val contextFuture: Future[Context] = sdk.createContext(units)

// Chain operations
val resultFuture = contextFuture.map { context =>
  context.treatment("exp_test")
}.flatMap { variant =>
  if (variant > 0) {
    // Publish events
    contextFuture.flatMap(_.publish())
  } else {
    Future.successful(())
  }
}

// Handle success and failure
resultFuture.onComplete {
  case Success(_) =>
    println("Operation completed successfully")
  case Failure(exception) =>
    println(s"Operation failed: ${exception.getMessage}")
}

// Or use for-comprehension
val workflow = for {
  context <- sdk.createContext(units)
  _ = context.treatment("exp_test")
  _ <- context.publish()
  _ <- context.finalizeContext()
} yield ()

// Wait for completion (blocking)
Await.result(workflow, 10.seconds)
```

## Thread Safety

The Context is designed for single-threaded use within a request or session. If you need to share a context across threads, ensure proper synchronization:

```scala
import java.util.concurrent.locks.ReentrantLock

class ThreadSafeContextWrapper(context: Context) {
  private val lock = new ReentrantLock()

  def treatment(experimentName: String): Int = {
    lock.lock()
    try {
      context.treatment(experimentName)
    } finally {
      lock.unlock()
    }
  }

  def track(goalName: String, properties: Option[Map[String, Json]] = None): Unit = {
    lock.lock()
    try {
      context.track(goalName, properties)
    } finally {
      lock.unlock()
    }
  }
}
```

## Context API Reference

### State Methods

| Method            | Return Type | Description                                   |
|-------------------|-------------|-----------------------------------------------|
| `isReady()`       | `Boolean`   | Returns true if context is ready to use      |
| `isFailed()`      | `Boolean`   | Returns true if context failed to initialize |
| `isFinalized()`   | `Boolean`   | Returns true if context is finalized          |
| `isFinalizing()`  | `Boolean`   | Returns true if context is finalizing         |
| `pending()`       | `Int`       | Number of pending events to publish           |
| `data()`          | `ContextData` | Get the context data (experiments)          |
| `experiments()`   | `List[String]` | List of experiment names                   |

### Unit Methods

| Method                                    | Return Type           | Description                    |
|-------------------------------------------|-----------------------|--------------------------------|
| `setUnit(unitType: String, uid: String)`  | `Unit`                | Set a single unit              |
| `setUnits(units: Map[String, String])`    | `Unit`                | Set multiple units             |
| `getUnit(unitType: String)`               | `Option[String]`      | Get a specific unit            |
| `getUnits()`                              | `Map[String, String]` | Get all units                  |

### Attribute Methods

| Method                                       | Return Type         | Description                     |
|----------------------------------------------|---------------------|---------------------------------|
| `setAttribute(name: String, value: Json)`    | `Unit`              | Set a single attribute          |
| `setAttributes(attrs: Map[String, Json])`    | `Unit`              | Set multiple attributes         |
| `getAttribute(name: String)`                 | `Option[Json]`      | Get a specific attribute        |
| `getAttributes()`                            | `Map[String, Json]` | Get all attributes              |

### Treatment Methods

| Method                            | Return Type | Description                                      |
|-----------------------------------|-------------|--------------------------------------------------|
| `treatment(experimentName: String)` | `Int`     | Get variant and queue exposure (0 = control)     |
| `peek(experimentName: String)`      | `Int`     | Get variant without queueing exposure            |

### Variable Methods

| Method                                                      | Return Type                 | Description                                    |
|-------------------------------------------------------------|-----------------------------|------------------------------------------------|
| `variableValue(key: String, defaultValue: String)`          | `String`                    | Get variable value and queue exposure          |
| `peekVariableValue(key: String, defaultValue: String)`      | `String`                    | Get variable value without queueing exposure   |
| `variableKeys()`                                            | `Map[String, List[String]]` | Get all variable keys with their experiments   |

### Override and Custom Assignment Methods

| Method                                              | Return Type | Description                        |
|-----------------------------------------------------|-------------|------------------------------------|
| `setOverride(experimentName: String, variant: Int)` | `Unit`      | Override a variant                 |
| `setOverrides(overrides: Map[String, Int])`         | `Unit`      | Set multiple overrides             |
| `setCustomAssignment(experimentName: String, variant: Int)` | `Unit` | Set custom assignment      |
| `setCustomAssignments(assignments: Map[String, Int])` | `Unit`    | Set multiple custom assignments    |

### Goal Tracking Methods

| Method                                                    | Return Type | Description                |
|-----------------------------------------------------------|-------------|----------------------------|
| `track(goalName: String, properties: Option[Map[String, Json]])` | `Unit` | Track a goal achievement |

### Publishing and Lifecycle Methods

| Method                | Return Type   | Description                                    |
|-----------------------|---------------|------------------------------------------------|
| `publish()`           | `Future[Unit]` | Publish pending events to collector           |
| `finalizeContext()`   | `Future[Unit]` | Publish events and seal context               |
| `refresh(newData: ContextData)` | `Unit` | Refresh context with new experiment data |

## Testing

Run the test suite:

```bash
sbt test
```

For cross-platform testing:

```bash
# Test on Scala 2.13
sbt +test

# Test on specific Scala version
sbt ++2.13.12 test
sbt ++3.3.1 test
```

## Building

Build the project:

```bash
sbt compile
```

Create a JAR:

```bash
sbt package
```

Cross-compile for all supported Scala versions:

```bash
sbt +package
```

Publish locally:

```bash
sbt publishLocal
```

## Type Safety

The Scala SDK is built with type safety in mind using circe for JSON handling:

```scala
import io.circe.Json
import io.circe.syntax._

// Type-safe JSON construction
val attributes = Map(
  "userId" -> 12345.asJson,
  "email" -> "user@example.com".asJson,
  "isPremium" -> true.asJson,
  "preferences" -> Json.obj(
    "theme" -> "dark".asJson,
    "notifications" -> true.asJson
  )
)

context.setAttributes(attributes)

// Type-safe JSON extraction
context.getAttribute("userId").foreach { json =>
  json.asNumber.flatMap(_.toInt).foreach { userId =>
    println(s"User ID: $userId")
  }
}
```

## Best Practices

1. **Create context once per request/session**: Avoid creating multiple contexts for the same user in a single request.

2. **Use pre-fetched data for SSR**: When doing server-side rendering, fetch context data once and reuse it on the client.

3. **Always finalize contexts**: Call `finalizeContext()` when you're done to ensure events are published.

4. **Handle Futures properly**: Don't block on Futures in production code. Use callbacks or for-comprehensions.

5. **Use type-safe JSON**: Leverage circe's type-safe API for attributes and goal properties.

6. **Don't override units**: Once a unit type is set, create a new context instead of trying to override it.

7. **Set attributes early**: Set all known attributes before calling `treatment()` for accurate audience targeting.

## Documentation

- [Scaladoc](https://javadoc.io/doc/com.absmartly/absmartly-sdk_2.13/latest/index.html)
- [ABsmartly Documentation](https://docs.absmartly.com)
- [Quickstart Guide](QUICKSTART.md)
- [Contributing Guide](CONTRIBUTING.md)

## About A/B Smartly

**A/B Smartly** is the leading provider of state-of-the-art, on-premises, full-stack experimentation platforms for engineering and product teams that want to confidently deploy features as fast as they can develop them.

A/B Smartly's real-time analytics helps engineering and product teams ensure that new features will improve the customer experience without breaking or degrading performance and/or business metrics.

### Have a look at our growing list of clients and SDKs:

- [JavaScript SDK](https://www.github.com/absmartly/javascript-sdk)
- [Java SDK](https://www.github.com/absmartly/java-sdk)
- [PHP SDK](https://www.github.com/absmartly/php-sdk)
- [Swift SDK](https://www.github.com/absmartly/swift-sdk)
- [Vue2 SDK](https://www.github.com/absmartly/vue2-sdk)
- [Vue3 SDK](https://www.github.com/absmartly/vue3-sdk)
- [React SDK](https://www.github.com/absmartly/react-sdk)
- [Python3 SDK](https://www.github.com/absmartly/python3-sdk)
- [Go SDK](https://www.github.com/absmartly/go-sdk)
- [Ruby SDK](https://www.github.com/absmartly/ruby-sdk)
- [.NET SDK](https://www.github.com/absmartly/dotnet-sdk)
- [Dart SDK](https://www.github.com/absmartly/dart-sdk)
- [Flutter SDK](https://www.github.com/absmartly/flutter-sdk)
- [Rust SDK](https://www.github.com/absmartly/rust-sdk)
- [Scala SDK](https://www.github.com/absmartly/scala-sdk)

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Support

- [Documentation](https://docs.absmartly.com)
- [GitHub Issues](https://github.com/absmartly/scala-sdk/issues)
- Email: sdk@absmartly.com
