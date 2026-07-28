plugins {
    java
    id("xyz.jpenilla.run-velocity") version "2.3.1"
}

group = "org.katacr"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks {
    compileJava {
        options.encoding = "UTF-8"
    }

    test {
        useJUnitPlatform()
    }

    processResources {
        val properties = mapOf("version" to version)
        inputs.properties(properties)
        filteringCharset = "UTF-8"
        filesMatching(listOf("bungee.yml")) {
            expand(properties)
        }
    }

    runVelocity {
        velocityVersion("3.4.0")
    }
}
