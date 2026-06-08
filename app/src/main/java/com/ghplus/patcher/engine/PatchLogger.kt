package com.ghplus.patcher.engine

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord

/**
 * Bridges the ReVanced patcher's java.util.logging output into the app log sink.
 *
 * Ported from ReVanced Manager v2.6.0
 * app/src/main/java/app/revanced/manager/patcher/logger/Logger.kt
 * (the `withJavaLogging` + Handler bridge). Simplified: a single string sink.
 */
class PatchLogger(private val sink: (String) -> Unit) {

    fun info(msg: String) = sink(msg)
    fun error(msg: String) = sink("ERROR: $msg")

    private val handler = object : Handler() {
        override fun publish(record: LogRecord) {
            val name = record.loggerName
            // Mirror Manager: only surface app/library logs, drop noisy framework logs.
            if (record.level.intValue() < Level.INFO.intValue()) return
            val prefix = when (record.level) {
                Level.SEVERE -> "ERROR: "
                Level.WARNING -> "WARN: "
                else -> ""
            }
            sink(prefix + record.message)
        }

        override fun flush() = Unit
        override fun close() = Unit
    }

    /**
     * Runs [block] with the patcher's root java.util.logging output routed to
     * this logger, restoring the previous configuration afterwards.
     * Mirrors Manager's `Logger.withJavaLogging`.
     */
    fun <T> withJavaLogging(block: () -> T): T {
        val root = java.util.logging.Logger.getLogger("")
        val previousLevel = root.level
        // Force INFO to avoid the library eagerly allocating TRACE LogRecords.
        root.level = Level.INFO
        val oldHandlers = root.handlers.toList()
        oldHandlers.forEach { root.removeHandler(it) }
        root.addHandler(handler)
        return try {
            block()
        } finally {
            root.removeHandler(handler)
            oldHandlers.forEach { root.addHandler(it) }
            root.level = previousLevel
        }
    }
}
