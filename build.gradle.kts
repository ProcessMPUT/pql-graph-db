plugins {
    kotlin("jvm") version "2.3.21"
    kotlin("plugin.spring") version "2.3.21"
    id("org.springframework.boot") version "4.0.6"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.diffplug.spotless") version "8.4.0"
    id("antlr")
}

group = "com.processm"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-neo4j")
    implementation("org.springframework.boot:spring-boot-jackson2")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // PQL parsing dependencies - ANTLR4
    val antlrVersion = "4.13.2"
    antlr("org.antlr:antlr4:$antlrVersion")
    implementation("org.antlr:antlr4-runtime:$antlrVersion")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-neo4j")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    jvmToolchain(25)
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

val benchmarkSourceSet = sourceSets.create("benchmark") {
    java {
        setSrcDirs(listOf("src/benchmark/kotlin"))
    }
    resources {
        setSrcDirs(listOf("src/benchmark/resources"))
    }
    compileClasspath += sourceSets["main"].output
    runtimeClasspath += output + compileClasspath
}

configurations.named(benchmarkSourceSet.implementationConfigurationName) {
    extendsFrom(configurations["implementation"])
}

configurations.named(benchmarkSourceSet.runtimeOnlyConfigurationName) {
    extendsFrom(configurations["runtimeOnly"])
}

sourceSets {
    main {
        java {
            srcDirs("build/generated-src/antlr/main")
        }
    }
    test {
        compileClasspath += benchmarkSourceSet.output
        runtimeClasspath += benchmarkSourceSet.output
    }
}

// Ensure ANTLR generates parser before Kotlin compilation
tasks.named("compileKotlin") {
    dependsOn("generateGrammarSource")
}

tasks.named("compileBenchmarkKotlin") {
    dependsOn("classes")
}

// Gradle 8 strict mode: compileTestKotlin reads from generated antlr/test output dir
// even when there's no test grammar, so make the dependency explicit.
tasks.named("compileTestKotlin") {
    dependsOn("generateTestGrammarSource")
    dependsOn("compileBenchmarkKotlin")
}

tasks.register<JavaExec>("runBenchmarkSmoke") {
    group = "benchmark"
    description = "Runs the small black-box benchmark profile against local and reference ProcessM APIs."
    classpath = benchmarkSourceSet.runtimeClasspath
    mainClass.set("com.processm.processminterpreter.benchmark.BenchmarkRunnerKt")
    args("smoke")
}

tasks.register<JavaExec>("runBenchmarkFull") {
    group = "benchmark"
    description = "Runs the full black-box benchmark profile for thesis measurements."
    classpath = benchmarkSourceSet.runtimeClasspath
    mainClass.set("com.processm.processminterpreter.benchmark.BenchmarkRunnerKt")
    args("full")
}

tasks.register<JavaExec>("runBenchmarkCleanup") {
    group = "benchmark"
    description = "Deletes benchmark datastores with the bench- prefix from local and reference ProcessM APIs."
    classpath = benchmarkSourceSet.runtimeClasspath
    mainClass.set("com.processm.processminterpreter.benchmark.BenchmarkRunnerKt")
    args("cleanup")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed", "standardOut", "standardError")
        showStandardStreams = true
    }
    maxHeapSize = "4g"
}
