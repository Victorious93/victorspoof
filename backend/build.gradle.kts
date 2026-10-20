plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("io.ktor.plugin")
    application
}

kotlin { jvmToolchain(17) }

application { mainClass.set("com.victorious.spoof.backend.ApplicationKt") }

val ktor = "2.3.12"
dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor")
    implementation("io.ktor:ktor-server-status-pages-jvm:$ktor")
    implementation("com.twilio.sdk:twilio:10.4.1")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("ch.qos.logback:logback-classic:1.5.8")
    testImplementation("io.ktor:ktor-server-test-host-jvm:$ktor")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:1.9.24")
}
