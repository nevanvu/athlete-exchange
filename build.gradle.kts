plugins {
    id("org.springframework.boot") version "4.1.2" apply false
    id("io.spring.dependency-management") version "1.1.7" apply false
}

subprojects {
    apply(plugin = "java")

    group = "com.athletex"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    tasks.withType<JavaCompile>().configureEach {
        options.release.set(21)
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // Full exception chains in the console, so CI logs show why a test failed, not just where.
        testLogging {
            events("failed")
            exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            showCauses = true
            showStackTraces = true
        }
    }
}
