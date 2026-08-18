import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

val releaseKeystorePropertiesFile = rootProject.file("keystore.properties")
val releaseKeystoreProperties = Properties().apply {
    if (releaseKeystorePropertiesFile.isFile) {
        releaseKeystorePropertiesFile.inputStream().use(::load)
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.directonly.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.directonly.app"
        minSdk = 24
        targetSdk = 36
        versionCode = 22
        versionName = "4.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // A fixed debug key committed to the repository.
        //
        // Android's default is a keystore auto-generated per machine, so a debug APK built
        // anywhere else is signed with a different key, and Android refuses to update an
        // installed app whose signature does not match: "App not installed as package
        // conflicts with an existing package." That makes it impossible to ship a debug
        // build from CI or from a fresh checkout without uninstalling first and losing every
        // WebView session. Pinning the key means any machine produces an installable update.
        //
        // This grants no meaningful capability to anyone: it signs only the `.debug`
        // application ID, and the release key is still kept out of the repository entirely.
        create("debugShared") {
            storeFile = rootProject.file("clearfeed-debug.keystore")
            storePassword = "clearfeed"
            keyAlias = "clearfeeddebug"
            keyPassword = "clearfeed"
        }
        if (releaseKeystorePropertiesFile.isFile) {
            create("release") {
                storeFile = rootProject.file(requireNotNull(releaseKeystoreProperties.getProperty("storeFile")))
                storePassword = requireNotNull(releaseKeystoreProperties.getProperty("storePassword"))
                keyAlias = requireNotNull(releaseKeystoreProperties.getProperty("keyAlias"))
                keyPassword = requireNotNull(releaseKeystoreProperties.getProperty("keyPassword"))
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            signingConfig = signingConfigs.getByName("debugShared")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (releaseKeystorePropertiesFile.isFile) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // BlockerStats keys usage counts to local calendar dates, and java.time is API 26+
        // while minSdk is 24.
        isCoreLibraryDesugaringEnabled = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")

    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.18.0")

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
}
