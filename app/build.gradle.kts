import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Essais avec Robolectric, hors ligne : le jar Android 14 est téléchargé par
// Gradle, pas pendant les tests.
val robolectricVersion = "4.12.2"
val robolectricAndroid: Configuration by configurations.creating { isTransitive = false }
// Robolectric tire androidx.test (tests seulement) : passé en fichiers pour que la vérification
// AndroidX de l'Android Gradle Plugin (le projet est en android.useAndroidX=false) ne s'applique pas.
val robolectricTest: Configuration =
    configurations.detachedConfiguration(dependencies.create("org.robolectric:robolectric:$robolectricVersion"))
val robolectricDeps = layout.buildDirectory.dir("robolectric-deps")
val robolectricJars = tasks.register<Sync>("robolectricJars") {
    from(robolectricAndroid)
    into(robolectricDeps)
}

android {
    namespace = "app.toque"
    compileSdk = 34

    defaultConfig {
        applicationId = "app.toque"
        minSdk = 23          // Android 6.0, celui du Monsieur Cuisine Connect
        targetSdk = 28
        // Pour publier une mise à jour : augmenter les deux valeurs. Chaque version a deux APK, de la même
        // source : Toque-x.y.z.apk (Connect) et Toque-smart-x.y.z.apk (gradlew -PtoqueSmart=true, Smart).
        // L'outil de mise à jour prend celui de son robot (Releases.parse).
        versionCode = 18
        versionName = "0.10.0"
        // APK du Monsieur Cuisine Smart (gradlew -PtoqueSmart=true) : le JIT d'Android 8.1 de sa tablette
        // compile mal certaines expressions Kotlin (portages Flav974 et Camybrith) ; le mode sûr le
        // contourne mais ralentit toute l'appli. Jamais dans l'APK du Connect.
        manifestPlaceholders["vmSafeMode"] = (project.findProperty("toqueSmart") == "true").toString()
    }

    signingConfigs {
        // Clé du projet, publique exprès : toutes les compilations (PC, GitHub) portent la même
        // signature, donc chaque mise à jour s'installe par-dessus la précédente.
        create("toque") {
            storeFile = file("signature/toque.p12")
            storeType = "PKCS12"
            storePassword = "toque-public"
            keyAlias = "toque"
            keyPassword = "toque-public"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("toque")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("toque")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    sourceSets["main"].java.srcDirs("src/main/kotlin")

    // Captures d'écran des écrans (Robolectric, dessin natif) : app/build/screenshots/. Essais seulement.
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all { test ->
            test.systemProperty("robolectric.offline", "true")
            test.systemProperty("robolectric.enabledSdks", "34")
            test.systemProperty("robolectric.dependency.dir", robolectricDeps.get().asFile.absolutePath)
            test.systemProperty("toque.screenshots", layout.buildDirectory.dir("screenshots").get().asFile.absolutePath)
            test.dependsOn(robolectricJars)
            test.maxHeapSize = "2g"
            test.testLogging { events("passed", "skipped", "failed") }
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_11) }
}

dependencies {
    implementation(project(":core"))
    // Essais seulement : rien de cela n'entre dans l'APK.
    testImplementation("junit:junit:4.13.2")
    testImplementation(files(robolectricTest))
    testImplementation("com.google.zxing:core:3.5.3")
    robolectricAndroid("org.robolectric:android-all-instrumented:14-robolectric-10818077-i6")
}
