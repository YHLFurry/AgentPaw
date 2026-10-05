plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

// Version can be overridden via -Pagentpaw.version= (used by CI when publishing from a tag)
val agentpawVersion: String = (project.findProperty("agentpaw.version") as String?) ?: "0.1.0"

android {
    namespace = "com.paw.agent.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    api(libs.shizuku.api)
    api(libs.shizuku.provider)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val sourcesJar = tasks.register<Jar>("sourcesJar") {
    archiveClassifier = "sources"
    from(android.sourceSets.getByName("main").java.srcDirs)
}

afterEvaluate {
    publishing {
        publications {
            register<MavenPublication>("release") {
                groupId = "com.paw.agent"
                artifactId = "agentpaw-core"
                version = agentpawVersion

                from(components.getByName("release"))
                artifact(sourcesJar)

                pom {
                    name = "AgentPaw Core"
                    description = "Core conversation engine and signature capabilities of AgentPaw: " +
                        "an Android LLM agent loop with OpenAI-compatible clients (incl. multimodal), " +
                        "tool calling, sub-agent delegation, agent skills, a sandboxed shell interpreter, " +
                        "web search, and on-device control via Accessibility & Shizuku."
                    inceptionYear = "2026"
                    url = "https://github.com/YHLFurry/AgentPaw"

                    licenses {
                        license {
                            name = "The Apache License, Version 2.0"
                            url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        }
                    }

                    developers {
                        developer {
                            id = "YHLFurry"
                            name = "Kira"
                            url = "https://github.com/YHLFurry"
                        }
                    }

                    scm {
                        url = "https://github.com/YHLFurry/AgentPaw"
                        connection = "scm:git:git://github.com/YHLFurry/AgentPaw.git"
                        developerConnection = "scm:git:ssh://git@github.com/YHLFurry/AgentPaw.git"
                    }
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/YHLFurry/AgentPaw")
                credentials {
                    username = System.getenv("GITHUB_ACTOR") ?: (project.findProperty("gpr.user") as String? ?: "")
                    password = System.getenv("GITHUB_TOKEN") ?: (project.findProperty("gpr.key") as String? ?: "")
                }
            }
        }
    }
}
