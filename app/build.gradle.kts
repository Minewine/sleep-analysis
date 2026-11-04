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
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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

    sourceSets {
        // Exported Room schemas as androidTest assets: MigrationTestHelper replays the real
        // 1.json…8.json history for the migration test (docs/DATA_MODEL.md §7).
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
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

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}

// ---------------------------------------------------------------------------
// CI policy gates (docs/ARCHITECTURE.md §6.6, docs/PRIVACY_AND_REGULATORY.md §8).
// All four run as part of `./gradlew :app:check` — locally and in GitHub Actions —
// and fail the build on drift, so a policy change can never land silently.
// ---------------------------------------------------------------------------

tasks.register("checkPermissionAllowlist") {
    group = "verification"
    description =
        "Fails when the merged debug manifest's permissions differ from ci/permission-allowlist.txt."
    dependsOn("processDebugMainManifest")
    doLast {
        val allowlist = rootProject.file("ci/permission-allowlist.txt").readLines()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .toSortedSet()
        val mergedManifests = fileTree(layout.buildDirectory.dir("intermediates/merged_manifests"))
            .matching { include("**/debug/**/AndroidManifest.xml") }
            .sortedBy { it.path }
        val merged = mergedManifests.lastOrNull()
            ?: throw GradleException("checkPermissionAllowlist: merged debug manifest not found.")
        val requested = Regex("""<uses-permission\s+android:name="([^"]+)"""")
            .findAll(merged.readText())
            .map { it.groupValues[1] }
            .toSortedSet()
        val unexpected = requested - allowlist
        val missing = allowlist - requested
        if (unexpected.isNotEmpty() || missing.isNotEmpty()) {
            throw GradleException(
                "Merged manifest permissions differ from ci/permission-allowlist.txt:\n" +
                    "  unexpected: $unexpected\n  missing: $missing\n" +
                    "Any new permission needs a PRIVACY_AND_REGULATORY.md §3 amendment + allowlist entry."
            )
        }
    }
}

tasks.register("checkForbiddenTerms") {
    group = "verification"
    description =
        "Fails on forbidden claim terms in strings.xml (MEASUREMENT_RELIABILITY §3) outside approved strings."
    doLast {
        // Approved: the mandatory disclaimer and the clinical-baseline field labels, which
        // describe the user's own clinical study, never a phone-derived claim.
        val approved = setOf("onboarding_disclaimer", "baseline_field_arousal", "baseline_field_ahi")
        val forbidden = listOf(
            Regex("""\bahi\b""", RegexOption.IGNORE_CASE),
            Regex("""\bapneas?\b""", RegexOption.IGNORE_CASE),
            Regex("""\barousal index(es)?\b""", RegexOption.IGNORE_CASE),
            Regex("""\bsleep stages?\b""", RegexOption.IGNORE_CASE),
            Regex("""\bdesaturations?\b""", RegexOption.IGNORE_CASE),
        )
        val offenders = fileTree("src/main/res")
            .matching { include("**/strings.xml") }
            .flatMap { file ->
                val text = file.readText()
                Regex("""<string\s+name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
                    .findAll(text)
                    .flatMap { m ->
                        val name = m.groupValues[1]
                        if (name in approved) emptyList()
                        else forbidden.mapNotNull { re ->
                            if (re.containsMatchIn(m.groupValues[2])) "${file.name}:$name → ${re.pattern}" else null
                        }
                    }
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Forbidden claim terms found (docs/MEASUREMENT_RELIABILITY.md §3):\n  " +
                    offenders.joinToString("\n  ")
            )
        }
    }
}

tasks.register("checkRepoHygiene") {
    group = "verification"
    description = "Fails when a tracked file matches the personal-data patterns (mirrors .githooks/pre-commit)."
    doLast {
        // Keep in sync with .githooks/pre-commit and .gitignore (docs/PRIVACY_AND_REGULATORY.md §2).
        val hygiene = Regex(
            """(^night-data/)|(\.wav$)|(\.db$)|(\.db-wal$)|(\.db-shm$)|(\.preferences_pb$)|(session_.*\.log$)|(chart_.*\.png$)"""
        )
        val tracked = ProcessBuilder("git", "ls-files")
            .directory(rootProject.projectDir)
            .start()
            .inputStream.bufferedReader().readText()
            .lines()
            .filter { it.isNotBlank() }
        val offenders = tracked.filter { hygiene.containsMatchIn(it) }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Personal-data files are tracked in git (docs/PRIVACY_AND_REGULATORY.md §2):\n  " +
                    offenders.joinToString("\n  ")
            )
        }
    }
}

tasks.register("checkNetworkHosts") {
    group = "verification"
    description =
        "Fails when a Kotlin URL host in main sources is outside the Garmin single-host allowlist."
    doLast {
        // Single-host allowlist (docs/GARMIN_INTEGRATION.md §8): the only network path is the
        // user-triggered Garmin Personal API pull. Comments are stripped first so doc links
        // don't trip the gate — the (?<!:) lookbehind keeps "://" URLs intact.
        val allowedHosts = setOf("personal.connectapi.garmin.com")
        val urlHost = Regex("""https?://([A-Za-z0-9.\-]+)""")
        val offenders = fileTree("src/main/java")
            .matching { include("**/*.kt") }
            .flatMap { file ->
                val code = file.readText()
                    .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                    .replace(Regex("(?<!:)//.*"), "")
                urlHost.findAll(code)
                    .map { it.groupValues[1].trimEnd('.').lowercase() }
                    .filter { it !in allowedHosts }
                    .map { "${file.toRelativeString(projectDir)} → $it" }
                    .toList()
            }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "Non-allowlisted URL host(s) in code (docs/ARCHITECTURE.md §2.1):\n  " +
                    offenders.joinToString("\n  ") + "\nNew hosts need a privacy review + allowlist update."
            )
        }
    }
}

tasks.named("check") {
    dependsOn(
        "checkPermissionAllowlist",
        "checkForbiddenTerms",
        "checkRepoHygiene",
        "checkNetworkHosts",
    )
}
