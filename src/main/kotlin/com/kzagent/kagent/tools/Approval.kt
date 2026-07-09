package com.kzagent.kagent.tools

interface ApprovalPolicy {
    fun approve(action: String, details: String): Boolean
}

object TerminalApprovalPolicy : ApprovalPolicy {
    override fun approve(action: String, details: String): Boolean {
        println()
        println("Approval required: $action")
        println(details)
        print("Allow this action? Type 'yes' to continue: ")
        val answer = readLine()?.trim()
        return answer == "yes"
    }
}

object AlwaysApprovePolicy : ApprovalPolicy {
    override fun approve(action: String, details: String): Boolean = true
}

object AlwaysDenyPolicy : ApprovalPolicy {
    override fun approve(action: String, details: String): Boolean = false
}

