package com.kzagent.kagent.todo

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val TODO_SCHEMA_VERSION = 1

@Serializable
enum class TodoStatus {
    @SerialName("pending")
    PENDING,

    @SerialName("completed")
    COMPLETED,
}

@Serializable
data class TodoItem(
    val id: String,
    val content: String,
    @SerialName("parent_id")
    val parentId: String? = null,
    val status: TodoStatus = TodoStatus.PENDING,
)

data class TodoSnapshot(
    val revision: Long = 0,
    val items: List<TodoItem> = emptyList(),
    val error: String? = null,
) {
    val hasIncomplete: Boolean get() = items.any { it.status == TodoStatus.PENDING }
    val leafItems: List<TodoItem>
        get() {
            val parentIds = items.mapNotNullTo(mutableSetOf()) { it.parentId }
            return items.filter { it.id !in parentIds }
        }
    val completedLeafCount: Int get() = leafItems.count { it.status == TodoStatus.COMPLETED }
    val totalLeafCount: Int get() = leafItems.size
}

data class TodoOperation(
    val type: Type,
    val id: String,
    val content: String? = null,
    val parentId: String? = null,
    val parentSpecified: Boolean = false,
    val status: TodoStatus? = null,
) {
    enum class Type { CREATE, UPDATE, SET_STATUS, DELETE }
}

@Serializable
private data class TodoDocument(
    @SerialName("schema_version")
    val schemaVersion: Int = TODO_SCHEMA_VERSION,
    val revision: Long = 0,
    val items: List<TodoItem> = emptyList(),
    @SerialName("turns_since_todo_tool")
    val turnsSinceTodoTool: Int = 0,
    @SerialName("turns_since_reminder")
    val turnsSinceReminder: Int = 0,
    @SerialName("has_reminded")
    val hasReminded: Boolean = false,
)

object TodoFiles {
    fun forSession(sessionFile: Path): Path =
        sessionFile.resolveSibling("${sessionFile.fileName}.todos.json")
}

/**
 * Session-scoped Todo state. Conversation history and context snapshots never
 * rewrite this sidecar, so task progress survives context compression.
 */
class TodoStore(
    private val path: Path,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val mutex = Mutex()
    private var document: TodoDocument?
    private val mutableState: MutableStateFlow<TodoSnapshot>
    val state: StateFlow<TodoSnapshot> get() = mutableState.asStateFlow()

    init {
        val loaded = loadDocument()
        document = loaded.first
        mutableState = MutableStateFlow(
            loaded.first?.toSnapshot() ?: TodoSnapshot(error = loaded.second),
        )
    }

    fun current(): TodoSnapshot = mutableState.value

    suspend fun clearIfAllCompleted(): Boolean = mutex.withLock {
        val current = document ?: return@withLock false
        if (current.items.isEmpty() || current.items.any { it.status != TodoStatus.COMPLETED }) {
            return@withLock false
        }
        val updated = current.copy(
            revision = current.revision + 1,
            items = emptyList(),
            turnsSinceTodoTool = 0,
            turnsSinceReminder = 0,
            hasReminded = false,
        )
        persist(updated)
        document = updated
        mutableState.value = updated.toSnapshot()
        true
    }

    suspend fun applyOperations(operations: List<TodoOperation>): TodoSnapshot = mutex.withLock {
        require(operations.isNotEmpty()) { "operations must not be empty." }
        val current = document ?: throw IllegalStateException(
            mutableState.value.error ?: "Todo state is unavailable.",
        )
        val items = current.items.toMutableList()

        for (operation in operations) {
            validateId(operation.id)
            when (operation.type) {
                TodoOperation.Type.CREATE -> {
                    require(items.none { it.id == operation.id }) {
                        "Todo id already exists: ${operation.id}"
                    }
                    val content = requireContent(operation.content)
                    operation.parentId?.let { parentId ->
                        require(items.any { it.id == parentId }) {
                            "Parent Todo does not exist: $parentId"
                        }
                    }
                    items += TodoItem(
                        id = operation.id,
                        content = content,
                        parentId = operation.parentId,
                    )
                }

                TodoOperation.Type.UPDATE -> {
                    val index = items.indexOfFirst { it.id == operation.id }
                    require(index >= 0) { "Todo does not exist: ${operation.id}" }
                    require(operation.content != null || operation.parentSpecified) {
                        "update requires content or parent_id."
                    }
                    val parentId = if (operation.parentSpecified) operation.parentId else items[index].parentId
                    require(parentId != operation.id) { "A Todo cannot be its own parent." }
                    parentId?.let {
                        require(items.any { item -> item.id == it }) {
                            "Parent Todo does not exist: $it"
                        }
                    }
                    val updated = items[index].copy(
                        content = operation.content?.let(::requireContent) ?: items[index].content,
                        parentId = parentId,
                    )
                    if (operation.parentSpecified && parentId != items[index].parentId) {
                        items.removeAt(index)
                        // Moving an item appends it to the new parent's ordered children.
                        items += updated
                    } else {
                        items[index] = updated
                    }
                }

                TodoOperation.Type.SET_STATUS -> {
                    require(items.any { it.id == operation.id }) {
                        "Todo does not exist: ${operation.id}"
                    }
                    val status = requireNotNull(operation.status) {
                        "set_status requires status."
                    }
                    val subtreeIds = subtreeIds(operation.id, items)
                    items.replaceAll { item ->
                        if (item.id in subtreeIds) item.copy(status = status) else item
                    }
                }

                TodoOperation.Type.DELETE -> {
                    require(items.any { it.id == operation.id }) {
                        "Todo does not exist: ${operation.id}"
                    }
                    val subtreeIds = subtreeIds(operation.id, items)
                    items.removeAll { it.id in subtreeIds }
                }
            }
            validateTree(items)
        }

        val normalized = normalizeParentStatuses(items)
        validateTree(normalized)
        // A model may try to signal "work started" by setting an already
        // pending item to pending again. Treat all semantic no-ops as reads so
        // they do not create misleading revisions or GUI refreshes.
        if (normalized == current.items) {
            return@withLock current.toSnapshot()
        }
        val updated = current.copy(
            revision = current.revision + 1,
            items = normalized,
            turnsSinceTodoTool = 0,
            turnsSinceReminder = 0,
            hasReminded = false,
        )
        persist(updated)
        document = updated
        updated.toSnapshot().also { mutableState.value = it }
    }

    fun shouldInjectReminder(): Boolean {
        val current = document ?: return false
        if (!current.toSnapshot().hasIncomplete) return false
        return current.turnsSinceTodoTool >= TODO_REMINDER_TURN_THRESHOLD &&
            (!current.hasReminded || current.turnsSinceReminder >= TODO_REMINDER_COOLDOWN_TURNS)
    }

    suspend fun recordAssistantTurn(
        todoToolCalled: Boolean,
        reminderInjected: Boolean,
    ) = mutex.withLock {
        val current = document ?: return@withLock
        val hasIncomplete = current.toSnapshot().hasIncomplete
        val updated = when {
            !hasIncomplete || todoToolCalled -> current.copy(
                turnsSinceTodoTool = 0,
                turnsSinceReminder = 0,
                hasReminded = false,
            )

            reminderInjected -> current.copy(
                turnsSinceTodoTool = current.turnsSinceTodoTool + 1,
                turnsSinceReminder = 1,
                hasReminded = true,
            )

            else -> current.copy(
                turnsSinceTodoTool = current.turnsSinceTodoTool + 1,
                turnsSinceReminder = if (current.hasReminded) {
                    current.turnsSinceReminder + 1
                } else {
                    0
                },
            )
        }
        if (updated != current && updated.items.isNotEmpty()) {
            persist(updated)
        }
        document = updated
    }

    private fun loadDocument(): Pair<TodoDocument?, String?> {
        if (!Files.exists(path)) return TodoDocument() to null
        return try {
            val loaded = json.decodeFromString<TodoDocument>(
                Files.readString(path, StandardCharsets.UTF_8),
            )
            require(loaded.schemaVersion == TODO_SCHEMA_VERSION) {
                "Unsupported Todo schema version: ${loaded.schemaVersion}"
            }
            validateTree(loaded.items)
            loaded.copy(items = normalizeParentStatuses(loaded.items)) to null
        } catch (error: Exception) {
            null to "Todo data could not be loaded from $path: ${error.message ?: error}"
        }
    }

    private suspend fun persist(value: TodoDocument) = withContext(Dispatchers.IO) {
        path.parent?.let(Files::createDirectories)
        val parent = path.parent ?: Path.of(".")
        val temporary = Files.createTempFile(parent, "${path.fileName}.", ".tmp")
        try {
            Files.writeString(temporary, json.encodeToString(value), StandardCharsets.UTF_8)
            try {
                Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun TodoDocument.toSnapshot(): TodoSnapshot =
        TodoSnapshot(revision = revision, items = items)

    private fun requireContent(value: String?): String {
        val content = value?.trim().orEmpty()
        require(content.isNotEmpty()) { "Todo content must not be blank." }
        require(content.length <= MAX_CONTENT_LENGTH) {
            "Todo content must not exceed $MAX_CONTENT_LENGTH characters."
        }
        return content
    }

    private fun validateId(id: String) {
        require(id.isNotBlank()) { "Todo id must not be blank." }
        require(id.length <= MAX_ID_LENGTH) {
            "Todo id must not exceed $MAX_ID_LENGTH characters."
        }
    }

    private fun validateTree(items: List<TodoItem>) {
        require(items.size <= MAX_ITEMS) { "Todo list must not exceed $MAX_ITEMS items." }
        require(items.map { it.id }.toSet().size == items.size) { "Todo ids must be unique." }
        val ids = items.mapTo(mutableSetOf()) { it.id }
        items.forEach { item ->
            validateId(item.id)
            requireContent(item.content)
            require(item.parentId == null || item.parentId in ids) {
                "Parent Todo does not exist: ${item.parentId}"
            }
            require(item.parentId != item.id) { "A Todo cannot be its own parent." }
        }
        val byId = items.associateBy { it.id }
        for (item in items) {
            val visited = mutableSetOf(item.id)
            var parentId = item.parentId
            while (parentId != null) {
                require(visited.add(parentId)) { "Todo hierarchy contains a cycle." }
                require(visited.size <= MAX_DEPTH) {
                    "Todo hierarchy must not exceed $MAX_DEPTH levels."
                }
                parentId = byId[parentId]?.parentId
            }
        }
    }

    private fun subtreeIds(rootId: String, items: List<TodoItem>): Set<String> {
        val children = items.groupBy { it.parentId }
        val result = linkedSetOf<String>()
        fun visit(id: String) {
            if (!result.add(id)) return
            children[id].orEmpty().forEach { visit(it.id) }
        }
        visit(rootId)
        return result
    }

    private fun normalizeParentStatuses(items: List<TodoItem>): List<TodoItem> {
        val children = items.groupBy { it.parentId }
        val byId = items.associateBy { it.id }
        val computed = mutableMapOf<String, TodoStatus>()

        fun statusOf(id: String): TodoStatus {
            computed[id]?.let { return it }
            val item = requireNotNull(byId[id])
            val childItems = children[id].orEmpty()
            val status = if (childItems.isEmpty()) {
                item.status
            } else if (childItems.all { statusOf(it.id) == TodoStatus.COMPLETED }) {
                TodoStatus.COMPLETED
            } else {
                TodoStatus.PENDING
            }
            computed[id] = status
            return status
        }

        return items.map { it.copy(status = statusOf(it.id)) }
    }

    companion object {
        const val TODO_REMINDER_TURN_THRESHOLD = 7
        const val TODO_REMINDER_COOLDOWN_TURNS = 4
        private const val MAX_ITEMS = 500
        private const val MAX_DEPTH = 20
        private const val MAX_CONTENT_LENGTH = 1_000
        private const val MAX_ID_LENGTH = 128
    }
}
