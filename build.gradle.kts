import org.gradle.language.jvm.tasks.ProcessResources

plugins {
	kotlin("jvm")
	kotlin("plugin.spring")
	id("org.springframework.boot")
	id("io.spring.dependency-management")
}

group = "org.suinevere.site.terminal"
version = "0.0.1-SNAPSHOT"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(25)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-rsocket")
	implementation("org.springframework.boot:spring-boot-starter-webflux")
	implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
	implementation("io.projectreactor.kotlin:reactor-kotlin-extensions")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
	testImplementation("org.springframework.boot:spring-boot-starter-rsocket-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test")
	testImplementation("io.projectreactor:reactor-test")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
	testImplementation("org.springframework.boot:spring-boot-starter-security-test")
	testImplementation("org.springframework.security:spring-security-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
	compilerOptions {
		freeCompilerArgs.addAll("-Xjsr305=strict", "-Xannotation-default-target=param-property")
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}

val frontendDir = layout.projectDirectory.dir("frontend")
val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"

val npmInstall by tasks.registering(Exec::class) {
	description = "Installs frontend dependencies from the lockfile."
	workingDir = frontendDir.asFile
	commandLine(npmCommand, "ci")
	inputs.file(frontendDir.file("package-lock.json"))
	outputs.dir(frontendDir.dir("node_modules"))
}

val npmBuild by tasks.registering(Exec::class) {
	description = "Builds the production frontend bundle."
	dependsOn(npmInstall)
	workingDir = frontendDir.asFile
	commandLine(npmCommand, "run", "build")
	inputs.dir(frontendDir.dir("src"))
	inputs.file(frontendDir.file("package.json"))
	inputs.file(frontendDir.file("package-lock.json"))
	inputs.file(frontendDir.file("tsconfig.json"))
	inputs.file(frontendDir.file("vite.config.ts"))
	inputs.file(frontendDir.file("index.html"))
	outputs.dir(frontendDir.dir("dist"))
}

val npmTest by tasks.registering(Exec::class) {
	description = "Runs the frontend unit tests."
	dependsOn(npmInstall)
	workingDir = frontendDir.asFile
	commandLine(npmCommand, "test")
	inputs.dir(frontendDir.dir("src"))
	inputs.file(frontendDir.file("package.json"))
	inputs.file(frontendDir.file("package-lock.json"))
	inputs.file(frontendDir.file("tsconfig.json"))
	inputs.file(frontendDir.file("vite.config.ts"))
	outputs.upToDateWhen { false }
}

tasks.named("check") {
	dependsOn(npmTest)
}

tasks.named<ProcessResources>("processResources") {
	dependsOn(npmBuild)
	from(frontendDir.dir("dist")) {
		into("static")
	}
}
