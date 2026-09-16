plugins {
    java
    application
}

group = "strix"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "strix.uci.Main"
}

// Per-commit tests. Fast. Excludes the depth-6 perft runs.
tasks.test {
    useJUnitPlatform {
        excludeTags("deep")
    }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// Nightly. Depth 6 is 119M nodes from the start position alone.
// A registered Test task does NOT inherit the test source set. Without these two
// lines it runs zero tests and still reports BUILD SUCCESSFUL.
tasks.register<Test>("perftDeep") {
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("deep")
    }
    testLogging {
        events("passed", "failed")
        showStandardStreams = true
    }
}
