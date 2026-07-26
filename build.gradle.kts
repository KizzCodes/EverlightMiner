plugins {
    id("java")
}

group = "net.kyle"
version = "1.0.0"

repositories {
    mavenLocal()
    mavenCentral()
    // Official BotWithUs artifact repository.
    maven { url = uri("https://nexus.botwithus.net/repository/maven-public/") }
}

// The installed BotWithUs client runs Java 20 with preview features enabled, and
// its API is compiled that way. Preview features are tied to the exact JDK
// version, so this project MUST be built with a JDK 20 (release 20 + preview).
java {
    sourceCompatibility = JavaVersion.VERSION_20
    targetCompatibility = JavaVersion.VERSION_20
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}

val includeInJar: Configuration by configurations.creating { isTransitive = false }

dependencies {
    // Core rs3 API is provided by the client at runtime -> compileOnly.
    compileOnly("net.botwithus.rs3:botwithus-api:1.0.8")
    // xapi.public is NOT provided to local scripts -> bundle it into the jar.
    implementation("net.botwithus.xapi.public:api:1.2.12")
    includeInJar("net.botwithus.xapi.public:api:1.2.12")
}

// Drop the built jar straight into the client's local-scripts folder.
val localScripts = "${System.getProperty("user.home")}/BotWithUs/scripts/local"

val copyJar by tasks.register<Copy>("copyJar") {
    from(tasks.named<Jar>("jar"))
    into(localScripts)
    include("*.jar")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("EverlightMiner")
    from({ includeInJar.map { zipTree(it) } })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    finalizedBy(copyJar)
}
