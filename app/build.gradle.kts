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

        // Room 把每一版的建表语句导出成 JSON 放在这里，用来验证升级迁移有没有写对。
        // 不配的话，第一个 @Database 出现时构建会警告「Schema export directory is not
        // provided」——警告本身不影响运行，但没有它就没有迁移测试的基准。
        javaCompileOptions {
            annotationProcessorOptions {
                argument("room.schemaLocation", "$projectDir/schemas")
            }
        }
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

    // 把导出的建表语句也当成 androidTest 的 asset 打进去。
    // MigrationTestHelper 是按 assets 路径找 schema 的——它拿 @Database 类的全限定名
    // 当目录名，去找 "<包名>.AppDatabase/1.json"。不配这一行，测试会在运行时抛
    // FileNotFoundException，而不是在编译期报错。
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
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

    // Room：数据侧（张莉那块）的本地库。注解处理器必须挂在 annotationProcessor 上，
    // 它在编译期生成建表语句和查询实现，所以列名写错是编译报错而不是运行时崩。
    // 生成物不进 APK，只影响编译时间。
    implementation(libs.androidx.room.runtime)
    annotationProcessor(libs.androidx.room.compiler)

    // MPAndroidChart：dashboard 的折线/柱状图。纯画图库，和游戏侧没有任何耦合
    // （game/ 底下不 import data/，也不 import 它）。
    implementation(libs.mpandroidchart)

    // kotlinx-serialization 版本对齐——这条不是给 App 用的，是给迁移测试用的。
    //
    // 出事的地方：androidx.savedstate 1.4.0 带进来 kotlinx-serialization-core 1.7.3，
    // 而 room-testing 解析 app/schemas/ 里的 JSON 走的是 room-migration，它按 1.8.1 编译。
    // 1.7.3 的运行时在 PluginGeneratedSerialDescriptor 里会去调
    // GeneratedSerializer.typeParametersSerializers()，而 1.8 生成的序列化器里
    // 已经没有这个方法了——于是 AppDatabaseMigrationTest 一跑就 AbstractMethodError，
    // 报错点还在 kotlinx 内部，看不出和 Room 有关。
    //
    // 为什么约束加在主 classpath 而不是 androidTestImplementation：AGP 的
    // consistent resolution 会强制 androidTest 跟着主 classpath 走（报错里那句
    // "By constraint: version resolved in configuration ':app:debugRuntimeClasspath'"），
    // 只改 androidTest 会被它覆盖掉，白改。
    implementation(platform(libs.kotlinx.serialization.bom))

    coreLibraryDesugaring(libs.desugar.jdk.libs)
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    // 迁移测试要真机/模拟器（它建的是真的 SQLite 文件），所以挂在 androidTest 上。
    androidTestImplementation(libs.androidx.room.testing)
}
