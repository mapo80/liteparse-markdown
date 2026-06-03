import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    `java-library`
    id("com.gradleup.shadow") version "8.3.5"
}

group = providers.gradleProperty("group").get()
version = providers.gradleProperty("version").get()

base { archivesName.set("liteparse-markdown") }

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

val liteparseJavaVersion = providers.gradleProperty("liteparseJavaVersion").get()

// Platform classifier for the liteparse-java native bundle used to run tests.
val osName = System.getProperty("os.name", "").lowercase()
val osArch = System.getProperty("os.arch", "").lowercase()
val classifier = run {
    val os = when {
        osName.contains("win") -> "windows"
        osName.contains("mac") || osName.contains("darwin") -> "macos"
        else -> "linux"
    }
    val arch = if (osArch.contains("aarch64") || osArch.contains("arm64")) "aarch64" else "x86_64"
    "$os-$arch"
}

repositories {
    mavenCentral()
    // Resolve liteparse-java straight from its GitHub Releases (no Maven Central).
    ivy {
        url = uri("https://github.com/mapo80/liteparse-java/releases/download")
        patternLayout { artifact("java-v[revision]/[module]-[revision](-[classifier]).jar") }
        metadataSources { artifact() }
        content { includeGroup("io.github.mapo80") }
    }
}

// During local development a bundle jar can be dropped in libs/ to avoid a published release.
val localBundle = file("libs/liteparse-java.jar")
val useLocalBundle = localBundle.exists()

dependencies {
    api("org.commonmark:commonmark:0.24.0")

    if (useLocalBundle) {
        // The self-contained bundle has the API classes + natives + (shaded) Jackson.
        compileOnly(files(localBundle))
        testImplementation(files(localBundle))
    } else {
        compileOnly("io.github.mapo80:liteparse-java-bundle:$liteparseJavaVersion:$classifier")
        testImplementation("io.github.mapo80:liteparse-java-bundle:$liteparseJavaVersion:$classifier")
    }

    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

// Self-contained "-all" jar: liteparse-markdown + commonmark (relocated). Consumers add this
// plus a liteparse-java bundle for their platform. liteparse-java is compileOnly, so its
// (platform-specific) classes/natives are NOT bundled here.
tasks.named<ShadowJar>("shadowJar") {
    archiveClassifier.set("all")
    relocate("org.commonmark", "io.liteparse.markdown.shaded.commonmark")
    mergeServiceFiles()
}
tasks.named("assemble") { dependsOn("shadowJar") }

// Run the CLI for manual checks: ./gradlew runCli -PcliArgs="document.pdf"
tasks.register<JavaExec>("runCli") {
    group = "application"
    description = "Parse a document with LiteParse and print Markdown."
    mainClass.set("io.liteparse.markdown.cli.Main")
    classpath = sourceSets["test"].runtimeClasspath
    val raw = (findProperty("cliArgs") as String?) ?: ""
    args = if (raw.isBlank()) emptyList() else raw.trim().split(Regex("\\s+"))
}
