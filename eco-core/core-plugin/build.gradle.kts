group = "com.willfp"
version = rootProject.version

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")

    implementation("com.willfp:ecomponent:1.5.0")
    testImplementation("org.mockito:mockito-core:5.20.0")
    testImplementation("net.kyori:adventure-platform-bukkit:4.4.1")
    testImplementation("com.willfp:eco:${rootProject.property("eco-version")}")
    testImplementation("com.willfp:libreforge:${rootProject.property("libreforge-version")}:all") { isTransitive = false }
    testImplementation("io.papermc.paper:paper-api:1.21.8-R0.1-SNAPSHOT")
    testImplementation("com.h2database:h2:2.3.232")
    testImplementation("org.junit.jupiter:junit-jupiter:5.13.4")
    testRuntimeOnly("com.github.ben-manes.caffeine:caffeine:3.2.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    build {
        dependsOn(publishToMavenLocal)
    }

    test {
        useJUnitPlatform()
        systemProperty("libreforge.runtime.jar", providers.gradleProperty("libreforgeRuntimeJar").getOrElse(""))
    }

    compileTestKotlin {
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

publishing {
    publications {
        create<MavenPublication>("shadow") {
            from(components["java"])
            artifactId = rootProject.name
        }
    }

    publishing {
        repositories {
            maven {
                name = "Auxilor"
                url = uri("https://repo.auxilor.io/repository/maven-releases/")
                credentials {
                    username = System.getenv("MAVEN_USERNAME")
                    password = System.getenv("MAVEN_PASSWORD")
                }
            }
        }
    }
}

// Packaging-only entry guards retain the foreign final-XP bridge source unchanged.
val nativeGuardTools by sourceSets.creating {
    java.srcDir("src/nativeGuard/java")
}
dependencies {
    add(nativeGuardTools.implementationConfigurationName, "org.ow2.asm:asm-tree:9.8")
}
val guardedClasses = layout.buildDirectory.dir("native-guards")
val nativeOperationGuards by tasks.registering(JavaExec::class) {
    dependsOn(tasks.named("compileKotlin"), tasks.named(nativeGuardTools.classesTaskName))
    classpath = nativeGuardTools.runtimeClasspath
    mainClass.set("com.willfp.ecoskills.build.NativeOperationGuard")
    args(layout.buildDirectory.dir("classes/kotlin/main").get().asFile.absolutePath, guardedClasses.get().asFile.absolutePath)
    inputs.dir(layout.buildDirectory.dir("classes/kotlin/main"))
    outputs.dir(guardedClasses)
}
tasks.named<Test>("test") {
    dependsOn(nativeOperationGuards)
    classpath = files(guardedClasses) + classpath
}
tasks.named<Jar>("jar") {
    dependsOn(nativeOperationGuards)
    from(guardedClasses)
    eachFile {
        val originals = setOf("com/willfp/ecoskills/skills/SkillLevelMap.class", "com/willfp/ecoskills/util/LevelMap.class", "com/willfp/ecoskills/api/EcoSkillsAPI.class")
        if (relativePath.pathString in originals && file.toPath().startsWith(layout.buildDirectory.dir("classes/kotlin/main").get().asFile.toPath())) exclude()
    }
}

tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    dependsOn(nativeOperationGuards)
    from(guardedClasses)
    eachFile {
        val originals = setOf("com/willfp/ecoskills/skills/SkillLevelMap.class", "com/willfp/ecoskills/util/LevelMap.class", "com/willfp/ecoskills/api/EcoSkillsAPI.class")
        if (relativePath.pathString in originals && file.toPath().startsWith(layout.buildDirectory.dir("classes/kotlin/main").get().asFile.toPath())) exclude()
    }
}
