package com.nikro.nexusssh.data.repository

import com.nikro.nexusssh.data.local.HistoryDao
import com.nikro.nexusssh.data.local.PortForwardDao
import com.nikro.nexusssh.data.local.SnippetDao
import com.nikro.nexusssh.data.local.toDomain
import com.nikro.nexusssh.data.local.toEntity
import com.nikro.nexusssh.domain.model.ConnectionHistoryEntry
import com.nikro.nexusssh.domain.model.ForwardType
import com.nikro.nexusssh.domain.model.PortForwardRule
import com.nikro.nexusssh.domain.model.Snippet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Reusable command snippets, with a small set seeded on first launch. */
@Singleton
class SnippetRepository @Inject constructor(
    private val snippetDao: SnippetDao,
) {

    fun observeAll(): Flow<List<Snippet>> =
        snippetDao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeForTag(tag: String): Flow<List<Snippet>> =
        observeAll().map { snippets -> snippets.filter { tag in it.tags } }

    suspend fun all(): List<Snippet> = snippetDao.getAll().map { it.toDomain() }

    suspend fun snippet(id: Long): Snippet? = snippetDao.findById(id)?.toDomain()

    suspend fun save(snippet: Snippet): Long = snippetDao.upsert(snippet.toEntity())

    suspend fun saveAll(snippets: List<Snippet>) = snippetDao.upsertAll(snippets.map { it.toEntity() })

    suspend fun delete(id: Long) = snippetDao.deleteById(id)

    /** Populates the library the first time the app runs so the screen is never empty. */
    suspend fun seedDefaultsIfEmpty() {
        if (snippetDao.getAll().isNotEmpty()) return
        saveAll(defaults())
    }

    private fun defaults(): List<Snippet> = listOf(
        Snippet(
            name = "System summary",
            script = "uname -a && uptime && free -h && df -h /",
            description = "Kernel, load, memory and root disk usage",
            tags = listOf("system"),
        ),
        Snippet(
            name = "Top processes by memory",
            script = "ps -eo pid,ppid,user,%mem,%cpu,cmd --sort=-%mem | head -n 15",
            description = "The 15 hungriest processes",
            tags = listOf("system"),
        ),
        Snippet(
            name = "Listening ports",
            script = "ss -tulpn 2>/dev/null || netstat -tulpn",
            description = "Every socket in LISTEN state",
            tags = listOf("network"),
        ),
        Snippet(
            name = "Tail a log",
            script = "tail -f -n 200 \${path}",
            description = "Follow any file; asks for the path",
            tags = listOf("logs"),
        ),
        Snippet(
            name = "Docker overview",
            script = "docker ps --format 'table {{.Names}}\\t{{.Status}}\\t{{.Ports}}' && docker stats --no-stream",
            description = "Running containers and their resource use",
            tags = listOf("docker"),
        ),
        Snippet(
            name = "Restart a service",
            script = "sudo systemctl restart \${service} && sudo systemctl status \${service} --no-pager",
            description = "Restart and show status; asks for the unit name",
            tags = listOf("systemd"),
        ),
        Snippet(
            name = "Nginx config test",
            script = "sudo nginx -t && sudo systemctl reload nginx",
            description = "Validate then reload nginx",
            tags = listOf("web"),
        ),
        Snippet(
            name = "Failed SSH logins",
            script = "sudo journalctl -u ssh -u sshd --since '24 hours ago' | grep -i 'failed\\|invalid' | tail -n 40",
            description = "Recent authentication failures",
            tags = listOf("security"),
        ),
        Snippet(
            name = "Package updates",
            script = "if command -v apt >/dev/null; then sudo apt update && apt list --upgradable; " +
                "elif command -v dnf >/dev/null; then sudo dnf check-update; " +
                "else sudo pacman -Sy && pacman -Qu; fi",
            description = "Works on Debian, RHEL and Arch families",
            tags = listOf("packages"),
        ),
        Snippet(
            name = "Largest files here",
            script = "du -ah . 2>/dev/null | sort -rh | head -n 20",
            description = "Find what is filling the current directory",
            tags = listOf("disk"),
        ),
    )
}

/** Saved port-forwarding rules. */
@Singleton
class PortForwardRepository @Inject constructor(
    private val dao: PortForwardDao,
) {

    fun observeAll(): Flow<List<PortForwardRule>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    fun observeForHost(hostId: Long): Flow<List<PortForwardRule>> =
        dao.observeForHost(hostId).map { list -> list.map { it.toDomain() } }

    suspend fun all(): List<PortForwardRule> = dao.getAll().map { it.toDomain() }

    suspend fun rule(id: Long): PortForwardRule? = dao.findById(id)?.toDomain()

    /** Rules that should come up automatically when their host connects. */
    suspend fun autoStartRules(): List<PortForwardRule> = dao.getAutoStart().map { it.toDomain() }

    suspend fun autoStartFor(hostId: Long): List<PortForwardRule> =
        autoStartRules().filter { it.hostId == hostId }

    suspend fun save(rule: PortForwardRule): Long = dao.upsert(rule.toEntity())

    suspend fun saveAll(rules: List<PortForwardRule>) = dao.upsertAll(rules.map { it.toEntity() })

    suspend fun delete(id: Long) = dao.deleteById(id)

    suspend fun deleteForHost(hostId: Long) = dao.deleteForHost(hostId)

    /** Rejects rules that cannot work before the user hits connect. */
    fun validate(rule: PortForwardRule): String? = when {
        rule.localPort !in 1..65535 && rule.type != ForwardType.REMOTE ->
            "The local port must be between 1 and 65535"

        rule.localPort in 1..1023 && rule.type != ForwardType.REMOTE ->
            "Ports below 1024 cannot be bound without root on Android"

        rule.type != ForwardType.DYNAMIC && rule.remoteHost.isBlank() ->
            "Enter the destination host"

        rule.type != ForwardType.DYNAMIC && rule.remotePort !in 1..65535 ->
            "The destination port must be between 1 and 65535"

        else -> null
    }
}

/** Connection log: one row per session, finished when the session ends. */
@Singleton
class HistoryRepository @Inject constructor(
    private val dao: HistoryDao,
) {

    fun observeRecent(limit: Int = 200): Flow<List<ConnectionHistoryEntry>> =
        dao.observeRecent(limit).map { list -> list.map { it.toDomain() } }

    fun observeForHost(hostId: Long): Flow<List<ConnectionHistoryEntry>> =
        dao.observeForHost(hostId).map { list -> list.map { it.toDomain() } }

    /** Records the start of a session; the returned id is passed back to [finish]. */
    suspend fun start(hostId: Long?, label: String, address: String): Long =
        dao.insert(
            ConnectionHistoryEntry(
                hostId = hostId,
                label = label,
                address = address,
                startedAt = System.currentTimeMillis(),
            ).toEntity(),
        )

    suspend fun finish(
        id: Long,
        bytesIn: Long,
        bytesOut: Long,
        succeeded: Boolean,
        error: String? = null,
    ) = dao.finish(
        id = id,
        endedAt = System.currentTimeMillis(),
        bytesIn = bytesIn,
        bytesOut = bytesOut,
        succeeded = succeeded,
        error = error,
    )

    suspend fun clear() = dao.clear()

    /** Keeps the log from growing forever; called on app start. */
    suspend fun prune(keepDays: Int = 90) =
        dao.pruneOlderThan(System.currentTimeMillis() - keepDays * 24L * 60 * 60 * 1000)

    suspend fun recentFailures(hours: Int = 24): Int =
        dao.failureCountSince(System.currentTimeMillis() - hours * 60L * 60 * 1000)
}
