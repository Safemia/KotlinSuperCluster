plugins {
    kotlin("jvm") version "2.0.0"
    `maven-publish`
}

group = "com.safemia"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(21)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            
            pom {
                name.set("Supercluster")
                description.set("A very fast geospatial point clustering library for Kotlin")
                url.set("https://github.com/mapbox/supercluster")
                
                licenses {
                    license {
                        name.set("ISC License")
                        url.set("https://opensource.org/licenses/ISC")
                    }
                }
            }
        }
    }
}