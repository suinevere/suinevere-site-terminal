pluginManagement {
	// Versions live here so build.gradle.kts declares plugins by id alone.
	plugins {
		kotlin("jvm") version "2.3.21"
		kotlin("plugin.spring") version "2.3.21"
		id("org.springframework.boot") version "4.1.0"
		id("io.spring.dependency-management") version "1.1.7"
	}
}

plugins {
	id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "suinevere-site-terminal"
