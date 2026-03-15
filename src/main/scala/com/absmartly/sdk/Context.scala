package com.absmartly.sdk

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import io.circe.Json
import io.circe.syntax._

/**
 * Context - Main API for experiment interaction
 *
 * CRITICAL: This class implements ALL required methods (singular + plural)
 */
class Context(
  sdk: SDK,
  initialData: Option[ContextData],
  initialUnits: Map[String, String],
  options: ContextOptions,
  eventLogger: EventLogger = NoOpEventLogger
)(implicit ec: ExecutionContext) {

  def this(sdk: SDK, data: ContextData, units: Map[String, String], options: ContextOptions, eventLogger: EventLogger)(implicit ec: ExecutionContext) =
    this(sdk, Some(data), units, options, eventLogger)

  private val logger = Logger.get
  private val lock = new AnyRef

  @volatile private var _ready: Boolean = initialData.isDefined
  @volatile private var _failed: Boolean = false
  @volatile private var _failedError: Option[Throwable] = None
  @volatile private var _finalized: Boolean = false
  @volatile private var _finalizing: Boolean = false

  // Data
  private var _data: ContextData = initialData.getOrElse(ContextData(experiments = List.empty))
  private val _units: mutable.Map[String, String] = mutable.Map(initialUnits.toSeq: _*)
  private val _attributes: mutable.ListBuffer[Attribute] = mutable.ListBuffer()
  private val _overrides: mutable.Map[String, Int] = mutable.Map() ++ options.overrides
  private val _cassignments: mutable.Map[String, Int] = mutable.Map() ++ options.cassignments

  // Event queues
  private val _exposures: mutable.ListBuffer[Exposure] = mutable.ListBuffer()
  private val _goals: mutable.ListBuffer[Goal] = mutable.ListBuffer()

  // Assignment cache
  private val _assignments: mutable.Map[String, Assignment] = mutable.Map()

  // Experiment indices
  private var _index: Map[String, ExperimentData] = Map.empty
  private var _indexVariables: Map[String, List[ExperimentData]] = Map.empty

  // Assigners for each unit type
  private val _assigners: mutable.Map[String, VariantAssigner] = mutable.Map()

  // Attribute sequence counter for audience re-evaluation
  private var _attrsSeq: Int = 0

  // Initialize
  initialData.foreach { data =>
    _init(data)
    eventLogger.logEvent("ready", _data.asJson)
  }

  // ======================
  // State Methods
  // ======================

  def isReady(): Boolean = _ready
  def isFailed(): Boolean = _failed
  def isFinalized(): Boolean = _finalized
  def isFinalizing(): Boolean = _finalizing

  def setData(data: ContextData): Unit = lock.synchronized {
    _init(data)
    _ready = true
    eventLogger.logEvent("ready", _data.asJson)
  }

  def setDataFailed(error: Option[Throwable] = None): Unit = lock.synchronized {
    _failed = true
    _failedError = error
    _ready = true
  }

  def readyError(): Option[Throwable] = _failedError

  def pending(): Int = lock.synchronized {
    _exposures.length + _goals.length
  }

  def data(): ContextData = lock.synchronized {
    checkReady()
    _data
  }

  def experiments(): List[String] = lock.synchronized {
    checkReady()
    _data.experiments.map(_.name)
  }

  // ======================
  // Units Methods
  // ======================

  def setUnit(unitType: String, uid: String): Unit = lock.synchronized {
    checkNotFinalized()
    require(unitType.trim.nonEmpty, "Unit type must not be blank")
    require(uid.trim.nonEmpty, s"Unit '$unitType' UID must not be blank")

    _units.get(unitType) match {
      case Some(existing) if existing != uid =>
        throw new IllegalStateException(s"Unit '$unitType' UID already set")
      case _ =>
        _units(unitType) = uid
        // Invalidate assigner cache for this unit type
        _assigners.remove(unitType)
        val maskedUid = if (uid.length > 4) s"***${uid.takeRight(4)}" else "***"
        logger.debug(s"Set unit '$unitType' = $maskedUid")
    }
  }

  def setUnits(units: Map[String, String]): Unit = {
    units.foreach { case (unitType, uid) => setUnit(unitType, uid) }
  }

  def getUnit(unitType: String): Option[String] = lock.synchronized {
    _units.get(unitType)
  }

  def getUnits(): Map[String, String] = lock.synchronized {
    _units.toMap
  }

  // ======================
  // Attributes Methods
  // ======================

  def setAttribute(name: String, value: Json): Unit = lock.synchronized {
    checkNotFinalized()
    require(name.trim.nonEmpty, "Attribute name must not be blank")
    _attributes += Attribute(name, value, System.currentTimeMillis())
    _attrsSeq += 1
    logger.debug(s"Set attribute '$name'")
  }

  def setAttributes(attrs: Map[String, Json]): Unit = {
    attrs.foreach { case (name, value) => setAttribute(name, value) }
  }

  def getAttribute(name: String): Option[Json] = lock.synchronized {
    _attributes.reverseIterator.find(_.name == name).map(_.value)
  }

  def getAttributes(): Map[String, Json] = lock.synchronized {
    val result = mutable.Map[String, Json]()
    _attributes.foreach { attr =>
      result(attr.name) = attr.value
    }
    result.toMap
  }

  // ======================
  // Override Methods
  // ======================

  def setOverride(experimentName: String, variant: Int): Unit = lock.synchronized {
    _overrides(experimentName) = variant
    _assignments.remove(experimentName)
    logger.debug(s"Override '$experimentName' = $variant")
  }

  def setOverrides(overrides: Map[String, Int]): Unit = {
    overrides.foreach { case (name, variant) => setOverride(name, variant) }
  }

  // ======================
  // Custom Assignment Methods
  // ======================

  def setCustomAssignment(experimentName: String, variant: Int): Unit = lock.synchronized {
    checkNotFinalized()
    _cassignments(experimentName) = variant
    _assignments.remove(experimentName)
    logger.debug(s"Custom assignment '$experimentName' = $variant")
  }

  def setCustomAssignments(assignments: Map[String, Int]): Unit = {
    assignments.foreach { case (name, variant) => setCustomAssignment(name, variant) }
  }

  // ======================
  // Treatment Methods
  // ======================

  def treatment(experimentName: String): Int = lock.synchronized {
    checkReady(expectNotFinalized = true)
    val assignment = _assign(experimentName)
    _queueExposure(assignment)
    assignment.variant
  }

  def peek(experimentName: String): Int = lock.synchronized {
    checkReady(expectNotFinalized = true)
    _assign(experimentName).variant
  }

  // ======================
  // Variable Methods
  // ======================

  def variableValue(key: String, defaultValue: String): String = lock.synchronized {
    checkReady(expectNotFinalized = true)
    _variableValue(key, defaultValue, queueExposure = true)
  }

  def peekVariableValue(key: String, defaultValue: String): String = lock.synchronized {
    checkReady(expectNotFinalized = true)
    _variableValue(key, defaultValue, queueExposure = false)
  }

  def variableKeys(): Map[String, List[String]] = {
    checkReady()
    _indexVariables.map { case (key, exps) =>
      key -> exps.map(_.name)
    }
  }

  // ======================
  // Custom Fields Methods (Not fully implemented - placeholder)
  // ======================

  def customFieldValue(experimentName: String, fieldName: String): Option[Json] = {
    checkReady()
    _index.get(experimentName).flatMap { exp =>
      exp.customFieldValues.flatMap { fields =>
        fields.find(_.name == fieldName).map { field =>
          field.`type` match {
            case "number" =>
              field.value.toDoubleOption match {
                case Some(d) if d == d.toLong.toDouble => Json.fromLong(d.toLong)
                case Some(d) => Json.fromDoubleOrNull(d)
                case None => Json.fromString(field.value)
              }
            case "boolean" =>
              scala.util.Try(field.value.toBoolean).toOption match {
                case Some(b) => Json.fromBoolean(b)
                case None => Json.fromString(field.value)
              }
            case "json" =>
              io.circe.parser.parse(field.value).getOrElse(Json.fromString(field.value))
            case _ => Json.fromString(field.value)
          }
        }
      }
    }
  }

  def customFieldKeys(): List[String] = {
    checkReady()
    _index.values.flatMap { exp =>
      exp.customFieldValues.map(_.map(_.name)).getOrElse(List.empty)
    }.toList.distinct
  }

  def customFieldValueType(experimentName: String, fieldName: String): Option[String] = {
    checkReady()
    _index.get(experimentName).flatMap { exp =>
      exp.customFieldValues.flatMap { fields =>
        fields.find(_.name == fieldName).map(_.`type`)
      }
    }
  }

  // ======================
  // Goal Tracking
  // ======================

  def track(goalName: String, properties: Option[Map[String, Json]] = None): Unit = lock.synchronized {
    checkNotFinalized()

    val goal = Goal(
      name = goalName,
      achievedAt = System.currentTimeMillis(),
      properties = if (properties.exists(_.nonEmpty)) properties else None
    )

    _goals += goal

    eventLogger.logEvent("goal", Json.obj(
      "name" -> Json.fromString(goal.name),
      "achievedAt" -> Json.fromLong(goal.achievedAt),
      "properties" -> goal.properties.map(p =>
        Json.obj(p.toSeq: _*)
      ).getOrElse(Json.Null)
    ))

    logger.debug(s"Tracked goal '$goalName' with ${properties.map(_.size).getOrElse(0)} properties")
  }

  // ======================
  // Publishing & Lifecycle
  // ======================

  def publish(): Future[Unit] = {
    checkReady(expectNotFinalized = true)
    _flush()
  }

  private def _flush(): Future[Unit] = {
    val (hashedUnits, exposures, goals, attributes) = lock.synchronized {
      (_getHashedUnits(), _exposures.toList, _goals.toList, _attributes.toList)
    }

    if (exposures.isEmpty && goals.isEmpty) {
      logger.debug("No events to publish")
      Future.successful(())
    } else {
      logger.debug(s"Publishing ${exposures.length} exposures, ${goals.length} goals")

      val unitsList = hashedUnits.map { case (unitType, hashedUid) =>
        PublishUnit(unitType, hashedUid)
      }.toList

      val publishEvent = PublishEvent(
        hashed = true,
        publishedAt = System.currentTimeMillis(),
        units = unitsList,
        exposures = exposures,
        goals = goals,
        attributes = if (attributes.nonEmpty) Some(attributes) else None
      )

      eventLogger.logEvent("publish", publishEvent.asJson)

      val unitsMap = hashedUnits
      val attrOpt = if (attributes.nonEmpty) Some(attributes) else None

      sdk.publish(unitsMap, true, exposures, goals, attrOpt).map { _ =>
        lock.synchronized {
          _exposures --= exposures
          _goals --= goals
        }
        eventLogger.logEvent("publish_success", publishEvent.asJson)
      }
    }
  }

  def finalizeContext(): Future[Unit] = {
    val shouldFinalize = lock.synchronized {
      if (_finalized) {
        logger.debug("Context already finalized")
        false
      } else if (_finalizing) {
        logger.warn("Context already finalizing")
        false
      } else {
        _finalizing = true
        true
      }
    }

    if (!shouldFinalize) {
      return Future.successful(())
    }

    val pendingCount = pending()
    logger.info(s"Finalizing context with $pendingCount pending events")

    _flush().map { _ =>
      lock.synchronized {
        _finalized = true
        _finalizing = false
      }
      eventLogger.logEvent("finalize", Json.Null)
      logger.info("Context finalized successfully")
    }.recover { case ex =>
      lock.synchronized {
        _finalizing = false
      }
      logger.error(s"Finalization failed, lost $pendingCount events: ${ex.getMessage}", ex)
      throw StateException(s"Finalization failed, $pendingCount events lost", Some(ex))
    }
  }

  def refresh(): Unit = {
    val newData = sdk.fetchContextData()
    refresh(newData)
  }

  def refresh(newData: ContextData): Unit = lock.synchronized {
    checkReady()
    checkNotFinalized()

    val oldIndex = _index
    _init(newData)
    eventLogger.logEvent("refresh", newData.asJson)

    val toRemove = mutable.ListBuffer[String]()
    val toReset = mutable.ListBuffer[String]()

    _assignments.foreach { case (name, assignment) =>
      val oldExp = oldIndex.get(name)
      val newExp = _index.get(name)

      val shouldClear = (oldExp, newExp) match {
        case (Some(old), Some(exp)) =>
          old.id != exp.id ||
          old.iteration != exp.iteration ||
          old.fullOnVariant != exp.fullOnVariant ||
          old.trafficSplit != exp.trafficSplit
        case (Some(_), None) =>
          assignment.assigned
        case (None, Some(_)) =>
          true
        case (None, None) =>
          false
      }

      val hasOverride = _overrides.contains(name)

      if (shouldClear && !hasOverride) {
        toRemove += name
      } else {
        toReset += name
      }
    }

    toRemove.foreach(_assignments.remove)
    toReset.foreach { name =>
      _assignments.get(name).foreach { a =>
        _assignments(name) = a.copy(exposed = false)
      }
    }
  }

  // ======================
  // Private Methods
  // ======================

  private def _parseConfig(config: Json): Option[Json] = {
    config.asObject match {
      case Some(_) => Some(config)
      case None =>
        config.asString.flatMap { str =>
          io.circe.parser.parse(str).toOption.flatMap { parsed =>
            if (parsed.isObject) Some(parsed) else None
          }
        }
    }
  }

  private def _init(data: ContextData): Unit = {
    _data = data

    val parsedExperiments = data.experiments.map { exp =>
      val parsedVariants = exp.variants.map { variant =>
        variant.config match {
          case Some(cfg) => variant.copy(config = _parseConfig(cfg).orElse(variant.config))
          case None => variant
        }
      }
      exp.copy(variants = parsedVariants)
    }

    _index = parsedExperiments.map(exp => exp.name -> exp).toMap

    val varIndex = mutable.Map[String, mutable.ListBuffer[ExperimentData]]()
    parsedExperiments.foreach { exp =>
      exp.variants.zipWithIndex.foreach { case (variant, idx) =>
        variant.config.foreach { config =>
          config.asObject.foreach { obj =>
            obj.keys.foreach { key =>
              varIndex.getOrElseUpdate(key, mutable.ListBuffer()) += exp
            }
          }
        }
      }
    }
    _indexVariables = varIndex.map { case (k, v) => k -> v.toList }.toMap
  }

  private def _assign(experimentName: String): Assignment = {
    val hasOverride = _overrides.contains(experimentName)
    val hasCustom = _cassignments.contains(experimentName)
    val experiment = _index.get(experimentName)

    _assignments.get(experimentName) match {
      case Some(assignment) if isAssignmentValid(assignment, experiment, hasOverride, hasCustom) =>
        return assignment
      case _ =>
    }

    val assignment = experiment match {
      case Some(exp) if hasOverride =>
        Assignment(
          id = exp.id,
          name = experimentName,
          unitType = exp.unitType,
          iteration = exp.iteration,
          trafficSplit = exp.trafficSplit,
          fullOnVariant = exp.fullOnVariant,
          variant = _overrides(experimentName),
          assigned = false,
          exposed = false,
          eligible = true,
          overridden = true,
          audienceMismatch = false,
          fullOn = false,
          custom = false,
          attrsSeq = _attrsSeq
        )

      case Some(exp) =>
        val audienceMismatch = checkAudienceMismatch(exp)

        if (exp.audienceStrict && audienceMismatch) {
          createAssignmentRaw(exp, 0, assigned = false, eligible = true,
            audienceMismatch = true, fullOn = false, custom = false)
        } else if (exp.fullOnVariant == 0) {
          _units.get(exp.unitType) match {
            case Some(_) =>
              val assigner = getOrCreateAssigner(exp.unitType)
              val eligible = assigner.assign(exp.trafficSplit, exp.trafficSeedHi, exp.trafficSeedLo) == 1

              if (eligible) {
                if (hasCustom) {
                  createAssignmentRaw(exp, _cassignments(experimentName), assigned = true, eligible = true,
                    audienceMismatch = audienceMismatch, fullOn = false, custom = true)
                } else {
                  val variant = assigner.assign(exp.split, exp.seedHi, exp.seedLo)
                  createAssignmentRaw(exp, variant, assigned = true, eligible = true,
                    audienceMismatch = audienceMismatch, fullOn = false, custom = false)
                }
              } else {
                createAssignmentRaw(exp, 0, assigned = true, eligible = false,
                  audienceMismatch = audienceMismatch, fullOn = false, custom = false)
              }
            case None =>
              createAssignmentRaw(exp, 0, assigned = true, eligible = false,
                audienceMismatch = audienceMismatch, fullOn = false, custom = false)
          }
        } else {
          createAssignmentRaw(exp, exp.fullOnVariant, assigned = true, eligible = true,
            audienceMismatch = audienceMismatch, fullOn = true, custom = false)
        }

      case None =>
        val variant = if (hasOverride) _overrides(experimentName) else 0
        Assignment(
          id = 0,
          name = experimentName,
          unitType = "",
          iteration = 0,
          trafficSplit = List.empty,
          fullOnVariant = 0,
          variant = variant,
          assigned = false,
          exposed = false,
          eligible = true,
          overridden = hasOverride,
          audienceMismatch = false,
          fullOn = false,
          custom = hasCustom,
          attrsSeq = _attrsSeq
        )
    }

    _assignments(experimentName) = assignment
    assignment
  }

  private def isAssignmentValid(
    assignment: Assignment,
    experiment: Option[ExperimentData],
    hasOverride: Boolean,
    hasCustom: Boolean
  ): Boolean = {
    if (hasOverride) {
      assignment.overridden && assignment.variant == _overrides(assignment.name)
    } else {
      experiment match {
        case Some(exp) =>
          val baseValid = assignment.id == exp.id &&
            assignment.iteration == exp.iteration &&
            assignment.fullOnVariant == exp.fullOnVariant &&
            assignment.trafficSplit == exp.trafficSplit &&
            (!hasCustom || _cassignments(assignment.name) == assignment.variant)
          baseValid && audienceMatches(exp, assignment)
        case None =>
          !assignment.assigned
      }
    }
  }

  private def audienceMatches(exp: ExperimentData, assignment: Assignment): Boolean = {
    exp.audience match {
      case Some(aud) if aud.nonEmpty && aud != "null" && aud != "{}" =>
        if (_attrsSeq > assignment.attrsSeq) {
          val matcher = new AudienceMatcher(getAttributes())
          val newAudienceMismatch = matcher.evaluate(Some(aud)) match {
            case Some(result) => !result
            case None => false
          }
          newAudienceMismatch == assignment.audienceMismatch
        } else {
          true
        }
      case _ => true
    }
  }

  private def createAssignment(
    exp: ExperimentData,
    variant: Int,
    overridden: Boolean = false
  ): Assignment = {
    val audienceMismatch = checkAudienceMismatch(exp)
    createAssignmentRaw(exp, variant, assigned = true, eligible = true,
      audienceMismatch = audienceMismatch, fullOn = false, custom = false,
      overridden = overridden)
  }

  private def createAssignmentRaw(
    exp: ExperimentData,
    variant: Int,
    assigned: Boolean,
    eligible: Boolean,
    audienceMismatch: Boolean,
    fullOn: Boolean,
    custom: Boolean,
    overridden: Boolean = false
  ): Assignment = {
    Assignment(
      id = exp.id,
      name = exp.name,
      unitType = exp.unitType,
      iteration = exp.iteration,
      trafficSplit = exp.trafficSplit,
      fullOnVariant = exp.fullOnVariant,
      variant = variant,
      assigned = assigned,
      exposed = false,
      eligible = eligible,
      overridden = overridden,
      audienceMismatch = audienceMismatch,
      fullOn = fullOn,
      custom = custom,
      attrsSeq = _attrsSeq
    )
  }

  private def checkAudienceMismatch(exp: ExperimentData): Boolean = {
    exp.audience.exists { aud =>
      if (aud.isEmpty || aud == "null" || aud == "{}") {
        false
      } else {
        val matcher = new AudienceMatcher(getAttributes())
        matcher.evaluate(Some(aud)) match {
          case Some(result) => !result
          case None => false
        }
      }
    }
  }

  private def getOrCreateAssigner(unitType: String): VariantAssigner = {
    _assigners.getOrElseUpdate(unitType, {
      _units.get(unitType) match {
        case Some(uid) =>
          new VariantAssigner(Utils.hashUnit(uid))
        case None =>
          new VariantAssigner(Utils.hashUnit(""))
      }
    })
  }

  private def _variableValue(key: String, defaultValue: String, queueExposure: Boolean): String = {
    _indexVariables.get(key) match {
      case Some(experiments) =>
        // Find the latest non-control experiment with this variable
        var result: Option[String] = None

        experiments.reverse.foreach { exp =>
          val assignment = _assign(exp.name)
          if (queueExposure) {
            _queueExposure(assignment)
          }

          if (result.isEmpty && assignment.variant > 0) {
            exp.variants.lift(assignment.variant).flatMap(_.config).foreach { config =>
              config.asObject.flatMap(_.apply(key)).foreach { value =>
                result = Some(value.noSpaces)
              }
            }
          }
        }

        result.getOrElse(defaultValue)
      case None =>
        defaultValue
    }
  }

  private def _queueExposure(assignment: Assignment): Unit = {
    if (!assignment.exposed) {
      _assignments(assignment.name) = assignment.copy(exposed = true)

      val exposure = Exposure(
        id = assignment.id,
        name = assignment.name,
        unit = assignment.unitType,
        variant = assignment.variant,
        exposedAt = System.currentTimeMillis(),
        assigned = assignment.assigned,
        eligible = assignment.eligible,
        overridden = assignment.overridden,
        fullOn = assignment.fullOn,
        custom = assignment.custom,
        audienceMismatch = assignment.audienceMismatch
      )

      _exposures += exposure

      eventLogger.logEvent("exposure", Json.obj(
        "id" -> Json.fromInt(exposure.id),
        "name" -> Json.fromString(exposure.name),
        "unit" -> (if (exposure.unit.isEmpty) Json.Null else Json.fromString(exposure.unit)),
        "variant" -> Json.fromInt(exposure.variant),
        "exposedAt" -> Json.fromLong(exposure.exposedAt),
        "assigned" -> Json.fromBoolean(exposure.assigned),
        "eligible" -> Json.fromBoolean(exposure.eligible),
        "overridden" -> Json.fromBoolean(exposure.overridden),
        "fullOn" -> Json.fromBoolean(exposure.fullOn),
        "custom" -> Json.fromBoolean(exposure.custom),
        "audienceMismatch" -> Json.fromBoolean(exposure.audienceMismatch)
      ))
    }
  }

  private def _getHashedUnits(): Map[String, String] = {
    _units.map { case (unitType, uid) =>
      unitType -> Utils.hashUnit(uid)
    }.toMap
  }

  private def checkReady(expectNotFinalized: Boolean = false): Unit = {
    if (!_ready) {
      throw new IllegalStateException("Context is not ready")
    }
    if (expectNotFinalized) {
      checkNotFinalized()
    }
  }

  private def checkNotFinalized(): Unit = {
    if (_finalized) {
      throw new IllegalStateException("Context is finalized")
    }
    if (_finalizing) {
      throw new IllegalStateException("Context is finalizing")
    }
  }
}
