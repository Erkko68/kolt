package buildlogic.apigen.c

import buildlogic.apigen.ApiHeaders
import buildlogic.apigen.Layout
import buildlogic.apigen.cpp.CppApi
import buildlogic.apigen.cpp.CppEnum
import buildlogic.apigen.cpp.CppRecord

/**
 * What the c stage made of the C++ [api]: the generated [files], by path under the C dir, and what the stages
 * wrapping the C API go by. [bindings]: each C function the C++ API calls for, bound or not. [handles], [enums]: the
 * records C holds by a handle, and the enums, the API reaches.
 */
internal class CApi(
    val api: CppApi, val names: CNames, val apiHeaders: ApiHeaders, val layout: Layout,
    val files: Map<String, String>, val bindings: List<Binding>, val handles: List<CppRecord>, val enums: List<CppEnum>,
)

/** C function [c] for the C++ [cpp]; [gap] is null once bound (generated or in manual/). [bound]: what a generated one does. */
class Binding(val c: String, val cpp: String, val gap: Gap?, val bound: Bound? = null)

/** Why a C function isn't bound: it's left for hand-written code, or `api-headers.txt` leaves it out. */
class Gap(val kind: Kind, val reason: String) {
    enum class Kind { TODO, SKIPPED }
}

/**
 * What a generated C function does of [owner] (a record, or a namespace for its functions), for the stages wrapping
 * the C API. [name]: the member's, as C names it without an overload's suffix; an upcast's or a downcast's base, as C
 * names the type.
 */
class Bound(
    val role: Role, val owner: String, val name: String, val params: List<Pair<String, CBridge>> = emptyList(),
    val returns: CBridge? = null, val isStatic: Boolean = false,
    /** A method's C++ signature, by which an [Upcall] names the one it overrides. */
    val signature: String? = null,
    /** For [Role.UPCALLS]: the virtual functions the created object passes on, by index. */
    val upcalls: List<Upcall> = emptyList(),
    /** The C++ member's documentation comment. */
    val doc: String? = null,
    /** For [Role.DYNAMIC_TYPE]: the records, as C names their types, by the index it returns less one; 0 is none of them. */
    val derived: List<String> = emptyList(),
) {
    /**
     * [UPCALLS] creates an object of a generated subclass, whose virtual functions call the `Upcall` it's given
     * (Bridge.hpp) with the user data [BIND_UPCALLS] sets; with none set, they're the record's own.
     */
    enum class Role { CREATE, ADD_REF, RELEASE, DESTROY, UPCAST, DOWNCAST, DYNAMIC_TYPE, METHOD, GETTER, SETTER, UPCALLS, BIND_UPCALLS }
}

/** A virtual function passed on with [args] in its slots; its result, if any, comes back as [returns]. */
class Upcall(val signature: String, val args: List<CValue>, val returns: CValue?)
