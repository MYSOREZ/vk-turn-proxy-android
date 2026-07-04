plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.vkturn.proxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vkturn.proxy"
        minSdk = 23
        targetSdk = 28
        versionCode = 9
        versionName = "2.0.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // Поддержка всех основных архитектур
            abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a", "x86", "x86_64"))
        }
    }

    // Фиксированный тестовый keystore (не для Google Play): используется во всех
    // сборках, включая CI, чтобы подпись APK не менялась от сборки к сборке —
    // иначе обновление приложения поверх предыдущей версии ломается.
    signingConfigs {
        getByName("debug") {
            storeFile = file("ci-release.keystore.jks")
            storePassword = "vkturnproxy-ci"
            keyAlias = "ci-release"
            keyPassword = "vkturnproxy-ci"
        }
    }

    // Красивое именование APK файлов
    applicationVariants.all {
        val variantName = name
        val vName = versionName
        outputs.all {
            val output = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
            // Извлекаем ABI из имени выхода (например, "arm64-v8aRelease" -> "arm64-v8a")
            val abiSuffix = name.replace(variantName, "", ignoreCase = true)
            val finalAbi = if (abiSuffix.isEmpty() || abiSuffix.lowercase() == "universal") "universal" else abiSuffix
            output.outputFileName = "TurnProxy_v${vName}_${finalAbi}.apk"
        }
    }

    splits {
        abi {
            isEnable = true // Включаем разделение
            reset() // Сбрасываем выбор по умолчанию
            include("arm64-v8a", "armeabi-v7a", "x86", "x86_64") // Указываем, какие APK собрать отдельно
            isUniversalApk = true // Генерировать один общий universal APK
        }
    }

    packaging {
        resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        jniLibs.useLegacyPackaging = true
    }
    // ---------------------------------

    buildTypes {
        release {
            isMinifyEnabled = true // Сжатие кода (R8/Proguard)
            isShrinkResources = true // Удаление неиспользуемых ресурсов
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // Отключаем проверку lint для релизных сборок, чтобы сборка release не падала из‑за ExpiredTargetSdkVersion
    lint {
        checkReleaseBuilds = false
        // при желании можно только отключить конкретное правило:
        disable += "ExpiredTargetSdkVersion"
    }

    buildFeatures {
        compose = true
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    // Core Library Desugaring (поддержка новых Java/Android API на старых устройствах)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

    // SSH и Корутины
    implementation("com.github.mwiede:jsch:0.2.17")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.google.code.gson:gson:2.10.1")

    // Стандартные библиотеки Android
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))

    // QR & Camera
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
}