# API Conventions

kolt's Kotlin API is generated from Jolt's C++ headers, by rule. This page is those rules: knowing them, you can read
Jolt's documentation or a C++ sample and write the Kotlin without looking anything up.

## Start and stop

`Jolt.init()` first, `Jolt.shutdown()` last, once everything created in between is closed. `init` does what a C++
program does by hand: registers the default allocator, creates `Factory::sInstance` and calls `RegisterTypes()`.

## Names

| C++ | Kotlin |
| :--- | :--- |
| `JPH::BodyInterface` in `Jolt/Physics/Body/` | `io.github.erkko68.kolt.physics.body.BodyInterface` |
| `GetGravity()` / `SetGravity(v)` | the property `gravity` |
| a public field `mRestitution` | the property `restitution` |
| a method `CreateAndAddBody(…)` | `createAndAddBody(…)` |
| a static `sRestoreFromBinaryState(…)` | `restoreFromBinaryState(…)` on the companion |
| a parameter `inRadius`, `outPosition`, `ioSettings` | `radius`, `position`, `settings` |
| a nested class `Shape::Stats` | `Shape.Stats` |
| an enum `EMotionType::Dynamic` | `EMotionType.Dynamic` |
| a free function `EstimateCollisionResponse(…)` | the top-level `estimateCollisionResponse(…)` |

A package is the header's directory, lower-cased. A getter that takes arguments stays a function
(`bodies.getPosition(id)`). An enum whose values combine as flags does so in Kotlin too:
`EAllowedDOFs.TranslationX or EAllowedDOFs.TranslationY`.

KDoc is the header's comment, so the IDE shows Jolt's documentation.

## Values and objects

Two kinds of type cross between Kotlin and Jolt.

**Values** are copied. They are ordinary Kotlin and never need closing:

- numbers, booleans, strings and enums;
- the math types in `math/` (`Vec3`, `Vec4`, `Quat`, `Mat44`, `DVec3`, `DMat44`, `UVec4`, `Float3`, `Float4`), hand-written
  data classes with the usual operators;
- IDs and layers (`BodyID`, `SubShapeID`, `BroadPhaseLayer`), which are their number.

**Objects** live in Jolt's memory. The Kotlin class is a wrapper holding a pointer, and is `AutoCloseable`.

## Ownership

The garbage collector does not free Jolt's objects. Whether `close()` does depends on where the wrapper came from:

| The wrapper came from | It is | `close()` |
| :--- | :--- | :--- |
| a constructor: `SphereShape(0.5f, null)` | owned | destroys the object, or drops its reference |
| a function returning a class by value: `shape.stats` | an owned copy | destroys the copy |
| a function handing over a reference: `settings.create()` | owned | drops the reference |
| a property or function reading from an object Jolt keeps: `system.bodyInterface`, `bodies.getShape(id)` | borrowed | does nothing |

So the rule of thumb is: **close what you created**. `close()` is safe to call twice, and safe on a borrowed wrapper,
so `use { }` works on anything:

```kotlin
val triangles = shape.stats.use { it.numTriangles }
```

Reference-counted classes (shapes, constraints, materials: what C++ holds in a `Ref<T>`) stay alive while anything
refers to them. A body holds a reference to its shape, so the shape's wrapper can be closed as soon as the body exists.

A borrowed wrapper is only valid while the object it was read from is: don't keep `system.bodyInterface` past
`system.close()`.

## Subclassing: listeners, filters, collectors

A C++ class with virtual functions is `open` in Kotlin, and Jolt calls what you override:

```kotlin
val notTheBall = object : BodyFilter() {
    override fun shouldCollide(bodyID: BodyID) = bodyID != ball
}

val hits = ArrayList<BodyID>()
val collector = object : CastRayCollector() {
    override fun addHit(result: RayCastResult) { hits += result.bodyID }
}
```

- What you don't override keeps Jolt's implementation, with no call into Kotlin.
- Jolt calls overrides **from its job threads**, during `system.update`. Shared state needs to be thread-safe.
- The arguments of a callback are borrowed: valid for the duration of the call.
- The object is yours: keep a reference for as long as Jolt uses it, and `close()` it after.

Classes without virtual functions are final.

## Functions as arguments

A `std::function` parameter is a Kotlin function type, or `null` for none:

```kotlin
system.setSimCollideBodyVsBody { body1, body2, transform1, transform2, settings, collector, filter ->
    PhysicsSystem.defaultSimCollideBodyVsBody(body1, body2, transform1, transform2, settings, collector, filter)
}
```

Where the C++ function hands something back through a reference argument, the Kotlin function returns it.

## Out-parameters

What C++ writes through a reference parameter, Kotlin returns:

```kotlin
val (position, rotation) = bodies.getPositionAndRotation(id)   // void GetPositionAndRotation(id, RVec3 &out, Quat &out)
```

- One out-parameter is the result; several are a `Pair` or `Triple`.
- If the function has a result of its own, that comes first.
- An `io` parameter is both passed in and returned.
- The same holds for an override: `onAdjustBodyVelocity(…)` on a `CharacterContactListener` returns the
  `Pair<Vec3, Vec3>` of velocities C++ takes back through its two `io` references.

## Arrays and pointers

| C++ | Kotlin |
| :--- | :--- |
| `const T *` and a count, `Array<T>`, `std::vector<T>` | `List<T>`, or a primitive array (`FloatArray`, `IntArray`) for numbers |
| an array Jolt fills | a `MutableList<T>`, changed in place and sized as large as Jolt may fill |
| an `Array<T>` C++ fills through a reference | the function's result, a `List<T>` |
| `const char *`, `JPH::String`, `std::string_view` | `String` |
| `void *` | `NativePointer` |
| a nullable pointer | a nullable type |

Where Jolt takes the count as its own parameter, Kotlin does too: `bodies.deactivateBodies(ids, 2)`.

## Errors

A C++ `Result<T>` (a value or an error) returns the value, or throws `NativeException` carrying Jolt's message:

```kotlin
val shape = try { settings.create() } catch (e: NativeException) { error("bad hull: ${e.message}") }
```

Jolt's assertions and crashes are not exceptions: an invalid call that would crash a C++ program crashes here too.

## Casts

What Jolt returns as a base class is wrapped as the class it actually is, so Kotlin's `is` and `as` work:

```kotlin
val radius = (bodies.getShape(id) as SphereShape).radius
val wheels = vehicle.controller as WheeledVehicleController
```

This relies on C++ RTTI, which kolt builds Jolt with. Two cases RTTI can't cover get a function instead:

- **A class without virtual functions** can't be asked what it is. Where Jolt documents a downcast, a function does it,
  unchecked: `body.motionProperties!!.asSoftBodyMotionProperties()`.
- **A second C++ base class**, which Kotlin's single inheritance can't express:
  `vehicle.asPhysicsStepListener()`.

## What isn't there

kolt binds the headers listed in [`c/api-headers.txt`](../../c/api-headers.txt). Of those,
[`c/api-coverage.txt`](../../c/api-coverage.txt) accounts for every function:

- `wrapped`: in the Kotlin API. Almost all of it.
- `todo`: not bound yet, with the reason. Today that is the stream save/restore functions taking ID maps
  (`SaveWithChildren`), plain function pointers (`SetCombineFriction`) and `std::pair` results.
- `skipped`: left out on purpose, by an exclusion in `api-headers.txt`: Jolt's own machinery, not API.

Jolt is built in single precision, without the debug renderer or the profiler.
