plugins {
    `java-library`
}

group = "io.github.okay-effects"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

// the kernel has no dependency; these are the tests' and the benchmark's
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-Werror"))
}

// src/test/negative: programs javac must refuse (NegativeTest compiles each and reads the refusal)
tasks.test {
    useJUnitPlatform()
    jvmArgs("-Xss256k", "-Xmx64m")       // the machine is a loop: a long program takes neither stack nor heap
    inputs.dir("src/test/negative")
    systemProperty("negative", file("src/test/negative").path)
}

// ./gradlew jmh [-Pbench=StateBench.counter]: the benchmarks of src/jmh, over the tests' Shop
val jmh: SourceSet by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
}

dependencies {
    "jmhImplementation"("org.openjdk.jmh:jmh-core:1.37")
    "jmhAnnotationProcessor"("org.openjdk.jmh:jmh-generator-annprocess:1.37")
}

tasks.named<JavaCompile>("compileJmhJava") {
    options.compilerArgs.remove("-Werror")
}

tasks.register<JavaExec>("jmh") {
    group = "verification"
    description = "Runs the JMH benchmarks: ns an operation."
    classpath = jmh.runtimeClasspath
    mainClass = "org.openjdk.jmh.Main"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    args((findProperty("bench") as String?) ?: "StateBench")
}
