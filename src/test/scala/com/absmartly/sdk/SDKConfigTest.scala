package com.absmartly.sdk

import org.scalatest.funsuite.AnyFunSuite

class SDKConfigTest extends AnyFunSuite {

  test("constructor validates endpoint is not empty") {
    assertThrows[IllegalArgumentException] {
      SDKConfig(
        endpoint = "",
        apiKey = "test-key",
        application = "test-app",
        environment = "test"
      )
    }
  }

  test("constructor validates apiKey is not empty") {
    assertThrows[IllegalArgumentException] {
      SDKConfig(
        endpoint = "https://test.absmartly.io/v1",
        apiKey = "",
        application = "test-app",
        environment = "test"
      )
    }
  }

  test("constructor validates application is not empty") {
    assertThrows[IllegalArgumentException] {
      SDKConfig(
        endpoint = "https://test.absmartly.io/v1",
        apiKey = "test-key",
        application = "",
        environment = "test"
      )
    }
  }

  test("constructor validates environment is not empty") {
    assertThrows[IllegalArgumentException] {
      SDKConfig(
        endpoint = "https://test.absmartly.io/v1",
        apiKey = "test-key",
        application = "test-app",
        environment = ""
      )
    }
  }

  test("constructor validates retries is non-negative") {
    assertThrows[IllegalArgumentException] {
      SDKConfig(
        endpoint = "https://test.absmartly.io/v1",
        apiKey = "test-key",
        application = "test-app",
        environment = "test",
        retries = -1
      )
    }
  }

  test("constructor validates timeout is positive") {
    assertThrows[IllegalArgumentException] {
      SDKConfig(
        endpoint = "https://test.absmartly.io/v1",
        apiKey = "test-key",
        application = "test-app",
        environment = "test",
        timeout = 0
      )
    }
  }

  test("constructor accepts valid config") {
    val config = SDKConfig(
      endpoint = "https://test.absmartly.io/v1",
      apiKey = "test-key",
      application = "test-app",
      environment = "test"
    )
    assert(config.endpoint == "https://test.absmartly.io/v1")
    assert(config.apiKey == "test-key")
    assert(config.application == "test-app")
    assert(config.environment == "test")
    assert(config.retries == 5)
    assert(config.timeout == 3000)
  }

  test("constructor accepts custom retries and timeout") {
    val config = SDKConfig(
      endpoint = "https://test.absmartly.io/v1",
      apiKey = "test-key",
      application = "test-app",
      environment = "test",
      retries = 10,
      timeout = 5000
    )
    assert(config.retries == 10)
    assert(config.timeout == 5000)
  }

  test("constructor accepts zero retries") {
    val config = SDKConfig(
      endpoint = "https://test.absmartly.io/v1",
      apiKey = "test-key",
      application = "test-app",
      environment = "test",
      retries = 0
    )
    assert(config.retries == 0)
  }

  test("X-Agent is absmartly-scala-sdk/<build version>") {
    val config = SDKConfig("https://test.absmartly.io/v1", "test-api-key-12345", "website", "production")
    assert(SdkBuildInfo.version.nonEmpty)
    assert(Http.Agent == s"absmartly-scala-sdk/${SdkBuildInfo.version}")
    assert(Http.headers(config).get("X-Agent").contains(Http.Agent))
  }

  // Cross-SDK scenarios 320/321: X-Agent is always absmartly-scala-sdk/<semver>,
  // on the context GET and the publish PUT alike, however the SDK was built.
  private val AgentPattern = "^absmartly-[a-z0-9]+-sdk/[0-9]+\\.[0-9]+\\.[0-9]+([-+][0-9A-Za-z.+-]*)?$"

  test("X-Agent is semver even when the build has no git metadata") {
    // sbt-dynver's fallback when built from a copy without .git or tags.
    assert(Http.agent("HEAD-20261113-1638") == "absmartly-scala-sdk/0.0.0-SNAPSHOT")
    assert(Http.agent("") == "absmartly-scala-sdk/0.0.0-SNAPSHOT")
    assert(Http.agent("HEAD-20261113-1638").matches(AgentPattern))
  }

  test("X-Agent keeps a semver build version") {
    assert(Http.agent("1.2.3") == "absmartly-scala-sdk/1.2.3")
    assert(Http.agent("1.2.3-4-abcdef12-SNAPSHOT") == "absmartly-scala-sdk/1.2.3-4-abcdef12-SNAPSHOT")
    assert(Http.agent("0.0.0+3-abcdef12") == "absmartly-scala-sdk/0.0.0+3-abcdef12")
    assert(Http.Agent.matches(AgentPattern))
  }
}
