package io.github.ndndndn1.meg

data class Policy(val mode: String = "NORMAL", val unsupported: Int = 0, val calibrated: Int = 0) {
    fun next(status: String): Policy =
        when (status) {
            "UNSUPPORTED ASSERTION" ->
                Policy(if (unsupported + 1 >= 3) "EVIDENCE REQUIRED" else mode, unsupported + 1, 0)
            "VERIFIED",
            "EXPERIENCE",
            "HYPOTHESIS" -> Policy(if (calibrated + 1 >= 3) "NORMAL" else mode, 0, calibrated + 1)
            "CONTRADICTED" -> copy(unsupported = 0, calibrated = 0)
            else -> copy()
        }
}
