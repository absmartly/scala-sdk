# ABsmartly Scala SDK

A Scala SDK for [ABsmartly](https://www.absmartly.com) - A/B testing and feature flagging platform.

## Compatibility

The ABsmartly Scala SDK is compatible with Scala 2.13+ and Scala 3. It provides both asynchronous (Future-based) and synchronous interfaces for variant assignment and goal tracking. The SDK is built with type-safe JSON handling using circe and functional programming principles.

**Supported Scala Versions:**
- Scala 2.13.x
- Scala 3.3.x

## Installation

### sbt

Add this to your `build.sbt`:

```scala
libraryDependencies += "com.absmartly" %% "absmartly-sdk" % "0.1.0"
```

### Maven

Add this to your `pom.xml`:

```xml
<dependency>
  <groupId>com.absmartly</groupId>
  <artifactId>absmartly-sdk_2.13</artifactId>
  <version>0.1.0</version>
</dependency>
```

### Gradle

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

#### With Optional Parameters

```scala
val config = SDKConfig(
  endpoint = "https://your-company.absmartly.io/v1",
  apiKey = "YOUR-API-KEY",
  environment = "development",
  application = "website",
  retries = 3,
  timeout = 5000
)

val sdk = new SDK(config)
```

**SDK Options**

| Config       | Type     | Required? | Default | Description                                                                                                                                                                   |
|:-------------|:---------|:---------:|:-------:|:------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| endpoint     | `String` |  &#9989;  |   `""`  | The URL to your API endpoint. Most commonly `"https://your-company.absmartly.io/v1"`                                                                                         |
| apiKey       | `String` |  &#9989;  |   `""`  | Your API key which can be found on the Web Console.                                                                                                                           |
| environment  | `String` |  &#9989;  |   `""`  | The environment of the platform where the SDK is installed. Environments are created on the Web Console and should match the available environments in your infrastructure.   |
| application  | `String` |  &#9989;  |   `""`  | The name of the application where the SDK is installed. Applications are created on the Web Console and should match the applications where your experiments will be running. |
| retries      | `Int`    | &#10060;  |   `5`   | Number of retry attempts for failed HTTP requests                                                                                                                             |
| timeout      | `Int`    | &#10060;  | `3000`  | Connection timeout in milliseconds                                                                                                                                            |
| eventLogger  | `EventLogger` | &#10060; | `NoOpEventLogger` | Custom event logger for SDK events (see Advanced section)                                                                                                      |

## Creating a New Context

### Asynchronously

```scala
import com.absmartly.sdk.{SDK, SDKConfig}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._

val units = Map("session_id" -> "5ebf06d8cb5d8137290c4abb64155584fbdb64d8")

val contextFuture = sdk.createContext(units)

val context = Await.result(contextFuture, 5.seconds)

println(s"Context ready: ${context.isReady()}")
```

### With Pre-fetched Data

When doing full-stack experimentation with ABsmartly, we recommend creating a context only once on the server-side. Creating a context involves a round-trip to the ABsmartly event collector. We can avoid repeating the round-trip on the client-side by sending the server-side data embedded in the first document.

```scala
import com.absmartly.sdk.{SDK, SDKConfig, ContextData}

val units = Map("session_id" -> "5ebf06d8cb5d8137290c4abb64155584fbdb64d8")

val contextData: ContextData = sdk.fetchContextData()

val context = sdk.createContextWith(units, contextData)
assert(context.isReady())
```

### Refreshing the Context with Fresh Experiment Data

For long-running contexts, the context can be refreshed manually to pull updated experiment data.

```scala
val newData = sdk.fetchContextData()
context.refresh(newData)
```

### Setting Extra Units

You can add additional units to a context by calling the `setUnit()` or `setUnits()` method. This method may be used, for example, when a user logs in to your application, and you want to use the new unit type in the context.

**Note:** You cannot override an already set unit type as that would be a change of identity. In this case, you must create a new context instead. The `setUnit()` and `setUnits()` methods can be called before the context is ready.

```scala
context.setUnit("db_user_id", "1000013")

context.setUnits(Map(
  "db_user_id" -> "1000013",
  "user_type" -> "premium"
))
```

## Basic Usage

### Selecting a Treatment

```scala
val variant = context.treatment("exp_test_experiment")

if (variant == 0) {
  // User is in control group (variant 0)
} else {
  // User is in treatment group
}
```

### Treatment Variables

Variables allow you to configure different values for each variant.

```scala
val buttonColor = context.variableValue("button.color", "blue")

val maxItems = context.variableValue("cart.max_items", "10")
```

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

#### Peeking at Variables

```scala
val peekedColor = context.peekVariableValue("button.color", "blue")
```

### Overriding Treatment Variants

During development, for example, it is useful to force a treatment for an experiment. This can be achieved with the `setOverride()` and/or `setOverrides()` methods.
The `setOverride()` and `setOverrides()` methods can be called before the context is ready.

```scala
context.setOverride("exp_test_experiment", 1)

context.setOverrides(Map(
  "exp_test_experiment" -> 1,
  "exp_another_experiment" -> 0
))
```

## Advanced

### Context Attributes

Attributes are used for audience targeting. The `setAttribute()` and `setAttributes()` methods can be called before the context is ready. They accept circe Json values.

```scala
import io.circe.Json
import io.circe.syntax._

context.setAttribute("user_agent", "Mozilla/5.0".asJson)
context.setAttribute("customer_age", "new_customer".asJson)
context.setAttribute("age", 25.asJson)

context.setAttributes(Map(
  "user_agent" -> "Mozilla/5.0".asJson,
  "customer_age" -> "new_customer".asJson
))
```

### Custom Assignments

Custom assignments allow you to set a specific variant for an experiment programmatically.

```scala
context.setCustomAssignment("exp_test_experiment", 1)

context.setCustomAssignments(Map(
  "exp_test_experiment" -> 1,
  "exp_another_experiment" -> 0
))
```

### Tracking Goals

Goals are created in the ABsmartly web console.

```scala
import io.circe.Json
import io.circe.syntax._

context.track("payment")

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

val publishFuture = context.publish()
Await.result(publishFuture, 5.seconds)
```

### Finalizing

The `finalizeContext()` method will ensure all events have been published to the ABsmartly collector, like `publish()`, and will also "seal" the context, preventing any further events from being tracked.

**Note:** The method is named `finalizeContext()` instead of `finalize()` to avoid conflicts with Java's `Object.finalize()`.

```scala
import scala.concurrent.Await
import scala.concurrent.duration._

val finalizeFuture = context.finalizeContext()
Await.result(finalizeFuture, 5.seconds)

assert(context.isFinalized())
```

### Custom Event Logger

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
```

Usage:

```scala
val config = SDKConfig(
  endpoint = "https://your-company.absmartly.io/v1",
  apiKey = "YOUR-API-KEY",
  environment = "development",
  application = "website",
  eventLogger = new CustomEventLogger()
)
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

## Platform-Specific Examples

### Using with Play Framework

Integrate ABsmartly into your Play Framework application using dependency injection.

```scala
// app/services/ABSmartlyService.scala
package services

import javax.inject._
import com.absmartly.sdk._
import play.api.Configuration
import scala.concurrent.ExecutionContext

@Singleton
class ABSmartlyService @Inject()(config: Configuration)(implicit ec: ExecutionContext) {

  private val sdk: SDK = {
    val sdkConfig = SDKConfig(
      endpoint = config.get[String]("absmartly.endpoint"),
      apiKey = config.get[String]("absmartly.apiKey"),
      application = config.get[String]("absmartly.application"),
      environment = config.get[String]("absmartly.environment")
    )
    new SDK(sdkConfig)
  }

  def createContext(sessionId: String): scala.concurrent.Future[Context] = {
    val units = Map("session_id" -> sessionId)
    sdk.createContext(units)
  }
}

// app/controllers/ProductController.scala
package controllers

import javax.inject._
import play.api.mvc._
import services.ABSmartlyService
import scala.concurrent.{ExecutionContext, Await}
import scala.concurrent.duration._

@Singleton
class ProductController @Inject()(
  cc: ControllerComponents,
  absmartlyService: ABSmartlyService
)(implicit ec: ExecutionContext) extends AbstractController(cc) {

  def show = Action.async { implicit request =>
    val sessionId = request.session.get("sessionId")
      .getOrElse(java.util.UUID.randomUUID().toString)

    absmartlyService.createContext(sessionId).map { context =>
      val treatment = context.treatment("exp_product_layout")

      context.finalizeContext()

      if (treatment == 0) {
        Ok(views.html.productControl()).withSession("sessionId" -> sessionId)
      } else {
        Ok(views.html.productTreatment()).withSession("sessionId" -> sessionId)
      }
    }
  }
}

// conf/application.conf
absmartly {
  endpoint = "https://your-company.absmartly.io/v1"
  endpoint = ${?ABSMARTLY_ENDPOINT}

  apiKey = ${ABSMARTLY_API_KEY}

  application = "website"
  application = ${?ABSMARTLY_APPLICATION}

  environment = "production"
  environment = ${?ABSMARTLY_ENVIRONMENT}
}
```

### Using with Akka HTTP

Use ABsmartly with Akka HTTP for reactive request handling.

```scala
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.model._
import com.absmartly.sdk._
import scala.concurrent.{ExecutionContext, Future}

object WebServer {

  def main(args: Array[String]): Unit = {
    implicit val system: ActorSystem = ActorSystem("absmartly-system")
    implicit val ec: ExecutionContext = system.dispatcher

    val sdkConfig = SDKConfig(
      endpoint = "https://your-company.absmartly.io/v1",
      apiKey = "YOUR-API-KEY",
      application = "website",
      environment = "production"
    )

    val sdk = new SDK(sdkConfig)

    val route: Route =
      path("product") {
        get {
          optionalCookie("session_id") { sessionCookie =>
            val sessionId = sessionCookie.map(_.value)
              .getOrElse(java.util.UUID.randomUUID().toString)

            val units = Map("session_id" -> sessionId)

            onSuccess(sdk.createContext(units)) { context =>
              val treatment = context.treatment("exp_product_layout")

              context.finalizeContext()

              val responseHtml = if (treatment == 0) {
                "<h1>Control Group</h1>"
              } else {
                "<h1>Treatment Group</h1>"
              }

              complete(HttpEntity(ContentTypes.`text/html(UTF-8)`, responseHtml))
            }
          }
        }
      }

    Http().newServerAt("localhost", 8080).bind(route)
    println("Server online at http://localhost:8080/")
  }
}
```

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
- [Scala SDK](https://www.github.com/absmartly/scala-sdk) (this package)
