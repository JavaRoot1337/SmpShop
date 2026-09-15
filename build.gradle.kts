plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "ru.javaroot"
version = "1.0"
val pluginVersion = version
val paperApiVersion = providers.gradleProperty("paperApiVersion")
    .orElse("1.21.11-R0.1-SNAPSHOT")
val paperApi = paperApiVersion.get()
val targetJava = providers.gradleProperty("targetJava")
    .map(String::toInt)
    .orElse(21)
val targetJavaVersion = targetJava.get()
val pluginApiVersion = providers.gradleProperty("pluginApiVersion")
    .orElse(if (paperApi.startsWith("26.")) "26.2" else "1.21.11")
val compatibilityClassifier = providers.gradleProperty("compatibilityClassifier").orElse("")
val paper26CompileClasspath by configurations.creating {
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
    compileOnly("io.papermc.paper:paper-api:$paperApi")
    paper26CompileClasspath("io.papermc.paper:paper-api:26.2.build.+")
    implementation("org.xerial:sqlite-jdbc:3.53.1.0")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("io.papermc.paper:paper-api:$paperApi")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.testCompileOnly {
    extendsFrom(configurations.compileOnly.get())
}

val testConsumer by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
    runtimeClasspath += output + compileClasspath
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(targetJavaVersion))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(targetJavaVersion)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(
            "version" to pluginVersion,
            "apiVersion" to pluginApiVersion.get()
        )
    }
}

tasks.test {
    useJUnitPlatform()
}

val compilePaper26Java by tasks.registering(JavaCompile::class) {
    source(sourceSets.main.get().allJava)
    classpath = paper26CompileClasspath
    destinationDirectory.set(layout.buildDirectory.dir("classes/java/paper26"))
    javaCompiler.set(javaToolchains.compilerFor {
        languageVersion.set(JavaLanguageVersion.of(25))
    })
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.check {
    dependsOn(compilePaper26Java)
}

tasks.named<ProcessResources>(testConsumer.processResourcesTaskName) {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(
            "version" to pluginVersion,
            "apiVersion" to pluginApiVersion.get()
        )
    }
}

tasks.shadowJar {
    archiveBaseName.set("SmpShop")
    archiveClassifier.set(compatibilityClassifier)
    duplicatesStrategy = DuplicatesStrategy.INCLUDE
    mergeServiceFiles()
    relocate("com.google.gson", "ru.javaroot.libs.gson")
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

tasks.build {
    dependsOn(tasks.shadowJar)
    dependsOn("testConsumerJar")
}

tasks.register<Jar>("testConsumerJar") {
    dependsOn(testConsumer.classesTaskName)
    archiveBaseName.set("SmpShopApiConsumer")
    archiveVersion.set(pluginVersion.toString())
    destinationDirectory.set(layout.buildDirectory.dir("examples"))
    from(testConsumer.output)
}

tasks.jar {
    enabled = false
}
