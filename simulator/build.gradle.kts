plugins {
    java
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

// NBA game simulator: deterministic event engine + a small Boot app that streams events to Kafka.
dependencies {
    implementation(project(":events"))
    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-kafka")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
