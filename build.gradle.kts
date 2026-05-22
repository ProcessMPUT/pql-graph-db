plugins {
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.spring") version "2.2.0"
    id("org.springframework.boot") version "3.5.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.4.0"
    id("antlr")
}

group = "com.processm"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.neo4j.driver:neo4j-java-driver:5.26.3")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // PQL parsing dependencies - ANTLR4
    val antlrVersion = "4.13.2"
    antlr("org.antlr:antlr4:$antlrVersion")
    implementation("org.antlr:antlr4-runtime:$antlrVersion")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:neo4j")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

spotless {
    kotlin {
        target("src/**/*.kt")
    }

    kotlinGradle {
        target("*.gradle.kts")
    }
}

tasks.generateGrammarSource {
    arguments = arguments + listOf("-visitor", "-no-listener")
}

sourceSets {
    main {
        java {
            srcDirs("build/generated-src/antlr/main")
        }
    }
}

// Ensure ANTLR generates parser before Kotlin compilation
tasks.named("compileKotlin") {
    dependsOn("generateGrammarSource")
}

// Gradle 8 strict mode: compileTestKotlin reads from generated antlr/test output dir
// even when there's no test grammar, so make the dependency explicit.
tasks.named("compileTestKotlin") {
    dependsOn("generateTestGrammarSource")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
    maxHeapSize = "4g"
}
