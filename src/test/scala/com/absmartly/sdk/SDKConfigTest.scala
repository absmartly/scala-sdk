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
}
