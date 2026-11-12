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

tasks.register<JavaExec>("recordGame") {
    group = "application"
    description = "Writes the sample simulated game as NDJSON: --args=\"<out-file> <seed>\""
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.athletex.simulator.RecordGame")
    workingDir = rootProject.projectDir
}
