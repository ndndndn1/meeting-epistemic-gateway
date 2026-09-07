package io.github.ndndndn1.meg

import java.util.concurrent.*
import org.json.JSONArray
import org.json.JSONObject

enum class ExecutionMode(val label: String) {
    AUTO("자동"),
    LOCAL_ONLY("로컬만"),
    OPENROUTER_FIRST("OpenRouter 우선"),
}

class AnalysisRouting(private val budgetMs: Long = 5000) {
    private val localQueue = Executors.newSingleThreadExecutor()
    private val recent = ArrayDeque<Long>()
    @Volatile private var generation = 0

    fun cancel() {
        generation++
    }

    fun close() {
        cancel()
        localQueue.shutdownNow()
    }

    fun predict(ready: Boolean, tokens: Int, queue: Int = 0): Long =
        if (!ready) Long.MAX_VALUE
        else ((recent.maxOrNull() ?: 7500L) * maxOf(1.0, tokens / 1800.0)).toLong() + queue * 5000L

    fun <T> run(
        mode: ExecutionMode,
        available: Boolean,
        predicted: Long,
        attempts: JSONArray,
        local: () -> T,
        cancelLocal: () -> Unit,
        remote: () -> T,
        warning: (String) -> Unit,
    ): T {
        val current = generation
        fun checkActive() {
            check(current == generation) { "검증 취소" }
        }
        fun attempt(route: String, reason: String, block: () -> T): T {
            checkActive()
            val at = System.currentTimeMillis()
            val record = JSONObject().put("route", route).put("reason", reason).put("startedAt", at)
            try {
                val result = block()
                checkActive()
                record.put("outcome", "completed")
                return result
            } catch (e: Exception) {
                record.put(
                    "outcome",
                    if (e is TimeoutException) "로컬 5초 예산 초과 · 취소"
                    else e.cause?.message ?: e.message ?: "검증 실패",
                )
                throw e
            } finally {
                val elapsed = System.currentTimeMillis() - at
                record.put("durationMs", elapsed)
                attempts.put(record)
                if (route == "LOCAL") {
                    recent.add(
                        if (record.optString("outcome") == "completed") elapsed
                        else maxOf(7500, elapsed)
                    )
                    if (recent.size > 5) recent.removeFirst()
                }
            }
        }
        var cloudTried = false
        if (mode != ExecutionMode.LOCAL_ONLY && !available)
            warning("OpenRouter 계정 연결과 모델 선택이 필요합니다. 로컬 처리를 유지합니다.")
        if (
            available &&
                (mode == ExecutionMode.OPENROUTER_FIRST ||
                    mode == ExecutionMode.AUTO && predicted > budgetMs)
        ) {
            cloudTried = true
            try {
                return attempt(
                    "OPENROUTER",
                    if (mode == ExecutionMode.AUTO) "예상 지연 5초 초과" else "OpenRouter 우선 선택",
                    remote,
                )
            } catch (e: Exception) {
                checkActive()
                warning("${e.message} · 로컬 검증으로 전환")
            }
        }
        try {
            return attempt("LOCAL", if (cloudTried) "OpenRouter 오류 후 로컬" else "로컬 실행") {
                val task = localQueue.submit(Callable { local() })
                try {
                    if (mode == ExecutionMode.AUTO && available && !cloudTried)
                        task.get(budgetMs, TimeUnit.MILLISECONDS)
                    else task.get()
                } catch (e: TimeoutException) {
                    cancelLocal()
                    task.cancel(true)
                    throw e
                }
            }
        } catch (e: TimeoutException) {
            checkActive()
            return attempt("OPENROUTER", "로컬 5초 초과 후 취소·전환", remote)
        }
    }
}
