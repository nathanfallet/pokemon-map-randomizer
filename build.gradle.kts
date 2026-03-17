plugins {
    alias(libs.plugins.composeHotReload) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.maven) apply false
    alias(libs.plugins.kover)
}

allprojects {
    group = "me.nathanfallet.pokemonmaprandomizer"
    version = "1.0.1"
    project.ext.set("url", "https://github.com/nathanfallet/pokemon-map-randomizer")
    project.ext.set("license.name", "Apache 2.0")
    project.ext.set("license.url", "https://www.apache.org/licenses/LICENSE-2.0.txt")
    project.ext.set("developer.id", "nathanfallet")
    project.ext.set("developer.name", "Nathan Fallet")
    project.ext.set("developer.email", "contact@nathanfallet.me")
    project.ext.set("developer.url", "https://www.nathanfallet.me")
    project.ext.set("scm.url", "https://github.com/nathanfallet/pokemon-map-randomizer.git")
}

dependencies {
    kover(projects.core)
    kover(projects.app)
}
