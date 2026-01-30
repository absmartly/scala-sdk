package com.absmartly.sdk

import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}
import io.circe.Json

/**
 * Context - Main API for experiment interaction
 *
 * CRITICAL: This class implements ALL required methods (singular + plural)
 */
class Context(
  sdk: SDK,
  initialData: ContextData,
  initialUnits: Map[String, String],
  options: ContextOptions
)(implicit ec: ExecutionContext) {

  // State flags
  private var _ready: Boolean = true
  private var _failed: Boolean = false
  private var _finalized: Boolean = false
  private var _finalizing: Boolean = false

  // Data
  private var _data: ContextData = initialData
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

  // Initialize
  _init(initialData)

  // ======================
  // State Methods
  // ======================

  def isReady(): Boolean = _ready
  def isFailed(): Boolean = _failed
  def isFinalized(): Boolean = _finalized
  def isFinalizing(): Boolean = _finalizing

  def pending(): Int = _exposures.length + _goals.length

  def data(): ContextData = {
    checkReady()
    _data
  }

  def experiments(): List[String] = {
    checkReady()
    _data.experiments.map(_.name)
  }

  // ======================
  // Units Methods
  // ======================

  def setUnit(unitType: String, uid: String): Unit = {
    checkNotFinalized()
    require(uid.trim.nonEmpty, s"Unit '$unitType' UID must not be blank")

    _units.get(unitType) match {
      case Some(existing) if existing != uid =>
        throw new IllegalStateException(s"Unit '$unitType' UID already set")
      case _ =>
        _units(unitType) = uid
        // Invalidate assigner cache for this unit type
        _assigners.remove(unitType)
    }
  }

  def setUnits(units: Map[String, String]): Unit = {
    units.foreach { case (unitType, uid) => setUnit(unitType, uid) }
  }

  def getUnit(unitType: String): Option[String] = _units.get(unitType)

  def getUnits(): Map[String, String] = _units.toMap

  // ======================
  // Attributes Methods
  // ======================

  def setAttribute(name: String, value: Json): Unit = {
    checkNotFinalized()
    _attributes += Attribute(name, value, System.currentTimeMillis())
  }

  def setAttributes(attrs: Map[String, Json]): Unit = {
    attrs.foreach { case (name, value) => setAttribute(name, value) }
  }

  def getAttribute(name: String): Option[Json] = {
    _attributes.reverseIterator.find(_.name == name).map(_.value)
  }

  def getAttributes(): Map[String, Json] = {
    val result = mutable.Map[String, Json]()
    _attributes.foreach { attr =>
      result(attr.name) = attr.value
    }
    result.toMap
  }

  // ======================
  // Override Methods
  // ======================

  def setOverride(experimentName: String, variant: Int): Unit = {
    _overrides(experimentName) = variant
  }

  def setOverrides(overrides: Map[String, Int]): Unit = {
    overrides.foreach { case (name, variant) => setOverride(name, variant) }
  }

  // ======================
  // Custom Assignment Methods
  // ======================

  def setCustomAssignment(experimentName: String, variant: Int): Unit = {
    checkNotFinalized()
    _cassignments(experimentName) = variant
  }

  def setCustomAssignments(assignments: Map[String, Int]): Unit = {
    assignments.foreach { case (name, variant) => setCustomAssignment(name, variant) }
  }

  // ======================
  // Treatment Methods
  // ======================

  def treatment(experimentName: String): Int = {
    checkReady(expectNotFinalized = true)
    val assignment = _assign(experimentName)
    _queueExposure(assignment)
    assignment.variant
  }

  def peek(experimentName: String): Int = {
    checkReady(expectNotFinalized = true)
    _assign(experimentName).variant
  }

  // ======================
  // Variable Methods
  // ======================

  def variableValue(key: String, defaultValue: String): String = {
    checkReady(expectNotFinalized = true)
    _variableValue(key, defaultValue, queueExposure = true)
  }

  def peekVariableValue(key: String, defaultValue: String): String = {
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

  def customFieldValue(experimentName: String, fieldName: String): Option[String] = {
    checkReady()
    None // TODO: Implement custom fields parsing
  }

  def customFieldKeys(experimentName: String): List[String] = {
    checkReady()
    List.empty // TODO: Implement custom fields parsing
  }

  // ======================
  // Goal Tracking
  // ======================

  def track(goalName: String, properties: Option[Map[String, Json]] = None): Unit = {
    checkNotFinalized()

    // Filter to only numeric properties
    val numericProps = properties.map(Utils.filterNumericProperties)

    _goals += Goal(
      name = goalName,
      achievedAt = System.currentTimeMillis(),
      properties = if (numericProps.exists(_.nonEmpty)) numericProps else None
    )
  }

  // ======================
  // Publishing & Lifecycle
  // ======================

  def publish(): Future[Unit] = {
    checkReady(expectNotFinalized = true)

    val hashedUnits = _getHashedUnits()
    val exposures = _exposures.toList
    val goals = _goals.toList

    _exposures.clear()
    _goals.clear()

    sdk.publish(hashedUnits, hashed = true, exposures, goals)
  }

  def finalizeContext(): Future[Unit] = {
    if (_finalized || _finalizing) {
      return Future.successful(())
    }

    _finalizing = true

    publish().map { _ =>
      _finalized = true
      _finalizing = false
    }.recover { case ex =>
      _finalizing = false
      throw ex
    }
  }

  def refresh(newData: ContextData): Unit = {
    checkReady()
    checkNotFinalized()

    // Clear assignments that have changed (cache invalidation)
    val oldIndex = _index
    _init(newData)

    _assignments.foreach { case (name, assignment) =>
      // Check if experiment changed
      val oldExp = oldIndex.get(name)
      val newExp = _index.get(name)

      val shouldClear = (oldExp, newExp) match {
        case (Some(old), Some(exp)) =>
          // Check if experiment parameters changed
          old.id != exp.id ||
          old.iteration != exp.iteration ||
          old.fullOnVariant != exp.fullOnVariant ||
          old.trafficSplit != exp.trafficSplit
        case (Some(_), None) =>
          // Experiment stopped
          assignment.assigned
        case (None, Some(_)) =>
          // Experiment started
          true
        case (None, None) =>
          // No change
          false
      }

      // Don't clear if override is set
      val hasOverride = _overrides.contains(name)

      if (shouldClear && !hasOverride) {
        _assignments.remove(name)
      }
    }
  }

  // ======================
  // Private Methods
  // ======================

  private def _init(data: ContextData): Unit = {
    _data = data

    // Build experiment index
    _index = data.experiments.map(exp => exp.name -> exp).toMap

    // Build variable index
    val varIndex = mutable.Map[String, mutable.ListBuffer[ExperimentData]]()
    data.experiments.foreach { exp =>
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

    // Check existing assignment
    _assignments.get(experimentName) match {
      case Some(assignment) if isAssignmentValid(assignment, experiment, hasOverride, hasCustom) =>
        return assignment
      case _ => // Need new assignment
    }

    // Create new assignment
    val assignment = experiment match {
      case Some(exp) if hasOverride =>
        // Override variant
        createAssignment(exp, _overrides(experimentName), overridden = true)

      case Some(exp) if hasCustom =>
        // Custom assignment
        createAssignment(exp, _cassignments(experimentName), custom = true)

      case Some(exp) =>
        // Normal assignment
        val variant = assignVariant(exp)
        createAssignment(exp, variant)

      case None =>
        // Experiment not running
        Assignment(
          id = 0,
          name = experimentName,
          unitType = "",
          iteration = 0,
          trafficSplit = List.empty,
          fullOnVariant = 0,
          variant = 0,
          assigned = false,
          exposed = false,
          eligible = true,
          overridden = hasOverride,
          audienceMismatch = false,
          fullOn = false,
          custom = hasCustom
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
          assignment.id == exp.id &&
          assignment.iteration == exp.iteration &&
          assignment.fullOnVariant == exp.fullOnVariant &&
          assignment.trafficSplit == exp.trafficSplit &&
          (!hasCustom || _cassignments(assignment.name) == assignment.variant)
        case None =>
          !assignment.assigned
      }
    }
  }

  private def createAssignment(
    exp: ExperimentData,
    variant: Int,
    overridden: Boolean = false,
    custom: Boolean = false
  ): Assignment = {
    // Check audience match
    val audienceMismatch = exp.audience.exists { aud =>
      val matcher = new AudienceMatcher(getAttributes())
      matcher.evaluate(Some(aud)) match {
        case Some(result) => !result
        case None => false
      }
    }

    // Check eligibility (has required unit type)
    val eligible = exp.unitType.isEmpty || _units.contains(exp.unitType)

    Assignment(
      id = exp.id,
      name = exp.name,
      unitType = exp.unitType,
      iteration = exp.iteration,
      trafficSplit = exp.trafficSplit,
      fullOnVariant = exp.fullOnVariant,
      variant = variant,
      assigned = true,
      exposed = false,
      eligible = eligible,
      overridden = overridden,
      audienceMismatch = audienceMismatch,
      fullOn = variant == exp.fullOnVariant,
      custom = custom
    )
  }

  private def assignVariant(exp: ExperimentData): Int = {
    // Get or create assigner for unit type
    val assigner = _assigners.getOrElseUpdate(exp.unitType, {
      _units.get(exp.unitType) match {
        case Some(uid) =>
          val hashedUid = Utils.hashUnit(uid)
          new VariantAssigner(hashedUid)
        case None =>
          // No unit for this type, use empty string
          new VariantAssigner(Utils.hashUnit(""))
      }
    })

    assigner.assign(exp.split, exp.seedHi, exp.seedLo)
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
    if (!assignment.exposed && assignment.assigned && assignment.eligible) {
      _assignments(assignment.name) = assignment.copy(exposed = true)

      _exposures += Exposure(
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
