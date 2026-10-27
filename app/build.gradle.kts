import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.paw.agent"
    // Miuix（miuix-core/shader 等 AAR）声明 minCompileSdk=37，故整体提升到 37；
    // SDK 37 平台已安装，AGP 8.13.2 仅会给出"推荐 36"的提示，不影响构建。
    compileSdk = providers.gradleProperty("agentpaw.compileSdk").get().toInt()

    defaultConfig {
        applicationId = "com.paw.agent"
        minSdk = 26
        targetSdk = 36
        versionCode = 13
        versionName = rootProject.file("version.txt").readText().trim()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    val localProperties = Properties().apply {
        val localPropFile = project.rootProject.file("local.properties")
        if (localPropFile.exists()) {
            localPropFile.inputStream().use { stream ->
                this.load(stream)
            }
        }
    }

    val signingStoreFile = localProperties.getProperty("agentpaw.signing.storeFile")
        ?: System.getenv("AGENTPAW_STORE_FILE")
        ?: (project.findProperty("agentpaw.signing.storeFile") as? String)

    val signingStorePass = localProperties.getProperty("agentpaw.signing.storePassword")
        ?: System.getenv("AGENTPAW_STORE_PASSWORD")
        ?: (project.findProperty("agentpaw.signing.storePassword") as? String)

    val signingKeyAlias = localProperties.getProperty("agentpaw.signing.keyAlias")
        ?: System.getenv("AGENTPAW_KEY_ALIAS")
        ?: (project.findProperty("agentpaw.signing.keyAlias") as? String)

    val signingKeyPass = localProperties.getProperty("agentpaw.signing.keyPassword")
        ?: System.getenv("AGENTPAW_KEY_PASSWORD")
        ?: (project.findProperty("agentpaw.signing.keyPassword") as? String)

    val resolvedKeystore = if (!signingStoreFile.isNullOrBlank()) {
        val f = file(signingStoreFile)
        if (f.exists()) f else project.rootProject.file(signingStoreFile).takeIf { it.exists() }
    } else null

    val isSigningConfigured = resolvedKeystore != null &&
        !signingStorePass.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPass.isNullOrBlank()

    signingConfigs {
        if (isSigningConfigured) {
            create("release") {
                storeFile = resolvedKeystore
                storePassword = signingStorePass
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPass
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (isSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (isSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":agentpaw-core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)

    // 可选 UI 主题：Miuix（HyperOS 风格）。Material 主题不依赖它，两套主题共存。
    implementation(libs.miuix.ui)
    implementation(libs.miuix.preference)
    // miuix-glass 的玻璃材质靠它采样背景层、跑模糊与 AGSL 着色器。
    implementation(libs.miuix.blur)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
}

// Miuix 的传递依赖会把 kotlin-stdlib 抬到 2.3.x，与本项目的 Kotlin 插件版本不一致；
// 强制对齐到编译期版本，避免 "compiled with an incompatible version of Kotlin"。
configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.2.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-common:2.2.21")
    }
}
