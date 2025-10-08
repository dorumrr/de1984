plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

// Disable baseline profiles for reproducible builds (F-Droid compatibility)
tasks.whenTaskAdded {
    if (name.contains("compileReleaseArtProfile") ||
        name.contains("mergeReleaseArtProfile") ||
        name.contains("ArtProfile")) {
        enabled = false
    }
}

android {
    namespace = "io.github.dorumrr.de1984"
    compileSdk = 34

    defaultConfig {
        applicationId = "io.github.dorumrr.de1984"
        minSdk = 26
        targetSdk = 34

        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables {
            useSupportLibrary = true
            generatedDensities?.clear()
        }
    }

    androidResources {
        noCompress += listOf()
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = false  // Disabled due to R8 base.jar issue
            isShrinkResources = false
            isCrunchPngs = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            isProfileable = false
            // Use debug signing for reproducible builds
            signingConfig = signingConfigs.getByName("debug")

            // Add deterministic BuildConfig fields for reproducible builds
            buildConfigField("String", "BUILD_TIME", "\"1970-01-01T00:00:00Z\"")
            buildConfigField("boolean", "REPRODUCIBLE_BUILD", "true")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xsuppress-version-warnings",
            // Add reproducible build flags to fix classes.dex differences
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions"
        )
    }

    // KSP configuration for reproducible builds
    ksp {
        // Ensure deterministic processing order for Room
        arg("room.incremental", "true")
        arg("room.expandProjection", "true")
        // Create schemas directory for deterministic Room schema generation
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    buildFeatures {
        compose = true
        viewBinding = true  // Phase 0: Enable ViewBinding
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Additional excludes for reproducible builds
            excludes += "/META-INF/versions/**"
            excludes += "**/kotlin_builtins"
            pickFirsts += listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val versionName = defaultConfig.versionName ?: "unknown"
            output.outputFileName = if (name.contains("release")) {
                "de1984-v${versionName}-release.apk"
            } else {
                "de1984-v${versionName}-debug.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose dependencies (will be removed in Phase 5)
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")

    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    // XML Views dependencies (Phase 0)
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // Room database
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ViewModel support for Compose
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
