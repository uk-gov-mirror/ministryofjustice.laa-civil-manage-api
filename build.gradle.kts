plugins {
    java
    jacoco
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.springdoc.openapi-gradle-plugin") version "1.9.0"
    id("com.diffplug.spotless") version "7.2.1"
    id("com.adarshr.test-logger") version "4.0.0"
    id("com.github.ben-manes.versions") version "0.54.0"
}

group = "uk.gov.justice"
version = "0.0.1-SNAPSHOT"
description = "Demo project for Spring Boot"

subprojects {
    apply(plugin = "java")
    apply(plugin = "io.spring.dependency-management")

    group = "uk.gov.justice"
    version = "0.0.1-SNAPSHOT"

    java {
        toolchain {
            languageVersion = JavaLanguageVersion.of(25)
        }
    }

    repositories {
        mavenCentral()
    }

    the<io.spring.gradle.dependencymanagement.dsl.DependencyManagementExtension>().imports {
        mavenBom("org.springframework.boot:spring-boot-dependencies:4.1.1")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}

// Temporary: override Spring Boot BOM's logback version to fix SNYK-JAVA-CHQOSLOGBACK-17675439
// (Expression Injection, High severity). Remove once Spring Boot ships with logback-core >= 1.5.36.
extra["logback.version"] = "1.5.36"

// Temporary: override Spring Boot BOM's Tomcat version to fix SNYK-JAVA-ORGAPACHETOMCATEMBED-17732890
// and SNYK-JAVA-ORGAPACHETOMCATEMBED-17733746. Remove once Spring Boot ships with tomcat-embed-core >= 11.0.23.
extra["tomcat.version"] = "11.0.23"

extra["commons-lang3.version"] = "3.18.0" // Fixes Uncontrolled Recursion (CVE-2025-48924)
extra["httpcore5.version"] = "5.4.3" // Fixes Header Parsing & HPACK Decoder DoS (CVE-2026-54428)
extra["httpclient5.version"] = "5.6.4" // Fixes Connection Leak DoS (CVE-2026-64607)
extra["log4j2.version"] = "2.25.5" // Fixes MapMessage JSON serialization (CVE-2026-49844)
extra["micrometer.version"] = "1.17.1" // Fixes Micrometer CRLF Injection (CVE-2026-59296)

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencyManagement {
    imports {
        // Fixes SNYK-JAVA-COMFASTERXMLJACKSONCORE-20059179 / -20059683
        mavenBom("com.fasterxml.jackson:jackson-bom:2.22.3")
        // Fixes SNYK-JAVA-TOOLSJACKSONCORE-20059180 / -20059682
        mavenBom("tools.jackson:jackson-bom:3.2.3")
    }
}

dependencies {
    implementation(project(":notify-integration"))
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")

    implementation("org.springframework.boot:spring-boot-starter-webmvc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("io.micrometer:micrometer-tracing-bridge-brave")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
    implementation("org.springframework.retry:spring-retry:2.0.13")
    implementation("org.aspectj:aspectjweaver")
    runtimeOnly("io.micrometer:micrometer-registry-prometheus")
    implementation("org.apache.tika:tika-core:3.3.2")
    compileOnly("org.projectlombok:lombok")
    developmentOnly("org.springframework.boot:spring-boot-devtools")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")

    testImplementation("io.rest-assured:spring-mock-mvc:6.0.1")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.wiremock:wiremock-standalone:3.13.2")

    implementation("org.springframework.boot:spring-boot-starter-web")
}

openApi {
    outputDir.set(layout.projectDirectory.dir("openApi"))
    groupedApiMappings.set(
        mapOf(
            "http://localhost:8080/v3/api-docs/laa-civil-manage-api" to "openApi.json",
        ),
    )
    customBootRun {
        args.set(
            listOf(
                "--spring.security.oauth2.client.registration.entra-obo-access-data-store.client-id=openapi-doc-gen",
                "--spring.security.oauth2.client.registration.entra-obo-access-data-store.client-secret=openapi-doc-gen",
                "--laa-civil-manage-api.provider-details.base-url=http://localhost:8080/openapi-doc-gen",
                "--laa-civil-manage-api.provider-details.api-key=openapi-doc-gen",
            ),
        )
    }
}

spotless {
    java {
        target("**src/*/java/**/*.java")
        googleJavaFormat("1.30.0")
    }

    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }

    format("misc") {
        target("*.md", "*.yml", "*.yaml")
        trimTrailingWhitespace()
        endWithNewline()
    }
}

tasks.register("verifyOpenApiSync") {
    dependsOn("generateOpenApiDocs")

    group = "verification"

    doLast {
        val openApiDir = file("openApi")

        val status =
            providers
                .exec {
                    commandLine("git", "status", "--porcelain", openApiDir.absolutePath)
                }.standardOutput.asText
                .get()
                .trim()

        if (status.isNotEmpty()) {
            throw GradleException(
                "ERROR: OpenAPI schemas are out of sync with your code changes!\n" +
                    "Files under 'openApi/' have changed. Please commit the updated versions (regenerate locally using ./gradlew generateOpenApiDocs).",
            )
        }

        println("OpenAPI sync verified: No changes detected.")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

testlogger {
    theme = com.adarshr.gradle.testlogger.theme.ThemeType.MOCHA
}

tasks.named<JacocoReport>("jacocoTestReport") {
    dependsOn(tasks.test)

    reports {
        xml.required.set(true)
        xml.outputLocation.set(layout.buildDirectory.file("reports/jacoco/jacoco.xml"))
        html.required.set(true)
        html.outputLocation.set(layout.buildDirectory.dir("reports/jacoco/html"))
    }
}

tasks.test {
    finalizedBy(tasks.named("jacocoTestReport"))
}
