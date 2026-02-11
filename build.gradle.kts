plugins {
	java
	id("org.springframework.boot") version "4.0.2"
	id("io.spring.dependency-management") version "1.1.7"
	id("com.google.cloud.tools.jib") version "3.4.0" // Jib Plugin
}

group = "live.overtake"
version = "0.0.1-SNAPSHOT"
description = "Demo project for Spring Boot"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(17)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("org.springframework.boot:spring-boot-starter-actuator")
	implementation("org.springframework.boot:spring-boot-starter-webmvc")

	implementation(platform("software.amazon.awssdk:bom:2.23.12"))
	implementation("software.amazon.awssdk:secretsmanager")
	implementation("software.amazon.awssdk:sts")

	testImplementation("org.springframework.boot:spring-boot-starter-actuator-test")
	testImplementation("org.springframework.boot:spring-boot-starter-webmvc-test")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

jib {
	from {
		image = "amazoncorretto:17-alpine-jdk"
	}
	to {
		// GitHub Actions 등 CI 환경에서 ECR_URL이 주입되면 해당 리포지토리로 설정
		// 로컬 환경에서는 ECR_URL이 없으므로 "ekssampleboot" (로컬 도커용 이름) 사용
		val ecrUrl = System.getenv("ECR_URL")
		val imageTag = System.getenv("IMAGE_TAG")

		if (ecrUrl != null) {
			image = "$ecrUrl/ekssampleboot"
			if (!imageTag.isNullOrEmpty()) {
				tags = setOf(imageTag)
			} else {
				tags = setOf("latest", "${project.version}")
			}
		} else {
			image = "ekssampleboot"
			tags = setOf("latest", "local")
		}
	}
	container {
		// 권장 방식: 문자열 이름("nobody") 대신 명시적인 Numeric UID:GID를 사용합니다.
		// 이유 1: 배포판마다 'nobody'의 이름이 다를 수 있어 호환성을 보장합니다.
		// 이유 2: Kubernetes의 'runAsNonRoot' 보안 정책 검사는 Numeric ID를 선호합니다.
		user = "65534:65534"
	}
	
	// MacOS의 경우 Docker 실행 경로를 명시적으로 지정 (CI/CD 환경인 Linux 등에서는 기본값 사용)
	if (System.getProperty("os.name").lowercase().contains("mac")) {
		dockerClient {
			executable = "/usr/local/bin/docker"
			environment = mapOf("PATH" to "/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin")
		}
	}
}

tasks.withType<Test> {
	useJUnitPlatform()
}
