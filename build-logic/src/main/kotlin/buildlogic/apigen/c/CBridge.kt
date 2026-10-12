package buildlogic.apigen.c

import buildlogic.apigen.ApiGenConfig

/**
 * How a C++ value crosses into C: its C spelling, and the expression converting it to the other side. [out]: a
 * result C returns through a trailing `out` pointer ([CAbi]), which [store] writes given the value and the pointer.
 */
class CBridge(
    val c: String,
    val convert: (String) -> String,
    val out: Boolean = false,
    val store: (String, String) -> String = { v, out -> "*$out = ${convert(v)};" },
    /** A parameter assigned to a field: the statement given the field and the C argument. */
    val assign: (String, String) -> String = { field, v -> "$field = ${convert(v)};" },
    /**
     * A parameter's C parameters after the first, as (type, name suffix): one C++ argument C passes in pieces. A
     * result's trailing parameters, as (type, name).
     */
    val extra: List<Pair<String, String>> = emptyList(),
    /** A typedef its C spelling needs, by C name: a callback's function pointer type. */
    val typedef: Pair<String, String>? = null,
    /** What the value is, for a stage wrapping the C API in typed code; null when [c] alone doesn't say how to. */
    val value: CValue? = null,
)

/** A bridged value a wrapper can type from its C spelling: [c] is the C type, by which the wrapper names its own. */
sealed interface CValue {
    val c: String

    /** A number or bool C passes as it is; a result [CBridge.out] returns through a pointer. */
    data class Builtin(override val c: String) : CValue
    data class Enum(override val c: String) : CValue
    /** An [ApiGenConfig.scalars] type, crossing as the integer [scalar]; [c] is the name C would give the type. */
    data class Scalar(override val c: String, val scalar: String) : CValue
    /** A NUL-terminated UTF-8 string: `const char*`. */
    data object Text : CValue {
        override val c get() = "const char*"
    }
    /**
     * A result that's [value], or NULL with the error's message copied into the trailing `outError`, up to
     * `outErrorCapacity`.
     */
    data class Fallible(val value: CValue) : CValue {
        override val c get() = value.c
    }
    /**
     * An array of [element]s and its count. A parameter's `Count` follows it. A result returns how many there are, and
     * fills `out` up to `outCapacity`: with the elements, or, [created], by copying each into the handle at its index.
     */
    data class Sequence(val element: CValue, val created: Boolean = false) : CValue {
        override val c get() = element.c
    }
    /** An [ApiGenConfig.mirrors] type by value: C passes a pointer to its [count] [element]s, which a result fills. */
    data class Mirror(override val c: String, val element: String, val count: Int) : CValue
    /**
     * A record's handle; [nullable]: C++ takes or returns a pointer, not a reference. [owned]: a result hands the caller
     * a reference to release. A result [CBridge.out] is copied into one the caller created.
     */
    data class Handle(override val c: String, val nullable: Boolean, val owned: Boolean = false) : CValue
    /** A pointer to one [value], which C++ takes by non-const reference: the callee reads what's there and writes its own. */
    data class InOut(val value: CValue) : CValue {
        override val c get() = value.c
    }
    /**
     * A pointer to [element]s, as many as the callee knows or another argument says. [const]: the callee only reads
     * them; a pointer to handles doesn't say, so there the parameter's name does ([ApiGenConfig.outParameter]).
     */
    data class Pointer(val element: CValue, val const: Boolean) : CValue {
        override val c get() = element.c
    }
    /**
     * A function written outside C++, which C passes as an `Upcall` and its user data: C++ calls method 0 with [args]
     * in its slots, as a virtual function's are, and index -1 once it drops the function.
     */
    data class Function(override val c: String, val args: List<CValue>, val returns: CValue?) : CValue
    /** A `void*`, which C passes as it is. */
    data object Opaque : CValue {
        override val c get() = "void*"
    }
}

/** Why a declaration stays hand-written; the generator leaves a comment saying so instead of code. */
class Unsupported(reason: String) : Exception(reason)
