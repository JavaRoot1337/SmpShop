plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "ru.javaroot"
version = "1.1.0"
val pluginVersion = version

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    implementation("org.xerial:sqlite-jdbc:3.53.1.0")
    implementation("com.google.code.gson:gson:2.14.0")
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
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
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

tasks.processResources {
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
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
}

tasks.build {
    dependsOn(tasks.shadowJar)
    dependsOn("testConsumerJar")
}

tasks.register<Jar>("testConsumerJar") {
    dependsOn(testConsumer.classesTaskName)
    archiveBaseName.set("SmpShopApiConsumer")
    archiveVersion.set(pluginVersion.toString())
    from(testConsumer.output)
}

tasks.jar {
    enabled = false
}
