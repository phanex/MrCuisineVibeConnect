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
}

tasks.test {
    // TwinIntegrationTest s'exécute seulement si tools/twin.py tourne (sinon ignoré).
    testLogging { events("passed", "skipped", "failed") }
    // I18nTest lit les fichiers de langue et les textes du code de l'appli. Sans eux parmi les entrées de
    // la tâche, Gradle la disait « à jour » quand eux seuls avaient changé, et ne la relançait pas.
    inputs.dir("../app/src/main/assets/lang").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir("../app/src/main/kotlin").withPathSensitivity(PathSensitivity.RELATIVE)
}
