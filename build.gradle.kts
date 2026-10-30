import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.util.Properties

plugins {
    kotlin("jvm") version "1.9.24"
    kotlin("plugin.serialization") version "1.9.24"
    application
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

// ---------- Credentials ----------
val secretPropsFile = rootProject.file("local.properties")
if (secretPropsFile.exists()) {
    val p = Properties()
    secretPropsFile.inputStream().use { p.load(it) }
    p.forEach { name, value -> extra[name.toString()] = value.toString() }
}

fun prop(key: String) = extra.properties[key]?.toString() ?: System.getenv(key) ?: ""

// ---------- Project ----------
group = "org.androidgradletools"
version = "0.1.1"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(11))
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    implementation(platform("io.ktor:ktor-bom:2.3.12"))
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-netty")
    implementation("io.ktor:ktor-server-websockets")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-client-cio")
    testImplementation("io.ktor:ktor-client-websockets")
}

application {
    mainClass.set("org.androidgradletools.adbrelay.relay.MainKt")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "11"
}

// ---------- Publishing ----------
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "org.androidgradletools"
            artifactId = "adbrelay-jvm"
            version = project.version.toString()

            from(components["java"])

            pom {
                name.set("ADB Relay JVM")
                description.set("ADB Relay JVM - relay ADB connections over network.")
                url.set("https://github.com/cuongnv126/adb-relay-jvm")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://github.com/cuongnv126/adb-relay-jvm/blob/main/LICENSE")
                    }
                }
                developers {
                    developer {
                        id.set("cuongnv")
                        name.set("Cuong V. Nguyen")
                        email.set("cuongnv126@gmail.com")
                    }
                }
                scm {
                    connection.set("scm:git:github.com/cuongnv126/adb-relay-jvm.git")
                    developerConnection.set("scm:git:ssh://github.com/cuongnv126/adb-relay-jvm.git")
                    url.set("https://github.com/cuongnv126/adb-relay-jvm/tree/main")
                }
            }
        }
    }
}

signing {
    useInMemoryPgpKeys(
        prop("signing.keyId"),
        prop("signing.key"),
        prop("signing.password"),
    )
    sign(publishing.publications["release"])
}

nexusPublishing {
    repositories {
        sonatype {
            stagingProfileId.set(prop("sonatypeStagingProfileId"))
            username.set(prop("ossrhUsername"))
            password.set(prop("ossrhPassword"))
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}
