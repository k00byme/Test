group = "app.k00byme"

patches {
    about {
        name = "Chess Assistant"
        description = "Stockfish move arrow, board-square highlights and evaluation for Chess.com bot games and standalone analysis."
        source = "https://github.com/k00byme/Revangetest/tree/morphe-chess-assistant/MorpheChessAssistant"
        author = "k00byme"
        contact = "GitHub issues"
        website = "https://github.com/k00byme/Revangetest"
        license = "GPLv3"
    }
}

val patchListGeneratorClasspath = configurations.create("patchListGeneratorClasspath")

dependencies {
    compileOnly(libs.gson)
    patchListGeneratorClasspath(libs.gson)
}

tasks {
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"
        dependsOn(build)
        classpath = sourceSets["main"].runtimeClasspath + patchListGeneratorClasspath
        mainClass.set("util.PatchListGeneratorKt")
    }

    publish {
        dependsOn("generatePatchesList")
    }
}
