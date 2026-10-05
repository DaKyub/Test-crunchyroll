import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.dakyub.crunchymal"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dakyub.crunchymal"
        minSdk = 24
        targetSdk = 35
        // Numéro de build GitHub Actions : chaque nouvel APK est une version supérieure.
        val runNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionCode = runNumber
        versionName = "0.2.$runNumber"

        // Identifiants client de l'app Android TV Crunchyroll ("Basic xxx=" ou juste "xxx=").
        // Fournis par la variable d'environnement CR_BASIC_AUTH ou par local.properties (cr.basicAuth).
        val localProps = Properties().apply {
            rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
        }
        val basicAuth = System.getenv("CR_BASIC_AUTH")?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("cr.basicAuth", "")
        buildConfigField("String", "CR_BASIC_AUTH", "\"${basicAuth.trim()}\"")

        // Client ID de l'API MyAnimeList : variable MAL_CLIENT_ID ou local.properties (mal.clientId).
        val malClientId = System.getenv("MAL_CLIENT_ID")?.takeIf { it.isNotBlank() }
            ?: localProps.getProperty("mal.clientId", "")
        buildConfigField("String", "MAL_CLIENT_ID", "\"${malClientId.trim()}\"")
    }

    // Clé de signature fixe (app perso installée en sideload) : chaque build peut ainsi
    // être installé par-dessus le précédent sans désinstaller.
    signingConfigs {
        create("sideload") {
            storeFile = file("signing/crunchymal.jks")
            storePassword = "crunchymal"
            keyAlias = "crunchymal"
            keyPassword = "crunchymal"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("sideload")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("sideload")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.tv.material3.ExperimentalTvMaterial3Api")
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.tv:tv-material:1.0.0")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
}
