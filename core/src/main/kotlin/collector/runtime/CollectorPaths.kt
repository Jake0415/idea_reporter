package collector.runtime

import java.io.File

data class CollectorPaths(
    val outDir: File,
    val configDir: File,
    val cacheDir: File = outDir.resolve("cache"),
    val debugDir: File = outDir.resolve("debug"),
    val envFile: File = File(configDir.parentFile, ".env")
) {

    val promptsDir: File get() = configDir.resolve("prompts")
    val pipelineConfig: File get() = configDir.resolve("pipeline.local.json")
    val sourcesConfig: File get() = configDir.resolve("sources.local.json")
    val analysisConfig: File get() = configDir.resolve("analysis.local.json")

    companion object {
        fun workingDirectory() = CollectorPaths(File("out"), File("config"))
    }
}
