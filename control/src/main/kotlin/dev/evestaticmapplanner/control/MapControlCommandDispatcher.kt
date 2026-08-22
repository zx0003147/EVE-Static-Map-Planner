package dev.evestaticmapplanner.control

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

sealed interface DispatchOutcome<out T> {
    data class Completed<T>(val value: T) : DispatchOutcome<T>
    data object QueueFull : DispatchOutcome<Nothing>
}

class MapControlCommandDispatcher(
    scope: CoroutineScope,
    capacity: Int = ControlLimits.COMMAND_QUEUE_CAPACITY,
) : AutoCloseable {
    private val commands = Channel<QueuedCommand<*>>(capacity)
    private val consumer = scope.launch {
        for (queued in commands) queued.runIfActive()
    }

    suspend fun <T> dispatch(block: suspend () -> T): DispatchOutcome<T> {
        val result = CompletableDeferred<T>()
        val queued = QueuedCommand(result, block)
        if (commands.trySend(queued).isFailure) return DispatchOutcome.QueueFull
        return try {
            DispatchOutcome.Completed(result.await())
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            result.cancel(cancelled)
            throw cancelled
        }
    }

    override fun close() {
        commands.close()
        consumer.cancel()
    }

    private class QueuedCommand<T>(
        private val result: CompletableDeferred<T>,
        private val block: suspend () -> T,
    ) {
        suspend fun runIfActive() {
            if (!result.isActive) return
            runCatching { block() }
                .onSuccess { result.complete(it) }
                .onFailure { result.completeExceptionally(it) }
        }
    }
}
