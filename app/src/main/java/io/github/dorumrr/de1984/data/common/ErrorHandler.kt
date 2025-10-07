package io.github.dorumrr.de1984.data.common

import android.content.pm.PackageManager
import android.util.Log
import java.io.IOException

class ErrorHandler {
    
    companion object {
        private const val TAG = "De1984Error"
    }
    
    fun handleError(throwable: Throwable, operation: String): De1984Error {
        return when (throwable) {
            is java.lang.SecurityException -> De1984Error.PermissionDenied(
                message = "Permission denied for $operation",
                operation = operation,
                cause = throwable
            )
            
            is PackageManager.NameNotFoundException -> De1984Error.PackageNotFound(
                message = "Package not found during $operation",
                operation = operation,
                cause = throwable
            )
            
            is IOException -> De1984Error.SystemAccessFailed(
                message = "System access failed for $operation",
                operation = operation,
                cause = throwable
            )
            
            is IllegalStateException -> De1984Error.InvalidState(
                message = "Invalid state during $operation",
                operation = operation,
                cause = throwable
            )
            
            is UnsupportedOperationException -> De1984Error.OperationNotSupported(
                message = "Operation $operation is not supported on this device",
                operation = operation,
                cause = throwable
            )
            
            else -> De1984Error.Unknown(
                message = "Unknown error during $operation: ${throwable.message}",
                operation = operation,
                cause = throwable
            )
        }
    }
    
    fun createPermissionError(missingPermissions: List<String>, operation: String): De1984Error.PermissionDenied {
        return De1984Error.PermissionDenied(
            message = "Missing permissions for $operation: ${missingPermissions.joinToString(", ")}",
            operation = operation,
            missingPermissions = missingPermissions
        )
    }
    
    fun createRootRequiredError(operation: String): De1984Error.RootRequired {
        return De1984Error.RootRequired(
            message = "Root access required for $operation",
            operation = operation
        )
    }
    
    fun createUnsupportedDeviceError(operation: String, reason: String): De1984Error.UnsupportedDevice {
        return De1984Error.UnsupportedDevice(
            message = "Device not supported for $operation: $reason",
            operation = operation,
            reason = reason
        )
    }
}

sealed class De1984Error(
    override val message: String,
    open val operation: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {
    
    data class PermissionDenied(
        override val message: String,
        override val operation: String,
        override val cause: Throwable? = null,
        val missingPermissions: List<String> = emptyList()
    ) : De1984Error(message, operation, cause)
    
    data class PackageNotFound(
        override val message: String,
        override val operation: String,
        override val cause: Throwable? = null,
        val packageName: String? = null
    ) : De1984Error(message, operation, cause)
    
    data class SystemAccessFailed(
        override val message: String,
        override val operation: String,
        override val cause: Throwable? = null,
        val systemComponent: String? = null
    ) : De1984Error(message, operation, cause)
    
    data class InvalidState(
        override val message: String,
        override val operation: String,
        override val cause: Throwable? = null,
        val expectedState: String? = null,
        val actualState: String? = null
    ) : De1984Error(message, operation, cause)
    
    data class OperationNotSupported(
        override val message: String,
        override val operation: String,
        override val cause: Throwable? = null,
        val requiredCapability: String? = null
    ) : De1984Error(message, operation, cause)
    
    data class RootRequired(
        override val message: String,
        override val operation: String,
        override val cause: Throwable? = null
    ) : De1984Error(message, operation, cause)
    
    data class UnsupportedDevice(
        override val message: String,
        override val operation: String,
        override val cause: Throwable? = null,
        val reason: String? = null
    ) : De1984Error(message, operation, cause)
    
    data class NetworkError(
        override val message: String,
        override val operation: String,
        override val cause: Throwable? = null,
        val networkComponent: String? = null
    ) : De1984Error(message, operation, cause)
    
    data class Unknown(
        override val message: String,
        override val operation: String,
        override val cause: Throwable? = null
    ) : De1984Error(message, operation, cause)
    
    fun getUserMessage(): String {
        return when (this) {
            is PermissionDenied -> "Permission required: Please grant the necessary permissions in Settings."
            is PackageNotFound -> "App not found: The requested application could not be found."
            is SystemAccessFailed -> "System access failed: Unable to access system resources."
            is InvalidState -> "Invalid state: The operation cannot be performed in the current state."
            is OperationNotSupported -> "Not supported: This operation is not supported on your device."
            is RootRequired -> "Root required: This operation requires root access."
            is UnsupportedDevice -> "Device not supported: Your device doesn't support this feature."
            is NetworkError -> "Network error: Unable to access network information."
            is Unknown -> "Unknown error: An unexpected error occurred."
        }
    }
    
    fun getSuggestedAction(): String {
        return when (this) {
            is PermissionDenied -> "Go to Settings > Apps > De1984 > Permissions and grant the required permissions."
            is PackageNotFound -> "Make sure the application is installed and try again."
            is SystemAccessFailed -> "Try restarting the app or your device."
            is InvalidState -> "Try refreshing the data or restarting the app."
            is OperationNotSupported -> "This feature is not available on your device."
            is RootRequired -> "Root your device or use alternative features."
            is UnsupportedDevice -> "Consider using a different device or LineageOS version."
            is NetworkError -> "Check your network connection and try again."
            is Unknown -> "Try restarting the app. If the problem persists, please report it."
        }
    }
    
    fun isRecoverable(): Boolean {
        return when (this) {
            is PermissionDenied -> true
            is PackageNotFound -> false
            is SystemAccessFailed -> true
            is InvalidState -> true
            is OperationNotSupported -> false
            is RootRequired -> false
            is UnsupportedDevice -> false
            is NetworkError -> true
            is Unknown -> true
        }
    }
}
