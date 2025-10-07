plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
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

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            isCrunchPngs = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            val isSelfDistributed = project.findProperty("IS_SELF_DISTRIBUTED")
                ?.toString()?.toBoolean() ?: false

            buildConfigField("boolean", "IS_SELF_DISTRIBUTED", if (isSelfDistributed) "true" else "false")
            buildConfigField("String", "GITHUB_REPO", "\"dorumrr/de1984\"")
            buildConfigField("String", "UPDATE_CHECK_URL",
                "\"https://api.github.com/repos/dorumrr/de1984/releases/latest\"")
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"

            buildConfigField("Boolean", "IS_SELF_DISTRIBUTED", "false")
            buildConfigField("String", "GITHUB_REPO", "\"dorumrr/de1984\"")
            buildConfigField("String", "UPDATE_CHECK_URL", "\"\"")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xsuppress-version-warnings"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            pickFirsts += listOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }

    applicationVariants.all {
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            val versionName = defaultConfig.versionName ?: "unknown"
            output.outputFileName = if (name.contains("release")) {
                "de1984-release-v${versionName}.apk"
            } else {
                "de1984-debug-v${versionName}.apk"
            }
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.navigation:navigation-compose:2.7.6")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.dagger:hilt-android:2.48.1")
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    ksp("com.google.dagger:hilt-compiler:2.48.1")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
