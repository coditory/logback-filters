plugins {
    id("build.java")
    id("build.test")
    id("build.coverage")
    id("build.publish")
}

dependencies {
    api(libs.logback.classic)
    testImplementation(libs.spock.core)
}
