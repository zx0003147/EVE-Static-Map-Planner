package dev.evestaticmapplanner.control.transport

import java.time.Duration
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal data class BoundedHttpExecutorSnapshot(
    val businessWorkerLimit: Int,
    val businessPendingLimit: Int,
    val businessPoolSize: Int,
    val businessLargestPoolSize: Int,
    val businessActiveCount: Int,
    val businessPendingCount: Int,
    val businessAdmissionAvailable: Int,
    val busyResponderLimit: Int,
    val busyPendingLimit: Int,
    val busyPoolSize: Int,
    val busyLargestPoolSize: Int,
    val busyActiveCount: Int,
    val busyPendingCount: Int,
)

/**
 * HttpServer calls [execute] from its dispatcher. Admitted exchanges run only on the fixed business pool.
 * Overflow exchanges run only on the fixed busy responder so the handler can return a structured 503.
 * Neither pool ever runs rejected work on its submitting thread.
 */
internal class BoundedHttpExecutor(
    instanceId: String,
    private val businessWorkerLimit: Int = LocalControlProtocol.HTTP_WORKER_COUNT,
    private val businessPendingLimit: Int = LocalControlProtocol.HTTP_QUEUE_CAPACITY,
    private val busyResponderLimit: Int = LocalControlProtocol.HTTP_BUSY_RESPONDER_COUNT,
    private val busyPendingLimit: Int = LocalControlProtocol.HTTP_BUSY_QUEUE_CAPACITY,
) : Executor {
    init {
        require(businessWorkerLimit > 0)
        require(businessPendingLimit > 0)
        require(busyResponderLimit > 0)
        require(busyPendingLimit > 0)
    }

    private val businessAdmission = Semaphore(
        businessWorkerLimit + businessPendingLimit,
        true,
    )
    private val business = fixedPool(
        workers = businessWorkerLimit,
        pending = businessPendingLimit,
        threadPrefix = "local-control-worker-${instanceId.take(8)}-",
    )
    private val busy = fixedPool(
        workers = busyResponderLimit,
        pending = busyPendingLimit,
        threadPrefix = "local-control-busy-${instanceId.take(8)}-",
    )

    override fun execute(command: Runnable) {
        if (businessAdmission.tryAcquire()) {
            try {
                business.execute {
                    try {
                        command.run()
                    } finally {
                        businessAdmission.release()
                    }
                }
            } catch (rejected: RejectedExecutionException) {
                businessAdmission.release()
                throw rejected
            }
        } else {
            busy.execute {
                BUSY_RESPONSE_TASK.set(true)
                try {
                    command.run()
                } finally {
                    BUSY_RESPONSE_TASK.remove()
                }
            }
        }
    }

    fun shutdown() {
        business.shutdown()
        busy.shutdown()
    }

    fun shutdownNow() {
        business.shutdownNow()
        busy.shutdownNow()
    }

    fun awaitTermination(timeout: Duration): Boolean {
        val deadline = System.nanoTime() + timeout.toNanos()
        if (!business.awaitTermination(timeout.toNanos(), TimeUnit.NANOSECONDS)) return false
        val remaining = (deadline - System.nanoTime()).coerceAtLeast(0L)
        return busy.awaitTermination(remaining, TimeUnit.NANOSECONDS)
    }

    val isTerminated: Boolean get() = business.isTerminated && busy.isTerminated

    fun snapshot(): BoundedHttpExecutorSnapshot = BoundedHttpExecutorSnapshot(
        businessWorkerLimit = businessWorkerLimit,
        businessPendingLimit = businessPendingLimit,
        businessPoolSize = business.poolSize,
        businessLargestPoolSize = business.largestPoolSize,
        businessActiveCount = business.activeCount,
        businessPendingCount = business.queue.size,
        businessAdmissionAvailable = businessAdmission.availablePermits(),
        busyResponderLimit = busyResponderLimit,
        busyPendingLimit = busyPendingLimit,
        busyPoolSize = busy.poolSize,
        busyLargestPoolSize = busy.largestPoolSize,
        busyActiveCount = busy.activeCount,
        busyPendingCount = busy.queue.size,
    )

    private fun fixedPool(workers: Int, pending: Int, threadPrefix: String): ThreadPoolExecutor {
        val sequence = AtomicInteger()
        return ThreadPoolExecutor(
            workers,
            workers,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(pending),
            { task -> Thread(task, threadPrefix + sequence.incrementAndGet()).apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy(),
        )
    }

    companion object {
        private val BUSY_RESPONSE_TASK = ThreadLocal<Boolean>()

        fun isBusyResponseTask(): Boolean = BUSY_RESPONSE_TASK.get() == true
    }
}
