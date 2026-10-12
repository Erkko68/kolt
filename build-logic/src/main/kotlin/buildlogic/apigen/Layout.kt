package buildlogic.apigen

/**
 * Where the generated code lives, and what package it's in. Under the C dir, `<module>/generated/<group>/<Name>.h`:
 * a module is an `api-headers.txt` section, a group the lowercased dirs of the C++ header under
 * [ApiGenConfig.groupRoot]. Under the Kotlin dir, a module named as the C one.
 */
internal class Layout(private val config: ApiGenConfig) {
    fun generated(module: String) = "$module/generated"
    fun manual(module: String) = "$module/manual"

    /** The group of what the C++ [header] declares: its dirs under [ApiGenConfig.groupRoot], lowercased, if any. */
    fun groupOf(header: String?) = config.groupRoot?.let { root ->
        header?.takeIf { it.startsWith("$root/") }?.removePrefix("$root/")?.substringBeforeLast('/', "")?.lowercase()
    }.orEmpty()

    /** The dir of a module's generated files in [group], under the C dir. */
    fun generated(module: String, group: String) = generated(module) + if (group.isEmpty()) "" else "/$group"

    /**
     * The package of a module's externals in [group]: the module's, with the group before its last segment. Group
     * `physics/body` of `a.kolt.capi` is `a.kolt.physics.body.capi`, next to the API of `a.kolt.physics.body`.
     */
    fun externalsPackage(module: String, group: String): String {
        val pkg = config.kotlin!!.packages.getValue(module)
        return if (group.isEmpty()) pkg else "${pkg.substringBeforeLast('.')}.${group.replace('/', '.')}.${pkg.substringAfterLast('.')}"
    }

    /** The package of the externals of the C header at [path] under the C dir, generated or hand-written. */
    fun externalsPackage(path: String) = path.split('/').let { externalsPackage(it[0], it.subList(2, it.size - 1).joinToString("/")) }

    /** The package of a module's typed API in [group]: its externals', a level up. */
    fun apiPackage(module: String, group: String) = externalsPackage(module, group).substringBeforeLast('.')

    /** The dir of [module]'s sources in [pkg], under the Kotlin dir. */
    fun sourceDir(module: String, pkg: String) = "$module/src/commonMain/kotlin/${pkg.replace('.', '/')}"

    /** The dir holding all of [module]'s generated Kotlin, under the Kotlin dir: its externals package's parent. */
    fun sourceRoot(module: String) = sourceDir(module, config.kotlin!!.packages.getValue(module)).substringBeforeLast('/')
}
