package whitelabel.captal.core.survey.question

/** Hierarchy level for location questions.
  *
  * Order is strict: Estado -> Ciudad -> Municipio -> Urbanizacion
  */
enum HierarchyLevel(val order: Int):
  case Estado       extends HierarchyLevel(1)
  case Ciudad       extends HierarchyLevel(2)
  case Municipio    extends HierarchyLevel(3)
  case Urbanizacion extends HierarchyLevel(4)

object HierarchyLevel:
  val ordered: List[HierarchyLevel] = List(
    HierarchyLevel.Estado,
    HierarchyLevel.Ciudad,
    HierarchyLevel.Municipio,
    HierarchyLevel.Urbanizacion)

  def nextLevel(current: HierarchyLevel): Option[HierarchyLevel] = ordered.find(
    _.order == current.order + 1)

  def previousLevel(current: HierarchyLevel): Option[HierarchyLevel] = ordered.find(
    _.order == current.order - 1)

  def canAnswer(toAnswer: HierarchyLevel, lastCompleted: Option[HierarchyLevel]): Boolean =
    lastCompleted match
      case None =>
        toAnswer == HierarchyLevel.Estado
      case Some(completed) =>
        toAnswer.order == completed.order + 1
