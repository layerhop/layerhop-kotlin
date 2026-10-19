import com.vanniktech.maven.publish.SonatypeHost
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.0.21"
    kotlin("plugin.serialization") version "2.0.21"
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "com.layerhop"
version = "0.6.0"

repositories {
    mavenCentral()
}

dependencies {
    api("com.squareup.okhttp3:okhttp:4.12.0")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

// Java 8 bytecode: runs on any JVM 8+ and on Android (minSdk 21).
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
    }
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Publishing to Maven Central (Central Portal). Credentials and the signing key come from Gradle
// properties, usually set as environment variables in CI:
//   ORG_GRADLE_PROJECT_mavenCentralUsername, ORG_GRADLE_PROJECT_mavenCentralPassword,
//   ORG_GRADLE_PROJECT_signingInMemoryKey, ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
// Release: ./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
mavenPublishing {
    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    coordinates("com.layerhop", "layerhop", version.toString())
    pom {
        name.set("LayerHop")
        description.set("Kotlin/JVM and Android client for the LayerHop cross-chain swap API. Non-custodial: returns unsigned transactions.")
        inceptionYear.set("2026")
        url.set("https://github.com/layerhop/layerhop-kotlin")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("layerhop")
                name.set("LayerHop")
                url.set("https://layerhop.com")
            }
        }
        scm {
            url.set("https://github.com/layerhop/layerhop-kotlin")
            connection.set("scm:git:https://github.com/layerhop/layerhop-kotlin.git")
            developerConnection.set("scm:git:ssh://git@github.com/layerhop/layerhop-kotlin.git")
        }
    }
}
