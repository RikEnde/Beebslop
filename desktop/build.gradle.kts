// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

plugins {
    kotlin("multiplatform")
}

// The desktop front end is JVM-only (Swing). All emulation lives
// in the platform-independent :bbc and :cpu modules.
kotlin {
    jvm()

    sourceSets {
        jvmMain.dependencies { implementation(project(":bbc")) }
        jvmTest.dependencies { implementation(kotlin("test")) }
    }
}

// The tests draw images but open no windows; headless AWT needs no display.
tasks.withType<Test>().configureEach {
    systemProperty("java.awt.headless", "true")
    systemProperty("beebslop.romDirectory", rootProject.file("roms").absolutePath)
}

val mainCompilation = kotlin.jvm().compilations.getByName("main")
val mainClassName = "beebslop.desktop.MainKt"

tasks.register<JavaExec>("run") {
    group = "application"
    description = "Open the emulator window. Pass options with --args=\"...\"."
    mainClass.set(mainClassName)
    classpath(mainCompilation.output.allOutputs)
    mainCompilation.runtimeDependencyFiles?.let { classpath(it) }
    workingDir = rootProject.projectDir
    jvmArgs("-Dapple.awt.application.name=Beebslop")
}

tasks.register<Jar>("fatJar") {
    group = "distribution"
    description = "Build a self-contained runnable jar in desktop/build/libs."
    archiveBaseName.set("beebslop")
    manifest { attributes("Main-Class" to mainClassName) }
    from(mainCompilation.output.allOutputs)
    from({ mainCompilation.runtimeDependencyFiles?.filter { it.name.endsWith(".jar") }?.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/**/module-info.class")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
