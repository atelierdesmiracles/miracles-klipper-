package com.octo4a.repository

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

enum class LogType {
    SYSTEM,
    BOOTSTRAP,
    OCTOPRINT,
    EXTENSION,
    OTHER
}

enum class LogLevel {
    INFO,
    ERROR
}

data class LogEntry(
    val entry: String,
    val type: LogType = LogType.SYSTEM,
    val level: LogLevel = LogLevel.INFO
)

fun LogType.getTypeEmoji(): String {
    return when (this) {
        LogType.BOOTSTRAP -> {
            "\uD83D\uDC38"
        }
        LogType.OCTOPRINT -> {
            "\uD83D\uDC19"
        }
        LogType.EXTENSION -> {
            "\uD83D\uDD0C"
        }
        LogType.SYSTEM -> {
            "\uD83D\uDCBB"
        }
        else ->"❓"
    }
}

interface LoggerRepository {
    val logHistoryFlow: SharedFlow<LogEntry>
    fun log(obj: Any? = null, type: LogType = LogType.SYSTEM, getMessage: () -> String)
    fun logError(obj: Any? = null, message: String, throwable: Throwable? = null)
}

class LoggerRepositoryImpl : LoggerRepository {
    private val _logHistoryFlow = MutableSharedFlow<LogEntry>(replay = 300, extraBufferCapacity = 1000)

    override val logHistoryFlow: SharedFlow<LogEntry>
        get() = _logHistoryFlow

    override fun log(obj: Any?, type: LogType, getMessage: () -> String) {
        val tag = (obj as? String)?.takeIf { it.isNotBlank() }
            ?: obj?.javaClass?.simpleName?.takeIf { it.isNotBlank() }
            ?: "Octo4a"
        val message = getMessage()
        Log.d(tag, message)
        _logHistoryFlow.tryEmit(LogEntry(message, type))
    }

    override fun logError(obj: Any?, message: String, throwable: Throwable?) {
        val tag = (obj as? String)?.takeIf { it.isNotBlank() }
            ?: obj?.javaClass?.simpleName?.takeIf { it.isNotBlank() }
            ?: "Octo4a"
        Log.e(tag, message, throwable)
        _logHistoryFlow.tryEmit(LogEntry(message, LogType.SYSTEM, LogLevel.ERROR))
    }
}
