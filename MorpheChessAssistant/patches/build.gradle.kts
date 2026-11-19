group = "app.k00byme"

patches {
    about {
        name = "Chess Assistant"
        description = "Stockfish MultiPV suggestions (best, good and least favorable), labeled arrows and evaluation for Chess.com online games, bot games and analysis."
        source = "https://github.com/k00byme/Test/tree/chess-assistant-mirror/MorpheChessAssistant"
        author = "k00byme"
        contact = "GitHub issues"
        website = "https://github.com/k00byme/Test"
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
