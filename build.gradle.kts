plugins {
    java
    id("com.gradleup.shadow") version "8.3.5"
}

group = "com.nevarielle"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Shaded + relocated into the plugin jar (see shadowJar below).
    implementation("org.bstats:bstats-bukkit:3.1.0")
    // sqlite-jdbc is NOT shaded: it is downloaded at runtime via plugin.yml `libraries`.
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

tasks.shadowJar {
    relocate("org.bstats", "com.nevarielle.happyghastpet.libs.bstats")
    archiveClassifier.set("")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}
