package io.github.dorumrr.de1984.data.common

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

enum class RootStatus {
    NOT_ROOTED,
    
    ROOTED_NO_PERMISSION,
    
    ROOTED_WITH_PERMISSION,
    
    CHECKING
}

@Singleton
class RootManager @Inject constructor() {

    private val _rootStatus = MutableStateFlow<RootStatus>(RootStatus.CHECKING)
    val rootStatus: StateFlow<RootStatus> = _rootStatus.asStateFlow()

    private var hasCheckedOnce = false

    val hasRootPermission: Boolean
        get() = _rootStatus.value == RootStatus.ROOTED_WITH_PERMISSION

    suspend fun checkRootStatus() {
        val currentStatus = _rootStatus.value
        if (hasCheckedOnce && (currentStatus == RootStatus.ROOTED_WITH_PERMISSION || currentStatus == RootStatus.NOT_ROOTED)) {
            return
        }

        if (!hasCheckedOnce) {
            _rootStatus.value = RootStatus.CHECKING
        }

        val newStatus = checkRootStatusInternal()
        _rootStatus.value = newStatus
        hasCheckedOnce = true
    }

    private suspend fun checkRootStatusInternal(): RootStatus = withContext(Dispatchers.IO) {
        try {
            val suCheck = Runtime.getRuntime().exec("which su")
            val suExists = suCheck.waitFor() == 0

            if (!suExists) {
                return@withContext RootStatus.NOT_ROOTED
            }

            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))

            val completed = kotlinx.coroutines.withTimeoutOrNull(3000) {
                process.waitFor()
            }

            if (completed == null) {
                process.destroy()
                return@withContext RootStatus.ROOTED_NO_PERMISSION
            }

            if (completed == 0) {
                val output = process.inputStream.bufferedReader().readText().trim()
                return@withContext if (output.contains("uid=0")) {
                    RootStatus.ROOTED_WITH_PERMISSION
                } else {
                    RootStatus.ROOTED_NO_PERMISSION
                }
            } else {
                return@withContext RootStatus.ROOTED_NO_PERMISSION
            }
        } catch (e: Exception) {
            return@withContext RootStatus.NOT_ROOTED
        }
    }
    
    suspend fun executeRootCommand(command: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        if (!hasRootPermission) {
            return@withContext Pair(-1, "No root permission")
        }
        
        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            val exitCode = process.waitFor()
            val output = process.inputStream.bufferedReader().readText().trim()
            Pair(exitCode, output)
        } catch (e: Exception) {
            Pair(-1, e.message ?: "Unknown error")
        }
    }
}

