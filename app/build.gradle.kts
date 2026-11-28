plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.flowcut.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.flowcut.app"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("debugConfig") {
            val localKeystore = file("${rootDir}/debug.keystore")
            if (localKeystore.exists()) {
                storeFile = localKeystore
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            } else {
                initWith(getByName("debug"))
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debugConfig")
            buildConfigField("boolean", "PERF_TELEMETRY", "true")
        }
        release {
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debugConfig")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("boolean", "PERF_TELEMETRY", "false")
        }
        create("perf") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isMinifyEnabled = true
            signingConfig = signingConfigs.getByName("debugConfig")
            buildConfigField("boolean", "PERF_TELEMETRY", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs = listOf(
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.metrics:metrics-performance:1.0.0-beta01")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

tasks.register("checkRules") {
    group = "verification"
    description = "Enforces FlowCut architectural rules at build time (Section 13)."
    doLast {
        val src = file("src/main/java/com/flowcut/app")
        val errors = mutableListOf<String>()

        fun scan(dir: String, rules: List<Pair<Regex, String>>, skip: Set<String> = emptySet()) {
            val d = File(src, dir)
            if (!d.exists()) return
            d.walkTopDown()
                .filter { it.isFile && it.extension == "kt" && it.name !in skip }
                .forEach { f ->
                    f.readLines().forEachIndexed { i, line ->
                        rules.forEach { (rx, msg) ->
                            if (rx.containsMatchIn(line)) {
                                errors += "${f.relativeTo(src)}:${i + 1}: $msg"
                            }
                        }
                    }
                }
        }

        // Rule 1: No import android.* or androidx.* (except compose.runtime.Immutable/Stable) inside core/
        // Rule 6: No importing upper layers (engine, ui, features, audio) inside core/
        scan(
            "core",
            listOf(
                Regex("""import android\.""") to "Rule 1 Violation: core cannot import android.*",
                Regex("""import androidx\.(?!compose\.runtime\.(Immutable|Stable)\b)""") to "Rule 1 Violation: core cannot import androidx.* except Immutable/Stable",
                Regex("""import com\.flowcut\.app\.(engine|ui|features|audio)""") to "Rule 6 Violation: core cannot import upper layers (engine, ui, features, audio)"
            )
        )

        // Rule 2: Zero scroll state in ui/timeline/
        scan(
            "ui/timeline",
            listOf(
                Regex("""\b(LazyRow|LazyColumn|rememberScrollState|ScrollState|horizontalScroll)\b|scrollable\(""") to "Rule 2 Violation: timeline cannot have independent scroll state"
            )
        )

        // Rule 4: No Thread.sleep or runBlocking in core, engine, audio, ui
        for (dir in listOf("core", "engine", "audio", "ui")) {
            scan(
                dir,
                listOf(
                    Regex("""Thread\.sleep|runBlocking""") to "Rule 4 Violation: Thread.sleep or runBlocking is forbidden in $dir"
                )
            )
        }

        // Rule 3: No .timeUs = assignment in any file except PlaybackController.kt
        for (dir in listOf("core", "engine", "audio", "ui", "features")) {
            scan(
                dir,
                listOf(
                    Regex("""\.timeUs\s*=[^=]""") to "Rule 3 Violation: playhead timeUs can only be written by PlaybackController"
                ),
                skip = setOf("PlaybackController.kt")
            )
        }

        // Rule 5: Inside features/<featureA>/, no importing another feature package
        val featuresDir = File(src, "features")
        if (featuresDir.exists()) {
            featuresDir.listFiles()?.filter { it.isDirectory }?.forEach { featureFolder ->
                val featureName = featureFolder.name
                val forbiddenPattern = Regex("""import com\.flowcut\.app\.features\.(?!$featureName\b)\w+""")
                featureFolder.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
                    f.readLines().forEachIndexed { i, line ->
                        if (forbiddenPattern.containsMatchIn(line)) {
                            errors += "${f.relativeTo(src)}:${i + 1}: Rule 5 Violation: feature $featureName cannot import another feature: $line"
                        }
                    }
                }
            }
        }

        if (errors.isNotEmpty()) {
            throw GradleException("FlowCut Architectural Guard (checkRules) Failed with ${errors.size} error(s):\n" + errors.joinToString("\n"))
        }
        println("checkRules: All FlowCut architectural rules passed cleanly.")
    }
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("checkRules")
}
