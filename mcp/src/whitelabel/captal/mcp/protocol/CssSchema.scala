package whitelabel.captal.mcp.protocol

import scala.io.Source
import scala.util.Using

/** The custom properties a location's stylesheet can override.
  *
  * Names, defaults and grouping are read from the portal's own `styles.css` at startup rather than
  * listed here. That is the same guarantee `get_i18n_schema` has and for the same reason: a list
  * kept by hand drifts from the thing it describes, and this drift is silent — an operator themes a
  * variable nothing reads any more, sees no change, and has nothing to blame.
  *
  * What is written by hand is the prose, and only the prose. A variable whose purpose is missing
  * still appears, with an empty one; that is the signal that the stylesheet grew something nobody
  * has explained yet.
  */
object CssSchema:
  private val Resource = "/default-styles.css"

  final case class Variable(name: String, default: String, purpose: String)
  final case class Group(group: String, variables: List[Variable])

  /** What each variable is for.
    *
    * Duplicated from `skill://style-reference` on purpose and for now: the skill explains how to
    * theme a portal, this answers what there is to theme. Deriving the second from the first is
    * worth doing later; writing it twice is worth it today, because the skill is prose a model
    * reads whole and this is a list it can act on.
    *
    * Terse where the name already says it — a spacing step is a spacing step — and explicit where
    * there is something to get wrong.
    */
  private[mcp] val Purposes: Map[String, String] = Map(
    "--color-primary" ->
      "The brand colour. Buttons, links and focus rings derive from it; set this one first.",
    "--color-primary-dark"  -> "Pressed and hovered states of anything primary.",
    "--color-primary-light" -> "Tints: selected option backgrounds and subtle fills.",
    "--color-success"     -> "Confirmation, and the button that advances once an answer is valid.",
    "--color-error"       -> "Validation failures and the error screen.",
    "--color-warning"     -> "Advisory states. Rare in the portal today.",
    "--bg-gradient-start" ->
      "First stop of the page background. The portal's whole backdrop is this gradient, so these two carry most of the perceived identity.",
    "--bg-gradient-end"   -> "Second stop of the page background.",
    "--bg-gradient-angle" ->
      "Direction of the background gradient. `135deg` runs top-left to bottom-right.",
    "--surface-primary"   -> "Cards and panels that sit over the gradient.",
    "--surface-secondary" -> "Recessed areas inside a card: inputs, list rows.",
    "--surface-error"     -> "Background of the error panel.",
    "--text-primary"      -> "Body text on a light surface.",
    "--text-secondary"    -> "Supporting text: hints, captions, inactive steps.",
    "--text-inverse"      ->
      "Text on the gradient and on primary buttons. Keep it legible against both.",
    "--text-link"   -> "Links. Defaults to the primary colour.",
    "--font-family" ->
      "The whole portal's typeface. A web font has to be loaded by the stylesheet itself; nothing else fetches one.",
    "--line-height"        -> "Body line height, unitless.",
    "--border-color"       -> "Resting borders on inputs and cards.",
    "--border-color-hover" -> "Borders under the pointer.",
    "--radius-full"        -> "Pills and circles. Not a size — leave it very large.",
    "--shadow-sm"          -> "Resting elevation of a card.",
    "--shadow-md"          ->
      "A primary button at rest. Tinted with the primary colour, so it needs updating when that changes.",
    "--shadow-lg"           -> "Modals and the video player.",
    "--shadow-button-hover" ->
      "A primary button under the pointer. Also tinted with the primary colour.",
    "--shadow-success"       -> "The success button at rest, tinted with the success colour.",
    "--shadow-success-hover" -> "The success button under the pointer.",
    "--shadow-focus"         ->
      "The focus ring. Keep it visible: it is the only keyboard affordance the portal has.",
    "--glass-bg" -> "Translucent panels laid directly over the gradient, as on the welcome screen.",
    "--glass-text"   -> "Text inside those panels.",
    "--glass-border" -> "Their hairline border.",
    "--glass-focus"  ->
      "The focus ring inside them, which has to read against the gradient rather than a surface.",
    "--error-bg-transparent" ->
      "Error state over the gradient, where a solid surface would break the glass effect.",
    "--error-text-light"       -> "Error text over the gradient.",
    "--success-bg-transparent" -> "Success state over the gradient.",
    "--transition-fast"        -> "Hovers and colour changes.",
    "--transition-base"        -> "The default for everything else.",
    "--transition-slow"        -> "Entrances and layout shifts.",
    "--animate-fade-in-1"      ->
      "The staggered entrance of a screen's content, step 1. The five together produce the cascade; change the delays to retime it, the keyframe name to replace it.",
    "--animate-fade-in-2" -> "Staggered entrance, step 2.",
    "--animate-fade-in-3" -> "Staggered entrance, step 3.",
    "--animate-fade-in-4" -> "Staggered entrance, step 4.",
    "--animate-fade-in-5" -> "Staggered entrance, step 5.",
    "--icon-size-lg"      ->
      "The brand icon on a screen's header, and in the loader. The loader's initial position is computed from it, so changing it moves the handoff.",
    "--icon-size-md" -> "The brand icon where it appears smaller.",
    "--control-size" ->
      "Radio and checkbox size. Below about 18px they become hard to hit on a phone.",
    // `--loader-ring-gap` and `--loader-ring-width` were described here and are gone from the
    // stylesheet: they belonged to a spinning ring that was dropped, and nothing read them. They
    // are the case this map cannot catch on its own — the names and defaults are derived from the
    // declarations, so a variable that is declared and unread still appears, and only the prose
    // can say so. Which is why these two read as "still named ring, no longer a ring".
    "--loader-ring-out" ->
      "First half of --loader-handoff: how long the waiting state takes to leave. Named for a ring that no longer exists; the waiting animation is now `breathe` on the icon itself.",
    "--loader-settle" ->
      "Second half of --loader-handoff: the pause between the wait ending and the icon rising with the content. It is what makes the end of the wait legible; at zero the two movements compete.",
    "--loader-handoff" ->
      "When the icon starts rising. Derived from the two above — override those, not this.",
    "--color-success-disabled" -> "The advance button before the answer is valid.",
    "--color-disabled"         -> "Disabled controls generally.",
    "--content-max-width"      ->
      "How wide a screen's content gets on a large display. The portal is designed for a phone held in one hand.",
    "--content-padding" -> "Breathing room inside that column."
    // No entry for the brand icon: it is an `<img>` whose file `set_location_icon` replaces, not a
    // variable. A `--brand-icon-url` was documented here and declared nowhere, which is exactly
    // what the orphan test exists to catch.
  )

  /** Reads the `:root` block: its banner comments are the groups, its declarations the variables.
    *
    * Deliberately not a CSS parser. The one block this reads is written by this repo in a shape it
    * controls, and a parser would be a dependency carried to read seventy-five lines.
    */
  private[mcp] def parse(stylesheet: String): List[Group] =
    val lines = stylesheet.linesIterator.toList
    val root = lines
      .dropWhile(!_.trim.startsWith(":root"))
      .drop(1)
      .takeWhile(!_.trim.startsWith("}"))

    val Declaration = """^\s*(--[a-z0-9-]+)\s*:\s*(.+?);\s*$""".r
    // A banner is three comment lines, the middle one naming the section.
    val Banner = """^[\s/*═]*([A-Z][A-Z0-9 ]*[A-Z0-9])[\s*/═]*$""".r

    val (groups, lastName, lastVars) =
      root.foldLeft((List.empty[Group], "General", List.empty[Variable])):
        case ((done, name, vars), line) =>
          line match
            case Declaration(variable, value) =>
              (done, name, vars :+ Variable(variable, value.trim, Purposes.getOrElse(variable, "")))
            case Banner(heading) =>
              val closed =
                if vars.isEmpty then
                  done
                else
                  done :+ Group(name, vars)
              (closed, heading.trim, Nil)
            case _ =>
              (done, name, vars)

    if lastVars.isEmpty then
      groups
    else
      groups :+ Group(lastName, lastVars)
  end parse

  lazy val groups: List[Group] = parse(
    Option(getClass.getResourceAsStream(Resource))
      .flatMap(stream => Using(Source.fromInputStream(stream, "UTF-8"))(_.mkString).toOption)
      .getOrElse(
        throw new IllegalStateException(
          s"$Resource is missing from the jar. It is copied from client/assets/styles.css in `mcp.resources`.")))

  lazy val all: List[Variable] = groups.flatMap(_.variables)
end CssSchema
