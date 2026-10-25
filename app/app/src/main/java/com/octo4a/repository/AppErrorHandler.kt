package com.octo4a.repository

data class AppError(
    val component: String,
    val operation: String,
    val message: String
) {
    companion object {
        fun from(component: String, operation: String, cause: Throwable): AppError {
            val reason = cause.localizedMessage
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: cause.javaClass.simpleName

            return AppError(component, operation, "$operation failed: $reason")
        }
    }
}

interface AppErrorHandler {
    fun report(component: String, operation: String, cause: Throwable): AppError
}

class AppErrorHandlerImpl(private val logger: LoggerRepository) : AppErrorHandler {
    override fun report(component: String, operation: String, cause: Throwable): AppError {
        val appError = AppError.from(component, operation, cause)
        logger.logError(component, appError.message, cause)
        return appError
    }
}
