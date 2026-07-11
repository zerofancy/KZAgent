package com.kzagent.kagent.tools

fun interface ApprovalPolicy {
    suspend fun approve(action: String, details: String): Boolean
}

object TerminalApprovalPolicy : ApprovalPolicy {
    override suspend fun approve(action: String, details: String): Boolean {
        println()
        println("Approval required: $action")
        println(details)
        print("Allow this action? Type 'yes' to continue: ")
        val answer = readlnOrNull()?.trim()
        return answer == "yes"
    }
}

object AlwaysApprovePolicy : ApprovalPolicy {
    override suspend fun approve(action: String, details: String): Boolean = true
}

object AlwaysDenyPolicy : ApprovalPolicy {
    override suspend fun approve(action: String, details: String): Boolean = false
}
