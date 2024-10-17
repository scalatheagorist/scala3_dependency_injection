# ZIO-like dependency injection using implicit resolution

Daniel Ciocîrlan recently published a [video](https://www.youtube.com/watch?v=gLJOagwtQDw) showcasing a
dependency-injection (DI) approach developed by [Martin Odersky](https://github.com/scala/scala3/blob/main/tests/run/Providers.scala) that uses Scala's implicit resolution to wire dependencies automatically.
(See also his [reddit post](https://www.reddit.com/r/scala/comments/1eksdo2/automatic_dependency_injection_in_pure_scala/).)

The basic pattern for defining services in Odersky's approach is as follows:

```scala3
class Service(using Provider[(Dep1, Dep2, Dep3)])
  def someMethod():
    provided[Dep1].doSomething()
    provided[Dep2].doSomethingElse()
```

We can now construct a `Service` instance simply by calling `Service()` as long as `implicit` `Provider`s
for `Dep1`, `Dep2`, and `Dep3` are in scope. If another service needs `Service`, we could easily define a 
`Provider[Service]`. We can even provide it globally by adding the following to the companion object:

```scala3
object Service:
  given default(using Provider[(Dep1, Dep2, Dep3)]) = provide(Provider())
```

As [u/Krever](https://www.reddit.com/user/Krever/) indicated 
[in a comment](https://www.reddit.com/r/scala/comments/1eksdo2/comment/lgqliv5/?utm_source=share&utm_medium=web3x&utm_name=web3xcss&utm_term=1&utm_content=share_button),
however, this approach makes it awkward to separate the DI framework from service definitions. Here is 
what it would look like to separate DI from the service itself:

```scala3
class Service(dep1: Dep1, dep2: Dep2, dep3: Dep3)])
  def someMethod():
    dep1.doSomething()
    dep2.doSomethingElse()
    
object Service:
  given default(using Provider[(Dep1, Dep2, Dep3)]): Provider[Service] =
    provide(Provider(provided[Dep1], provided[Dep2), provided[Dep3])
    
  object providers:
    given test(using Provider[Dep1]): Provider[Service] =
      provide(Provider(provided[Dep1], Dep2(/* custom conf */), Dep3(/* custom conf */))
```

Providing generic instances can get pretty verbose, especially when we start providing alternate 
instances for different contexts.

## ZIO layers

The approach taken by ZIO is to separate constructors used for DI from the services they construct into a new
type called `ZLayer`. DI works in ZIO by using a macro to automatically compose these layers, or constructors, 
in such a way as to provided the desired type while satisfying all dependencies. One of the best thing about layers is 
that it easy to provide several different layers for the same service and choose the one you want for each when 
constructing the application.

The basic pattern for defining services in ZIO looks like this:

```scala3
final case class Service(dep1: Dep1, dep2: Dep2, dep3: Dep3):
  def someMethod():
    dep1.doSomething()
    dep2.doSomethingElse()

object Service:
  val live = ZLayer.fromFunction(Service.apply)
  val test = ZLayer.fromFunction { (dep1: Dep1) =>
    Service(dep1, Dep2(/* custom conf */), Dep3(/* custom conf */))
  }
```

In the above example, the `live` layer will have a dependency on `Dep1`, `Dep2`, and `Dep3`, whereas `test`
will only have a dependency on `Dep1` (`Dep2` and `Dep3` are constructed manually). The dependencies for 
each layer are tracked in their types, which in the above case are inferred (another advantage).

## ZLayers using implicit resolution?

The code included in this gist demonstrates a way to combine the ZIO approach with Odersky's implicit `Provider`
approach. Rather than simply providing a value, a `Provider` now represents a constructor, which tracks the 
dependencies (i.e., the constructor parameters) at the type-level. `Provider[R, A]` represents a constructor of
`A` that needs `R`, where `R` is either a dependency or a tuple of dependencies. We now also include a second type `
Provided` which represents a value that has been provided -- either directly or via evaluation one or more `Provider`s.
The mechanics of this DI framework thus lie in the implicit resolution of a `Provided` instance from given `Provider` 
and/or `Provided` instances.

This approach now allows us to define our services and DI instances in a similar way to ZIO with less boilerplate:

```scala3
import di.*

final case class Service(dep1: Dep1, dep2: Dep2, dep3: Dep3):
  def someMethod():
    dep1.doSomething()
    dep2.doSomethingElse()

object Service:
  given default: Provider[(Dep1, Dep2, Dep3), Service] =
    provideConstructor(Service.apply)
  
  object providers:
    given test: Provider[Dep1, Service] = provideConstructor { (dep1: Dep1) =>
      Service(dep1, Dep2(/* custom conf */), Dep3(/* custom conf */))
    }
```

There are a couple important differences from the ZIO pattern, all of which follow from the fact that
providers, unlike layers, are not simply values but `given` instances. One benefit of this is that we are 
able to provide a single default given at the top level of our application which will be used automatically 
when we call `provided[Service]` without needing to import anything. We can override the default by
importing `Service.provided.test` in the scope where we call `provided[Service]`.

The second difference is that since we are defining our `Provider`s as `given` instances we must explicitly 
annotate their types. While not the end of the world, this is an ergonomic sacrifice. ZIO makes good use of 
Scala's type inference to allow tracking complicated dependencies without having to fuss with boilerplate. In 
the ZIO example above, for instance, you can change the constructor parameters of `Service` and the changes 
to `live`'s dependency type will be inferred. In our version, changing the types of `Service`'s parameters 
would require rewriting the type annotation on `default`.

## Other missing ZLayer features

Since ZIO provides what is in my view the best DI framework available, it's worth pointing out a few things it 
offers that this one doesn't.

1. *Error messages*. ZIO's `provide` macro will display very nicely formatted error messages explaining which 
   layers are missing which dependencies. It will also tell you which layers provide ambiguous (conflicting)
   dependencies. When using implicit resolution, we depend on built-in compiler error messages with only 
   minimal customization possible (e.g., `implicitNotFound` and `ambiguousImplicit` annotations).
2. *Effects*. `ZLayer` allows you to include effects in your dependencies constructors, which is important for 
   initializing and scoping resources needed by services. `Provider`/`Provided` certainly supports effectful 
   construction, but not using any powerful context like `Future`, `ZIO`, or `IO`.
3. *Composability*. `ZLayer`s can be composed manually using combinators like `>>` and `++`, allowing the user 
   build layers from one another without having to redefine a constructor. For instance, to make a test layer 
   for `Service` from the above example, you might want to define it as:
   ```scala3
   val test = (Dep2.test ++ Dep3.test) >> Service.live
   ```
   This will use the test layers from `Dep2` and `Dep3` to satisfy those dependencies while still requiring a
   layer for `Dep1`. It would be possible, though complicated, to implement this for `Provider`, but doing so would 
   requiring explicitly annotating the resulting provider instance with the dependencies of `Dep1` and `Dep2` 
   which would offset a lot of the convenience.

## Conclusion

By using `Provider`s modeled after `ZLayer`, it is easier to separate the DI mechanism from our service 
definitions and provide alternate versions of the same dependency for different contexts. Nevertheless, there 
is still a fair amount missing that we get in mature DI frameworks like ZIO's.


