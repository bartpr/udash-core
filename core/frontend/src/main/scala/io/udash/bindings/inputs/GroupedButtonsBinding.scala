package io.udash.bindings.inputs

import io.udash._
import io.udash.properties.PropertyCreator
import org.scalajs.dom.html.{Div, Input => JSInput}
import org.scalajs.dom.{Event, Node}
import scalatags.JsDom.all._

private[inputs] class GroupedButtonsBinding[T : PropertyCreator](
  options: ReadableProperty[Seq[T]], decorator: Seq[(JSInput, T)] => Seq[Node], inputModifiers: Modifier*
)(
  inputTpe: String,
  checkedIf: T => ReadableProperty[Boolean],
  refreshSelection: Seq[T] => Unit,
  onChange: (JSInput, T) => Event => Unit
) extends InputBinding[Div] {
  private val buttons = div(
    nestedInterceptor(
      produceWithNested(options) { case (opts, nested) =>
        refreshSelection(opts)

        decorator(
          opts.zipWithIndex.map { case (opt, idx) =>
            val in = input(
              inputModifiers, tpe := inputTpe, value := idx.toString,
              nested((checked := "checked").attrIf(checkedIf(opt)))
            ).render

            in.onchange = onChange(in, opt)

            (in, opt)
          }
        )
      }
    )
  ).render

  override def render: Div = buttons
}
