//> using scala 3.3.3
//> using jvm 21

import scala.annotation.{experimental, implicitNotFound}
import scala.compiletime.{error, erasedValue, summonInline}

object di:
  // Provides a dependency of type A given dependencies encoded by type R
  sealed case class Provider[-R, +A] private[di] (constructor: R => A):
    // Memoization is necessary so that constructor is run only once per
    // dependency
    private var memo: A | Null = null
    private[di] def apply(value: R): A =
      if memo == null then
        val result = constructor(value)
        memo = result
        result
      else memo.asInstanceOf[A]

  // Create a provider from a function. Uses transparent inline + compile-time
  // utilities to resolve types correctly.
  transparent inline def provideConstructor[F](inline construct: F): Provider[Nothing, Any] =
    inline construct match
      case f0: Function0[b] => Provider[Any, b]((_: Any) => f0())
      case f1: Function1[a, b] => Provider(f1)
      case f2: Function2[a1, a2, b] => Provider(f2.tupled)
      case f3: Function3[a1, a2, a3, b] => Provider(f3.tupled)
      case f4: Function4[a1, a2, a3, a4, b] => Provider(f4.tupled)
      case f5: Function5[a1, a2, a3, a4, a5, b] => Provider(f5.tupled)
      case f6: Function6[a1, a2, a3, a4, a5, a6, b] => Provider(f6.tupled)
      case f7: Function7[a1, a2, a3, a4, a5, a6, a7, b] => Provider(f7.tupled)
      case f8: Function8[a1, a2, a3, a4, a5, a6, a7, a8, b] => Provider(f8.tupled)
      case f9: Function9[a1, a2, a3, a4, a5, a6, a7, a8, a9, b] => Provider(f9.tupled)
      case f10: Function10[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, b] => Provider(f10.tupled)
      case f11: Function11[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, b] => Provider(f11.tupled)
      case f12: Function12[a1, a2, a3, a4, a5, a6, a7, a8, a9, a10, a11, a12, b] => Provider(f12.tupled)
      // Could go up to 22, or find more generic approach
  end provideConstructor

  // A provided dependency of type A
  opaque type Provided[A] = A

  // Provide a simple value
  def provide[A](value: A): Provided[A] = value

  // Provide a value lazily
  def provideSuspended[A](value: => A): Provider[Any, A] =
    Provider((_: Any) => value)

  // Retrieve a dependency of type A by resolving an implicit Provided[A] instance
  def provided[A](
    using
    @implicitNotFound("Unable to provide a value of type ${A}. Make sure any dependencies are provided.")
    pr: Provided[A],
  ): A = pr

  trait LowPriorityProvided:
    given providedFromProvider[R, A](using lyr: Provider[R, A], apr: Provided[R]): Provided[A] =
      lyr(apr)

  object Provided extends LowPriorityProvided:
    given providedNonEmptyTuple[A, T <: Tuple](using apr: Provided[A], npr: Provided[T]): Provided[A *: T] =
      apr *: npr

    given providedEmptyTuple: Provided[EmptyTuple] = EmptyTuple

    given providedFromTrivialProvider[A](using pr: Provider[Any, A]): Provided[A] =
      pr(())
end di

  //////////////////////////////////////////
  //  SERVICE DEFINITIONS WITH PROVIDERS  //
  //////////////////////////////////////////


final case class Service1(int: Int, bool: Boolean)

object Service1:
  // Provider instance that will be used by default because it's at top level in the
  // companion object. Can use Provided here too.
  given default: di.Provider[(Int, Boolean), Service1] =
    di.provideConstructor(Service1.apply)

final case class Service2(str: String)

object Service2:
  given default: di.Provider[Service1, Service2] =
    di.provideConstructor((service1: Service1) => Service2(s"${service1.int} - ${service1.bool}"))

  object providers:
    // Given Provided instance that can be imported explicitly to override
    // default instance. Can use Provider here too.
    given test: di.Provided[Service2] =
      di.provide(Service2(s"TEST (no dependencies!)"))

final case class Service3(service1: Service1, service2: Service2)

object Service3:
  given default: di.Provider[(Service1, Service2), Service3] =
    di.provideConstructor(Service3.apply)


  //////////////////////////////////////
  //  CONSTRUCT AND USE DEPENDENCIES  //
  //////////////////////////////////////

object Main:
  // Three ways to provide a zero-dependency type (needed by Service1):

  // 1: Provide directly with a value (eagerly evaluated)
  given di.Provided[String] = di.provide("hi")

  // 2: Provide with a suspended value (lazily evaluated)
  given di.Provider[Any, Int] = di.provideSuspended {
    println("Performing side-effect...") // This should only run once
    23
  }

  // 3: Provide with a Function0 (lazily evaluated)
  given di.Provider[Any, Boolean] = di.provideConstructor(() => false)

  // Uncomment this import to inject a test version of Service2
  // import Service2.providers.test

  def main(args: Array[String]): Unit =

    // Resolve Service3 dependency from Provided/Provider instances
    val service3 = di.provided[Service3]

    println(service3)
