plugins {
    `java-library`
    `maven-publish`
}

group = "io.github.okay-effects"
version = rootProject.version

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":"))
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // the plugin its own tests are compiled with: this module's classes, as a processor path
    testAnnotationProcessor(sourceSets.main.get().output)
    testAnnotationProcessor(project(":"))
}

// The plugin works on javac's own trees, which jdk.compiler does not export: to compile it, and — below, and in
// every build that uses it — to run javac with it.
val javacTrees = listOf("api", "code", "tree", "util").map {
    "--add-exports=jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED"
}

tasks.compileJava {
    options.compilerArgs.addAll(javacTrees + listOf("-Xlint:all", "-Werror"))
}

tasks.compileTestJava {
    options.compilerArgs.addAll(listOf("-Xplugin:OkayDirect", "-Xlint:all", "-Xlint:-processing", "-Werror"))
    options.isFork = true
    options.forkOptions.jvmArgs = (options.forkOptions.jvmArgs ?: emptyList()) + javacTrees
}

tasks.withType<Javadoc>().configureEach {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
    (options as StandardJavadocDocletOptions).addMultilineStringsOption("-add-exports").value =
        listOf("api", "code", "tree", "util").map { "jdk.compiler/com.sun.tools.javac.$it=ALL-UNNAMED" }
}

// src/test/negative: methods the plugin must refuse, each for the reason its first line names
tasks.test {
    useJUnitPlatform()
    jvmArgs(listOf("-Xss256k", "-Xmx64m") + javacTrees)
    inputs.dir("src/test/negative")
    systemProperty("negative", file("src/test/negative").path)
    systemProperty("plugin", sourceSets.main.get().output.asPath)
}

publishing {
    publications {
        create<MavenPublication>("okayTinyDirect") {
            artifactId = "okay-tiny-direct"
            from(components["java"])
            pom {
                name = "okay-tiny-direct"
                description = "Direct style for okay-tiny: a javac plugin that rewrites methods marked @Direct " +
                    "into chains of flatMap."
                url = "https://github.com/okay-effects/okay-tiny"
                licenses {
                    license {
                        name = "Apache-2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                    }
                }
                developers {
                    developer {
                        id = "sergey-scherbina"
                        name = "Sergiy Shcherbyna"
                    }
                }
                scm {
                    url = "https://github.com/okay-effects/okay-tiny"
                    connection = "scm:git:https://github.com/okay-effects/okay-tiny.git"
                }
            }
        }
    }
    repositories {
        maven {
            name = "pages"
            url = uri(rootProject.layout.buildDirectory.dir("pages-maven"))
        }
    }
}
