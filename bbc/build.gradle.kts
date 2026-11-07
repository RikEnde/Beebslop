// SPDX-FileCopyrightText: 2026 The Beebslop contributors
// SPDX-License-Identifier: BSD-3-Clause

plugins {
    kotlin("multiplatform")
}

kotlin {
    jvm()
    macosArm64()
    macosX64()
    linuxArm64()
    linuxX64()
    mingwX64()

    sourceSets {
        commonMain.dependencies { api(project(":cpu")) }
        commonTest.dependencies { implementation(kotlin("test")) }
    }
}

// ROMs and tapes remain local inputs, never resources packaged into the library.
tasks.withType<Test>().configureEach {
    systemProperty("beebslop.romDirectory", rootProject.file("roms").absolutePath)
    systemProperty("beebslop.tapeDirectory", rootProject.file("tapes").absolutePath)
    inputs.files(rootProject.file("roms/os.rom"), rootProject.file("roms/basic.rom"))
        .withPropertyName("bbcRoms")
    inputs.dir(rootProject.file("tapes")).withPropertyName("tapes")
    testLogging { events("passed", "skipped", "failed") }
}
