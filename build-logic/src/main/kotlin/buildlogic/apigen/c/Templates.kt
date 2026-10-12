package buildlogic.apigen.c

import buildlogic.apigen.ApiGenConfig
import buildlogic.apigen.cpp.CppApi
import buildlogic.apigen.cpp.CppMethod
import buildlogic.apigen.cpp.CppRecord
import buildlogic.apigen.cpp.CppType
import buildlogic.apigen.cpp.CppType.Kind

/** The templates C binds: the instantiations [ApiGenConfig] lists of a class or function template. */
internal class Templates(private val api: CppApi, private val config: ApiGenConfig) {
    /** A class template C binds, or a record nested in one: C binds the instantiation the library exports. */
    fun instantiated(name: String) = instantiations(name).isNotEmpty()

    /** A template [record] C doesn't bind. */
    fun uninstantiated(record: CppRecord) = record.template && !instantiated(record.name)

    /** [ApiGenConfig.functionInstantiations] entries no template matched: stale after an upstream rename. */
    val unusedFunctionInstantiations = config.functionInstantiations.keys.toMutableSet()

    /**
     * The instantiations of the function template [method] C binds, each with the template arguments its call spells;
     * null when the table lists none.
     */
    fun functionInstantiations(method: CppMethod): List<Pair<CppMethod, List<String>>>? {
        val key = "${method.owner}::${method.name}"
        val instantiations = config.functionInstantiations[key] ?: return null
        unusedFunctionInstantiations -= key
        val parameters = method.templateParameters!!
        return instantiations.map { arguments ->
            check(parameters.containsAll(arguments.keys)) { "$key has no template parameter ${arguments.keys - parameters.toSet()}" }
            method.instantiate(arguments.mapValues { (_, a) -> argumentType(a) }) to parameters.takeWhile { it in arguments }.map { arguments.getValue(it) }
        }
    }

    /** A template argument as the model would resolve it. */
    private fun argumentType(spelling: String) = when {
        CAbi.isBuiltin(spelling) -> CppType(spelling, null, Kind.BUILTIN)
        spelling in api.records || spelling in api.enums -> CppType(spelling, spelling, Kind.DECLARED)
        else -> CppType(spelling, spelling, Kind.EXTERNAL)
    }

    /** [decl] as C++ spells it: an instantiated template takes its arguments. */
    fun cpp(decl: String): String {
        var qualified = ""
        return decl.split("::").joinToString("::") { segment ->
            qualified = if (qualified.isEmpty()) segment else "$qualified::$segment"
            config.instantiations[qualified]?.let { "$segment<${it.values.joinToString()}>" } ?: segment
        }
    }

    /** [spelling], written in [scope], with the template parameters of the instantiations around it replaced. */
    fun substitute(spelling: String, scope: String) = instantiations(scope).flatMap { config.instantiations.getValue(it).entries }
        .fold(spelling) { s, (parameter, argument) -> s.replace(Regex("\\b$parameter\\b"), argument) }

    /** The argument [ApiGenConfig.instantiations] gives the template parameter [decl] (`Template::T`), or null. */
    fun argument(decl: String) = config.instantiations[decl.substringBeforeLast("::")]?.get(decl.substringAfterLast("::"))

    private fun instantiations(name: String) = name.split("::").runningReduce { a, b -> "$a::$b" }.filter { it in config.instantiations }
}
