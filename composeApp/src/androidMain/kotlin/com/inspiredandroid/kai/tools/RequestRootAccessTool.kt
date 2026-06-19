package com.inspiredandroid.kai.tools

import com.inspiredandroid.kai.TermuxShellExecutor
import com.inspiredandroid.kai.blockedRootCommandReason
import com.inspiredandroid.kai.network.tools.ParameterSchema
import com.inspiredandroid.kai.network.tools.Tool
import com.inspiredandroid.kai.network.tools.ToolInfo
import com.inspiredandroid.kai.network.tools.ToolSchema
import java.io.File
import java.time.Instant

private const val TERMUX_HOME = "/data/data/com.termux/files/home"
private const val ROOT_OUTPUT_LIMIT = 15_000

object RequestRootAccessTool : Tool {
    private val executor = TermuxShellExecutor()
    private val auditFile = File(TERMUX_HOME, ".karl9000/root_audit.log")

    override val schema = ToolSchema(
        name = "request_root_access",
        description = """Request one-shot audited root execution for a specific command in Termux. First call creates a native Kai approval prompt for the user. After the user approves in Kai, call this tool again with the returned approval_id. The approval is one-shot and only works for the exact staged command/scope. For frequent root use, ask the user to enable "Root shell access" in Settings or the chat top bar — then su/sudo/tsu will work directly in execute_shell_command.""",
        parameters = mapOf(
            "reason" to ParameterSchema("string", "Why root is required. Be specific and user-readable.", true),
            "command" to ParameterSchema("string", "The exact command to run as root after approval.", true),
            "scope" to ParameterSchema("string", "read-only or admin. Prefer read-only; admin is for changes.", true),
            "approval_id" to ParameterSchema("string", "Approval id returned by a previous request. Omit on the first call; include it after the user approves in Kai.", false),
            "timeout" to ParameterSchema("integer", "Timeout in seconds (default 30, max 180)", false),
        ),
    )

    override suspend fun execute(args: Map<String, Any>): Any {
        val reason = (args["reason"] as? String)?.trim()
            ?: return mapOf("success" to false, "error" to "reason is required")
        val command = (args["command"] as? String)?.trim()
            ?: return mapOf("success" to false, "error" to "command is required")
        val scope = (args["scope"] as? String)?.trim()?.lowercase()
            ?: return mapOf("success" to false, "error" to "scope is required")
        if (scope !in setOf("read-only", "admin")) {
            return mapOf("success" to false, "error" to "scope must be read-only or admin")
        }
        blockedRootCommandReason(command)?.let { reasonBlocked ->
            audit("blocked", null, scope, reason, command, reasonBlocked)
            return mapOf("success" to false, "blocked" to true, "error" to reasonBlocked)
        }

        val approvalId = (args["approval_id"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        return if (approvalId == null) {
            createPendingRequest(reason, command, scope)
        } else {
            executeApprovedRequest(approvalId, reason, command, scope, (args["timeout"] as? Number)?.toLong() ?: 30L)
        }
    }

    private fun createPendingRequest(reason: String, command: String, scope: String): Map<String, Any> {
        val remainingMs = RootApprovalRegistry.cooldownRemainingMs(reason, command, scope)
        if (remainingMs > 0L) {
            return mapOf(
                "success" to false,
                "denied_recently" to true,
                "retry_after_seconds" to ((remainingMs + 999L) / 1000L),
                "message" to "The user denied this root request recently. Do not ask again until the cooldown expires.",
            )
        }

        val request = RootApprovalRegistry.create(reason, command, scope)
        audit("pending", request.id, scope, reason, command, null)
        return mapOf(
            "success" to false,
            "requires_approval" to true,
            "approval_id" to request.id,
            "audit_log" to auditFile.absolutePath,
            "message" to "Root request is waiting for approval in Kai. After the user approves, call request_root_access again with this approval_id.",
        )
    }

    private fun executeApprovedRequest(
        id: String,
        reason: String,
        command: String,
        scope: String,
        timeoutSeconds: Long,
    ): Map<String, Any> {
        if (!safeId(id)) return mapOf("success" to false, "error" to "Invalid approval_id")
        val request = RootApprovalRegistry.get(id)
            ?: return mapOf("success" to false, "error" to "Root approval request does not exist. Create a fresh request.")

        if (request.denied) {
            RootApprovalRegistry.consume(id)
            audit("denied", id, request.scope, request.reason, request.command, null)
            return mapOf("success" to false, "denied" to true, "error" to "The user denied this root request.")
        }
        if (request.command != command || request.scope != scope || request.reason != reason) {
            audit("mismatch", id, scope, reason, command, "Approval id did not match staged request")
            return mapOf(
                "success" to false,
                "blocked" to true,
                "error" to "Approval id does not match the staged reason, command, and scope. Create a fresh root request.",
            )
        }
        if (!request.approved) {
            return mapOf(
                "success" to false,
                "requires_approval" to true,
                "approval_id" to id,
                "message" to "Root request is still waiting for the user to approve it in Kai.",
            )
        }

        RootApprovalRegistry.consume(id)
        val result = executor.executeApprovedRoot(command, timeoutSeconds)
        val success = result["success"] as? Boolean ?: false
        audit(if (success) "executed" else "failed", id, scope, reason, command, result["stderr"] as? String)
        return mapOf(
            "approval_id" to id,
            "scope" to scope,
            "audit_log" to auditFile.absolutePath,
        ) + result.mapValues { (_, value) ->
            if (value is String) value.take(ROOT_OUTPUT_LIMIT) else value
        }
    }

    private fun audit(
        action: String,
        id: String?,
        scope: String,
        reason: String,
        command: String,
        detail: String?,
    ) {
        auditFile.parentFile?.mkdirs()
        val line = buildString {
            append(Instant.now()).append('\t')
            append(action).append('\t')
            append(id ?: "-").append('\t')
            append(scope).append('\t')
            append(reason.replace('\n', ' ')).append('\t')
            append(command.replace('\n', ' '))
            if (!detail.isNullOrBlank()) append('\t').append(detail.replace('\n', ' ').take(500))
        }
        auditFile.appendText(line + "\n")
    }

    private fun safeId(id: String): Boolean = Regex("""[A-Za-z0-9_-]{8,64}""").matches(id)

    val toolInfo = ToolInfo(
        id = "request_root_access",
        name = "Request Root Access",
        description = "Request one-shot audited Termux root commands through a native approval prompt. For persistent root, use the root shell toggle in Settings or the chat top bar.",
        isEnabled = false,
    )
}
