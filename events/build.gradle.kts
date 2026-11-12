import org.springframework.boot.gradle.plugin.SpringBootPlugin

plugins {
    `java-library`
    id("io.spring.dependency-management")
}

// Event envelope + payload types per docs/event-schema.md. Framework-free: only Jackson,
// pinned to the same version Spring Boot manages so app and simulator agree on the wire format.
dependencyManagement {
    imports {
        mavenBom(SpringBootPlugin.BOM_COORDINATES)
    }
}

dependencies {
    api("tools.jackson.core:jackson-databind")
    api("com.fasterxml.jackson.core:jackson-annotations")

    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
