package whitelabel.captal.core.survey.question

/** Hierarchy level for location questions.
  *
  * Order is strict: State -> City -> Municipality -> Urbanization
  */
enum HierarchyLevel(val order: Int):
  case State        extends HierarchyLevel(1)
  case City         extends HierarchyLevel(2)
  case Municipality extends HierarchyLevel(3)
  case Urbanization extends HierarchyLevel(4)

object HierarchyLevel:
  val ordered: List[HierarchyLevel] = List(
    HierarchyLevel.State,
    HierarchyLevel.City,
    HierarchyLevel.Municipality,
    HierarchyLevel.Urbanization)

  def nextLevel(current: HierarchyLevel): Option[HierarchyLevel] = ordered.find(
    _.order == current.order + 1)

  def previousLevel(current: HierarchyLevel): Option[HierarchyLevel] = ordered.find(
    _.order == current.order - 1)

  def canAnswer(toAnswer: HierarchyLevel, lastCompleted: Option[HierarchyLevel]): Boolean =
    lastCompleted match
      case None =>
        toAnswer == HierarchyLevel.State
      case Some(completed) =>
        toAnswer.order == completed.order + 1
