package com.flowcut.app.engine.export

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ExportFrameTimeoutException(val frameIndex: Int) :
    Exception("Decoder frame $frameIndex par timeout")

/** Export frame ka "naya frame aaya" signal. Late signal agle frame me leak na ho, isliye arm() pehle. */
class FrameGate {
    private val lock = ReentrantLock()
    private val cond = lock.newCondition()
    private var available = false

    /** Decoder ko frame render karne se PEHLE. */
    fun arm() = lock.withLock {
        available = false
    }

    /** SurfaceTexture.OnFrameAvailableListener se. */
    fun signal() = lock.withLock {
        available = true
        cond.signalAll()
    }

    /** true = frame aaya, false = timeout. */
    fun await(timeoutMs: Long): Boolean = lock.withLock {
        var nanos = TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (!available) {
            if (nanos <= 0L) return@withLock false
            nanos = cond.awaitNanos(nanos)
        }
        available = false
        true
    }
}
