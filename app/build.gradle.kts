plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "net.k1lllagt.andraxng"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.k1lllagt.andraxng"
        minSdk = 26
        // targetSdk 28 on purpose: Android 10+ blocks exec() from writable app
        // dirs for targetSdk >= 29. See README "Architecture decisions".
        // Sideload-only; Google Play will not accept this.
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"

        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            val ks = System.getenv("ANDRAX_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("ANDRAX_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("ANDRAX_KEY_ALIAS")
                keyPassword = System.getenv("ANDRAX_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            // Minification is off for the first builds; the terminal libraries
            // and commons-compress use reflection in places. proguard-rules.pro
            // is ready for when you want to turn it on.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig =
                if (System.getenv("ANDRAX_KEYSTORE") != null) signingConfigs.getByName("release")
                else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    packaging {
        // proot and helpers must be extracted to nativeLibraryDir so they can
        // be executed. Needs extractNativeLibs="true" in the manifest too.
        jniLibs { useLegacyPackaging = true }
        resources {
            pickFirsts += setOf("META-INF/LICENSE*", "META-INF/NOTICE*", "META-INF/DEPENDENCIES")
        }
    }

    lint {
        abortOnError = false
        disable += setOf("ExpiredTargetSdkVersion", "OldTargetApi")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Pure-JVM tar.xz extraction (symlinks/permissions applied via android.system.Os)
    implementation("org.apache.commons:commons-compress:1.26.2")
    implementation("org.tukaani:xz:1.10")

    // Termux terminal libraries (keep license + attribution, see NOTICE.md)
    implementation("com.github.termux.termux-app:terminal-view:v0.118.1")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.1")
}

// Fail early with a clear message if scripts/fetch-native.sh was not run.
val checkNativeLibs = tasks.register("checkNativeLibs") {
    doLast {
        val dir = file("src/main/jniLibs/arm64-v8a")
        val need = listOf(
            "libproot.so", "libproot-loader.so", "libproot-loader32.so",
            "libtalloc.so", "libandroid-shmem.so"
        )
        val missing = need.filter { !File(dir, it).exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Missing native libs in ${dir.path}: $missing. Run scripts/fetch-native.sh first."
            )
        }
    }
}
tasks.matching { it.name.matches(Regex("merge.*JniLibFolders")) }
    .configureEach { dependsOn(checkNativeLibs) }
