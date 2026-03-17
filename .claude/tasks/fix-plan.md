# Scala SDK Fix Plan

Based on full review of PR #1.

## Critical

### 1. FIX: _flush() must actually publish events to server
- **File**: `Context.scala:296-329`
- **Issue**: `_flush()` builds a `PublishEvent`, logs it locally, clears queues, and returns `Future.successful(())` — but NEVER calls `sdk.publish()`. ALL experiment data is silently discarded.
- **Fix**: Re-add the publish call:
  ```scala
  val publishFuture = sdk.publish(event)
  publishFuture.map { _ =>
    eventLogger.foreach(_.handleEvent(context, EventType.Publish, eventData))
  }
  ```
  Ensure `sdk.publish()` method exists and accepts the `PublishEvent`. Check the previous implementation or Java SDK for reference.

### 2. Fix ReDoS "protection" — threads leak indefinitely
- **File**: `Evaluator.scala:269-272`
- **Issue**: `Await.result` timeout doesn't interrupt the regex thread. Leaked threads exhaust the global pool.
- **Fix**: Use `Thread.interrupt()` after timeout:
  ```scala
  val thread = new Thread(() => regex.findFirstIn(text))
  thread.start()
  try {
    thread.join(100) // 100ms timeout
    if (thread.isAlive) { thread.interrupt(); None }
    else result
  } catch { case _: InterruptedException => None }
  ```
  Or better: limit input length and pattern length instead of using timeouts.

## Important

### 3. Fix Logger thread safety
- **File**: `Logger.scala:49-57`
- **Fix**: Mark `instance` as `@volatile`:
  ```scala
  @volatile private var instance: Logger = defaultLogger
  ```

### 4. Fix unsynchronized state reads
- **File**: `Context.scala:67-70`
- **Fix**: Mark `_ready`, `_failed`, `_finalized`, `_finalizing` as `@volatile`, or read them inside `lock.synchronized`.

### 5. Fix unsynchronized pending() reads
- **File**: `Context.scala:83`
- **Fix**: Wrap in `lock.synchronized`:
  ```scala
  def pending(): Int = lock.synchronized {
    _exposures.length + _goals.length
  }
  ```

### 6. Fix unsynchronized variableValue/peekVariableValue
- **File**: `Context.scala:202-209`
- **Fix**: Add `lock.synchronized` like `treatment()` and `peek()`.

### 7. Fix API key leak in error logs
- **File**: `SDK.scala:81,93`
- **Fix**: Replace `config.apiKey.take(8)` with masked version:
  ```scala
  s"***${config.apiKey.takeRight(4)}"
  ```

## Minor

### 8. Remove unnecessary _assigners removal on setOverride
- **File**: `Context.scala:159`
- **Fix**: Remove `_assigners.remove(...)` — overrides bypass assignment entirely.

### 9. Extract shared HTTP logic between sync/async fetch
- **File**: `SDK.scala:197-272`
- **Fix**: Have sync version delegate to async, or extract shared parse/error-handling logic.

### 10. Remove non-idiomatic return statement
- **File**: `Context.scala:303`
- **Fix**: Replace `return Future.successful(())` with `if/else` control flow.

### 11. Don't catch OutOfMemoryError
- **File**: `Matcher.scala:59-63`
- **Fix**: Catch `Exception` instead of `Throwable` to avoid catching `OutOfMemoryError`.

### 12. Fix customFieldValue boolean parsing
- **File**: `Context.scala:235`
- **Fix**: Use `Try(field.value.toBoolean).toOption` instead of bare `.toBoolean`.

## Additional Findings (Full Review v3)

### 13. SECURITY: SDK.publish() signature mismatch — _flush() cannot call it even if fixed
- **File**: `SDK.scala:129-181`, `Context.scala:296-329`
- **Issue**: `SDK.publish()` takes `(units, hashed, exposures, goals)` as separate parameters, but `_flush()` builds a `PublishEvent` object. Fix #1 suggests calling `sdk.publish(event)` but that signature doesn't exist. The _flush method must be updated to call `sdk.publish(hashedUnits, publishEvent.hashed, exposures, goals)` with the correct decomposed arguments, or SDK.publish needs a `PublishEvent` overload.
- **Severity**: Critical (blocks fix #1)

### 14. CORRECTNESS: _flush() clears queues before publish succeeds — data loss on failure
- **File**: `Context.scala:323-326`
- **Issue**: `_flush()` clears `_exposures` and `_goals` synchronously (line 324-325) BEFORE the publish Future completes (currently `Future.successful(())` but even after fix #1). If the HTTP publish call fails, the events are already gone. The clear should happen inside the `.map` callback of the publish Future, not before it.
- **Severity**: Critical

### 15. CORRECTNESS: refresh() not synchronized
- **File**: `Context.scala:368-412`
- **Issue**: `refresh()` reads and mutates `_assignments`, `_index`, `_data` without `lock.synchronized`. Other methods like `treatment()` and `setOverride()` hold the lock, creating a race condition where refresh can corrupt state mid-assignment.
- **Severity**: Important

### 16. CORRECTNESS: data() and experiments() not synchronized
- **File**: `Context.scala:85-93`
- **Issue**: `data()` returns `_data` and `experiments()` iterates `_data.experiments` without locking. Since `refresh()` and `setData()` mutate `_data`, these can return partially-updated state.
- **Severity**: Important

### 17. CORRECTNESS: getAttribute/getAttributes not synchronized
- **File**: `Context.scala:139-149`
- **Issue**: `getAttribute()` iterates `_attributes` (a mutable ListBuffer) without locking, while `setAttribute()` holds the lock when appending. Concurrent read during write on ListBuffer is unsafe.
- **Severity**: Important

### 18. CORRECTNESS: getUnit/getUnits not synchronized
- **File**: `Context.scala:119-121`
- **Issue**: `getUnit()` and `getUnits()` read from mutable `_units` Map without locking while `setUnit()` mutates it under lock. Concurrent access on mutable.Map is unsafe.
- **Severity**: Important

### 19. CORRECTNESS: _variableValue calls _assign and _queueExposure without lock
- **File**: `Context.scala:655-680`
- **Issue**: `variableValue()` and `peekVariableValue()` (fix #6) call `_variableValue()` which internally calls `_assign()` and `_queueExposure()`. These methods mutate `_assignments` and `_exposures`. Even if the outer methods are synchronized, the current code is NOT synchronized. The entire `_variableValue` body needs to run under `lock.synchronized`.
- **Severity**: Important (related to fix #6 but more specific about what needs locking)

### 20. PERFORMANCE: HttpURLConnectionBackend is blocking and not thread-safe for concurrent use
- **File**: `SDK.scala:35`
- **Issue**: `HttpURLConnectionBackend()` creates a new HTTP connection per request but the backend instance is shared. The `createContext` wraps calls in `Future { ... }` which runs on the global ExecutionContext, but HttpURLConnection is known to have synchronization issues. Consider using `AsyncHttpClientBackend` or at minimum documenting the limitation.
- **Severity**: Minor

### 21. SIMPLIFICATION: Evaluator uses global ExecutionContext for regex timeout
- **File**: `Evaluator.scala:25`
- **Issue**: `private implicit val ec: ExecutionContext = ExecutionContext.global` is declared at the object level. This means regex timeouts (fix #2) block threads from the global pool. Even after fixing the thread leak, regex evaluation contention could starve the application's global pool. The regex timeout approach should be replaced entirely with input length limits (already has pattern length limit of 500, but no input text length limit).
- **Severity**: Minor (supplements fix #2)

### 22. CORRECTNESS: Matcher catches StackOverflowError and OutOfMemoryError separately but both should propagate
- **File**: `Matcher.scala:59-63`
- **Issue**: Fix #11 notes catching `OutOfMemoryError`, but the code also catches `StackOverflowError` (line 59) and returns `None` instead of propagating. Both are `VirtualMachineError` subtypes that indicate unrecoverable JVM state. Catching either is dangerous — the JVM may be in an inconsistent state. Both should propagate, or at minimum the catch should be `case NonFatal(e)` which correctly excludes all `VirtualMachineError` subclasses.
- **Severity**: Important (extends fix #11)

### 23. PR BEST PRACTICES: README removes useful documentation sections
- **File**: `README.md` (diff lines 429-997)
- **Issue**: The PR removes several useful documentation sections: Error Handling examples, Async Operations with Futures best practices, Thread Safety guidance, Context API Reference table, Advanced Request Configuration, Testing/Building instructions. While some trimming is reasonable, the API Reference table and Error Handling sections are valuable for users. Consider keeping those or moving them to a separate doc.
- **Severity**: Minor

### 24. CORRECTNESS: SDK.createContext publish signature doesn't send PublishEvent format
- **File**: `SDK.scala:134-152`
- **Issue**: `SDK.publish()` constructs its own JSON body with `units`, `hashed`, `exposures`, `goals`, `publishedAt` as top-level keys, but doesn't include `attributes`. The `PublishEvent` model includes `attributes` field (line 210 of Models.scala) which Context._flush() populates. When fix #1 is applied, attributes will be silently dropped unless SDK.publish() is updated to accept and serialize them.
- **Severity**: Important

### 25. SECURITY: Unit IDs logged in plaintext
- **File**: `Context.scala:111`
- **Issue**: `logger.debug(s"Set unit '$unitType' = $uid")` logs the raw unit ID (e.g., user session IDs, user IDs). These are PII that should not appear in logs. The log should either omit the value or hash/truncate it.
- **Severity**: Important
