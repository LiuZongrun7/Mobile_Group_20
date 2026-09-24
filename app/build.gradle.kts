plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.mobilegroup20.tokentrail"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.mobilegroup20.tokentrail"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // Generates a typed binding class per layout file, so Activities/Fragments
    // no longer call findViewById() and never risk a wrong-cast at runtime.
    buildFeatures {
        viewBinding = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17

        // 这个 App 的核心是"按天结算用量"，日期和费率生效区间全部走 java.time
        // （LocalDate / Instant / ZoneId）。java.time 到 API 26 才进系统，而 minSdk
        // 是 24，所以开脱糖，让 24、25 上也能直接用，不必退回 Calendar。
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.lifecycle.livedata)
    implementation(libs.androidx.lifecycle.viewmodel)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
