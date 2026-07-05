plugins {
    java
    application
    id("com.gradleup.shadow") version "8.3.5"
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

application {
    mainClass = "dev.estap.EstapApplication"
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.javalin:javalin:6.6.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.18.3")
    implementation("io.github.cdimascio:dotenv-java:3.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.16")
    implementation("com.knuddels:jtokkit:1.1.0")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.assertj:assertj-core:3.27.3")
    testImplementation("org.mockito:mockito-core:5.15.2")
    testImplementation("org.wiremock:wiremock:3.10.0")
    testImplementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
