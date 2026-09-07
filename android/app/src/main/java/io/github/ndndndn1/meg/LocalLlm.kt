package io.github.ndndndn1.meg
class LocalLlm {
    companion object {
        init {
            System.loadLibrary("meg")
        }
    }

    external fun load(path: String)

    external fun generate(prompt: String): String

    external fun close()

    external fun cancel()
}
