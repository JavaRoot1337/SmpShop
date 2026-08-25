plugins {
    java
    id("com.gradleup.shadow") version "9.0.0"
}

group = "ru.javaroot"
version = "1.0"

val defaultSqliteNativePath = when {
    System.getProperty("os.name").startsWith("Windows") -> "Windows/x86_64"
    System.getProperty("os.name").startsWith("Mac") -> "Mac/x86_64"
    else -> "Linux/x86_64"
}
val sqliteNativePath = providers.gradleProperty("sqliteNativePath")
    .orElse(defaultSqliteNativePath)
    .get()

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://libraries.minecraft.net")
    maven("https://maven.canvasmc.io/public")
}

dependencies {
    compileOnly("io.canvasmc.canvas:canvas-api:26.2.build.827-alpha")
    implementation("org.xerial:sqlite-jdbc:3.50.3.0")
    implementation("com.google.code.gson:gson:2.13.1")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("io.canvasmc.canvas:canvas-api:26.2.build.827-alpha")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

configurations.testCompileOnly {
    extendsFrom(configurations.compileOnly.get())
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.shadowJar {
    archiveBaseName.set("SmpShop")
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("com.google.gson", "ru.javaroot.libs.gson")
    exclude { entry ->
        val path = entry.path
        path.startsWith("org/sqlite/native/") &&
            !path.startsWith("org/sqlite/native/$sqliteNativePath/")
    }
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.jar {
    enabled = false
}
