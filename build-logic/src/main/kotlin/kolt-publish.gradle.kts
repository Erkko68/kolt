import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

// Maven Central, for every published module. The group, the version and the POM are gradle.properties' (GROUP,
// VERSION_NAME, POM_*), which the plugin reads itself; the artifact is named as the project, or by a module's own
// POM_ARTIFACT_ID. Applied after the plugin that makes the module a library.
plugins {
    id("com.vanniktech.maven.publish")
}

mavenPublishing {
    // An empty javadoc jar, as Central only asks for one: the reference is on GitHub Pages, not copied into every
    // target's jar. Said explicitly, as the default with Dokka applied is its HTML.
    when {
        pluginManager.hasPlugin("org.jetbrains.kotlin.multiplatform") -> configure(KotlinMultiplatform(javadocJar = JavadocJar.Empty()))
        pluginManager.hasPlugin("java-library") -> configure(JavaLibrary(javadocJar = JavadocJar.Empty(), sourcesJar = SourcesJar.Sources()))
    }
    // Signed where there's a key: CI's publish job. publishToMavenLocal works without one.
    if (project.hasProperty("signingInMemoryKeyId")) signAllPublications()
}
