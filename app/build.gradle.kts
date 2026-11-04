plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.sleepanalysis"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.sleepanalysis"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    // Release signing is opt-in via environment (no keystore paths or secrets in
    // the repo): the "release" config exists only when ALL four variables are set,
    // so env-var-less builds stay unsigned debug-style exactly as before.
    signingConfigs {
        val storeFile = System.getenv("RELEASE_STORE_FILE")
        val storePassword = System.getenv("RELEASE_STORE_PASSWORD")
        val keyAlias = System.getenv("RELEASE_KEY_ALIAS")
        val keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
        if (storeFile != null && storePassword != null && keyAlias != null && keyPassword != null) {
            create("release") {
                this.storeFile = file(storeFile)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Null when the env vars are absent -> release stays unsigned (previous behavior).
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Fixture eval (docs/NEXT_STEPS.md P2): replays the labeled clips exported by
// tools/export_fixtures.py through the current detector. Skips silently when no
// fixtures/manifest.json exists; -PsleepFixturesDir overrides the fixture directory.
tasks.register<Test>("evalFixtures") {
    group = "verification"
    description = "Offline fixture eval against the labeled clips in fixtures/ (skips when absent)."
    val unitTestTask = tasks.named("testDebugUnitTest", Test::class.java)
    dependsOn(unitTestTask)
    testClassesDirs = unitTestTask.get().testClassesDirs
    classpath = unitTestTask.get().classpath
    include("com/sleepanalysis/monitoring/eval/FixtureEvalRunTest*")
    if (project.hasProperty("sleepFixturesDir")) {
        systemProperty("sleep.fixtures.dir", project.property("sleepFixturesDir"))
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.ext.compiler)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Real org.json for JVM unit tests (android.jar stubs would throw "not mocked").
    testImplementation(libs.json)
}
