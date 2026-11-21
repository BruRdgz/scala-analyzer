plugins { application }
repositories { mavenCentral() }
tasks.withType<JavaCompile> { options.release.set(17) }
application { mainClass.set("demo.app.Main") }
dependencies {
    implementation(project(":core"))
    implementation("org.apache.commons:commons-lang3:3.14.0")
}
