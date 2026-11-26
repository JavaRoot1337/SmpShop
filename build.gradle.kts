plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "ru.javaroot.smpshop"
version = "2.0"

val targetJava = 21
val paperApiVersion: String by project
val modernPaperApiVersion: String by project
val sqliteVersion: String by project
val gsonVersion: String by project
val junitVersion: String by project

val modernPaperApi by configurations.creating {
    extendsFrom(configurations.implementation.get())
}

val sqliteNativePlatforms = listOf(
    "Linux/x86_64",
    "Linux/aarch64",
    "Linux-Musl/x86_64",
    "Linux-Musl/aarch64",
    "Windows/x86_64",
    "Windows/aarch64",
    "Mac/x86_64",
    "Mac/aarch64"
)

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("org.jetbrains:annotations:24.1.0")
    implementation("org.xerial:sqlite-jdbc:$sqliteVersion")
    implementation("com.google.code.gson:gson:$gsonVersion")

    modernPaperApi("io.papermc.paper:paper-api:$modernPaperApiVersion")
    modernPaperApi("org.jetbrains:annotations:24.1.0")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(targetJava))
}

tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release.set(targetJava)
    }

    processResources {
        filteringCharset = "UTF-8"
        val props = mapOf("version" to project.version.toString())
        inputs.properties(props)
        filesMatching("paper-plugin.yml") {
            expand(props)
        }
    }

    test {
        useJUnitPlatform()
    }

    jar {
        enabled = false
        archiveBaseName.set("SmpShop")
    }

    register<JavaCompile>("compileModernJava") {
        description = "Compiles sources against Paper 26.x API."
        group = "verification"
        source(sourceSets.main.get().allJava)
        classpath = modernPaperApi
        destinationDirectory.set(layout.buildDirectory.dir("classes/java/modern"))
        javaCompiler.set(javaToolchains.compilerFor {
            languageVersion.set(JavaLanguageVersion.of(25))
        })
        options.encoding = "UTF-8"
        options.release.set(targetJava)
    }

    check {
        dependsOn("compileModernJava")
    }

    shadowJar {
        archiveBaseName.set("SmpShop")
        archiveClassifier.set("")
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        mergeServiceFiles()
        relocate("com.google.gson", "ru.javaroot.smpshop.libs.gson")
        eachFile {
            if (path.startsWith("org/sqlite/native/")
                && sqliteNativePlatforms.none { platform ->
                    path.startsWith("org/sqlite/native/$platform/")
                }
            ) {
                exclude()
            }
        }
    }

    build {
        dependsOn(shadowJar)
    }
}
