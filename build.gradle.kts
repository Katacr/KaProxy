plugins {
    java
    id("com.gradleup.shadow") version "8.3.2"
    id("xyz.jpenilla.run-velocity") version "2.3.1"
}

group = "org.katacr"
version = "1.0.2"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    annotationProcessor("com.velocitypowered:velocity-api:3.4.0-SNAPSHOT")
    compileOnly("net.md-5:bungeecord-api:1.21-R0.4")
    implementation("com.mysql:mysql-connector-j:8.4.0") {
        // 仅使用经典 JDBC，不需要 X DevAPI/protobuf
        exclude(group = "com.google.protobuf", module = "protobuf-java")
    }
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

    shadowJar {
        archiveClassifier.set("")
        // 重定位 JDBC 驱动，避免与代理或其它插件冲突
        relocate("com.mysql", "org.katacr.kaproxy.libs.mysql")
        mergeServiceFiles()
    }

    build {
        dependsOn(shadowJar)
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
