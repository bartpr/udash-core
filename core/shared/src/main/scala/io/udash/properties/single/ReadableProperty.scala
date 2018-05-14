package io.udash.properties.single

import io.udash.properties._
import io.udash.properties.seq.{ReadableSeqProperty, ReadableSeqPropertyFromSingleValue}
import io.udash.utils.Registration

import scala.collection.mutable
import scala.concurrent.{Future, Promise}

/** Base interface of every Property in Udash. */
trait ReadableProperty[A] {
  /** Unique property ID. */
  val id: PropertyId

  /** @return Current property value. */
  def get: A

  /**
    * Registers listener which will be called on value change.
    * @param initUpdate If `true`, listener will be instantly triggered with current value of property.
    */
  def listen(valueListener: A => Any, initUpdate: Boolean = false): Registration

  /** Registers listener which will be called on the next value change. This listener will be fired only once. */
  def listenOnce(valueListener: A => Any): Registration

  /** Returns listeners count. */
  private[properties] def listenersCount(): Int

  /** @return validation result as Future, which will be completed on the validation process ending. It can fire validation process if needed. */
  def isValid: Future[ValidationResult]

  /** Property containing validation result. */
  def valid: ReadableProperty[ValidationResult]

  /** Parent property. `null` if this property has no parent. */
  protected[properties] def parent: ReadableProperty[_]

  /** Fires value listeners. */
  protected[properties] def fireValueListeners(): Unit

  /** This method should be called when the value has changed. */
  protected[properties] def valueChanged(): Unit

  /** Triggers validation. */
  protected[properties] def validate(): Unit

  /**
    * Creates ReadableProperty[B] linked to `this`. Changes will be synchronized with `this`.
    *
    * @param transformer Method transforming type A of existing Property to type B of new Property.
    * @tparam B Type of new Property.
    * @return New ReadableProperty[B], which will be synchronised with original ReadableProperty[A].
    */
  def transform[B](transformer: A => B): ReadableProperty[B]

  /**
    * Creates ReadableSeqProperty[B] linked to `this`. Changes will be synchronized with `this`.
    *
    * @param transformer Method transforming type A of existing Property to type Seq[B] of new Property.
    * @tparam B Type of elements in new SeqProperty.
    * @return New ReadableSeqProperty[B], which will be synchronised with original ReadableProperty[A].
    */
  def transformToSeq[B : PropertyCreator : DefaultValue](transformer: A => Seq[B]): ReadableSeqProperty[B, ReadableProperty[B]]

  /** Streams value changes to the `target` property.
    * It is not as strong relation as `transform`, because `target` can change value independently. */
  def streamTo[B](target: Property[B], initUpdate: Boolean = true)(transformer: A => B): Registration

  /**
    * Combines two properties into a new one. Created property will be updated after any change in the origin ones.
    *
    * @param property `Property[B]` to combine with `this`.
    * @param combinedParent Parent of combined property, `null` by default.
    * @param combiner Method combining values A and B into O.
    * @tparam B Type of elements in provided property.
    * @tparam O Output property elements type.
    * @return Property[O] updated on any change in `this` or `property`.
    */
  def combine[B, O: PropertyCreator : DefaultValue](
    property: ReadableProperty[B], combinedParent: ReadableProperty[_] = null
  )(combiner: (A, B) => O): ReadableProperty[O] = {
    val pc = implicitly[PropertyCreator[O]]
    val output = pc.newProperty(combinedParent)

    def update(x: A, y: B): Unit =
      output.set(combiner(x, y))

    output.setInitValue(combiner(get, property.get))
    listen(x => update(x, property.get))
    property.listen(y => update(get, y))
    output
  }
}

private[properties] trait AbstractReadableProperty[A] extends ReadableProperty[A] {
  protected[this] val listeners: mutable.Buffer[A => Any] = CrossCollections.createArray[A => Any]
  protected[this] val oneTimeListeners: mutable.Buffer[(A => Any, () => Any)] = CrossCollections.createArray[(A => Any, () => Any)]

  protected[this] lazy val validationProperty: Property.ValidationProperty[A] = new Property.ValidationProperty[A](this)
  protected[this] val validators: mutable.Buffer[Validator[A]] = CrossCollections.createArray[Validator[A]]
  protected[this] var validationResult: Future[ValidationResult] = _

  /**
    * Registers listener which will be called on value change.
    * @param initUpdate If `true`, listener will be instantly triggered with current value of property.
    */
  def listen(valueListener: A => Any, initUpdate: Boolean = false): Registration = {
    listeners += valueListener
    if (initUpdate) valueListener(this.get)
    new MutableBufferRegistration(listeners, valueListener)
  }

  /** Registers listener which will be called on the next value change. This listener will be fired only once. */
  def listenOnce(valueListener: A => Any): Registration = {
    val reg = new MutableBufferRegistration(listeners, valueListener)
    oneTimeListeners += ((valueListener, () => reg.cancel()))
    reg
  }

  /** Returns listeners count. */
  private[properties] def listenersCount(): Int =
    listeners.length + oneTimeListeners.length

  /** @return validation result as Future, which will be completed on the validation process ending. It can fire validation process if needed. */
  def isValid: Future[ValidationResult] = {
    if (validationResult == null) validate()
    validationResult
  }

  override def transform[B](transformer: A => B): ReadableProperty[B] =
    new TransformedReadableProperty[A, B](this, transformer)

  override def transformToSeq[B : PropertyCreator : DefaultValue](transformer: A => Seq[B]): ReadableSeqProperty[B, ReadableProperty[B]] =
    new ReadableSeqPropertyFromSingleValue(this, transformer)

  override def streamTo[B](target: Property[B], initUpdate: Boolean = true)(transformer: A => B): Registration = {
    @inline def update(v: A): Unit = target.set(transformer(v))
    if (initUpdate) update(get)
    val listenerRegistration = listen(update)
    new Registration {
      override def cancel(): Unit = listenerRegistration.cancel()
      override def isActive: Boolean = listenerRegistration.isActive
      override def restart(): Unit = {
        listenerRegistration.restart()
        update(get)
      }
    }
  }

  /** Property containing validation result. */
  lazy val valid: ReadableProperty[ValidationResult] = validationProperty.property

  protected[properties] def fireValueListeners(): Unit = {
    CallbackSequencer().queue(s"${this.id.toString}:fireValueListeners", () => {
      val t = get
      val listenersCopy = CrossCollections.copyArray(listeners)
      val oneTimeListenersCopy = CrossCollections.copyArray(oneTimeListeners)
      oneTimeListeners.clear()
      listenersCopy.foreach(_.apply(t))
      oneTimeListenersCopy.foreach { case (callback, cancel) =>
        callback(t)
        cancel()
      }
    })
  }

  protected[properties] def valueChanged(): Unit = {
    validationResult = null
    fireValueListeners()
    if (parent != null) parent.valueChanged()
  }

  protected[properties] def validate(): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    if (validators.nonEmpty) {
      val p = Promise[ValidationResult]
      validationResult = p.future
      CallbackSequencer().queue(s"${this.id.toString}:fireValidation", () => {
        import Validator._
        val currentValue = this.get
        val cpy = CrossCollections.copyArray(validators)
        p.completeWith {
          Future.sequence(
            cpy.map(_ (currentValue)).toSeq
          ).foldValidationResult
        }
      })
    } else validationResult = Future.successful(Valid)
  }

}