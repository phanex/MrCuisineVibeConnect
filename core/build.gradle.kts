import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Logique pure (protocole, sécurité, pilotage, recettes, recherche) : testable sans Android.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.zxing:core:3.5.3")
}

tasks.test {
    // TwinIntegrationTest s'exécute seulement si tools/twin.py tourne (sinon ignoré).
    testLogging { events("passed", "skipped", "failed") }
    // I18nTest lit les fichiers de langue et les textes du code de l'appli. Sans eux parmi les entrées de
    // la tâche, Gradle la disait « à jour » quand eux seuls avaient changé, et ne la relançait pas.
    inputs.dir("../app/src/main/assets/lang").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("../app/src/main/kotlin").withPathSensitivity(PathSensitivity.RELATIVE)
}

// Démo du compagnon (serveur JVM sur base simulée) : source set à part, jamais dans l'APK.
val demo: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += sourceSets["main"].output
}
configurations[demo.implementationConfigurationName].extendsFrom(configurations["implementation"])
configurations[demo.runtimeOnlyConfigurationName].extendsFrom(configurations["runtimeOnly"])
// Les tests lisent aussi le catalogue de la démo (calcul du nombre de personnes sur le téléphone).
sourceSets["test"].compileClasspath += demo.output
sourceSets["test"].runtimeClasspath += demo.output

tasks.register<JavaExec>("companionDemo") {
    group = "application"
    description = "Serveur de démonstration de Toque Compagnon (-PdemoArgs=\"--port 8088 --fast 5\")"
    classpath = demo.runtimeClasspath
    mainClass.set("app.toque.core.companion.demo.DemoMainKt")
    standardInput = System.`in`
    workingDir = projectDir
    val extra = (project.findProperty("demoArgs") as String?)?.trim().orEmpty()
    if (extra.isNotEmpty()) args(extra.split(Regex("\\s+")))
}
