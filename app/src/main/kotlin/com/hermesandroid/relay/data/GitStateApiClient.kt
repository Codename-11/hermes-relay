package com.hermesandroid.relay.data

import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.network.upstream.DashboardHttpException
import com.hermesandroid.relay.plugins.runtime.ScopedPluginApiClient
import java.io.IOException
import java.net.URLEncoder
import java.util.Locale
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

private fun pathsArray(paths: List<String>) = buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } }

/**
 * Uses official Dashboard `/api/git/…` reads for the active session repository.
 * Relay remains the discovery source and owns stronger write/preview extensions.
 * Operational upstream failures are never hidden by a Relay retry; only a 404
 * can fall back to a matching Relay-discovered repository.
 */
class GitStateApiClient(
    private val dashboard: DashboardApiClient,
) {
    private val scoped = ScopedPluginApiClient("hermes-relay", dashboard)
    private val json = Json { ignoreUnknownKeys = true }
    private val reposById = linkedMapOf<String, GitRepo>()
    private val relayRepoIdsByRoot = linkedMapOf<String, String>()

    suspend fun repos(
        sessionRepoPath: String? = null,
        includeRelayDiscovery: Boolean = true,
    ): Result<List<GitRepo>> {
        reposById.clear()
        relayRepoIdsByRoot.clear()
        val path = sessionRepoPath?.trim().orEmpty()
        if (path.isBlank()) {
            return if (includeRelayDiscovery) relayRepos().onSuccess(::rememberRepos)
            else Result.success(emptyList())
        }

        val upstream = upstreamStatus(path)
        if (upstream.isFailure) {
            val error = upstream.exceptionOrNull()!!
            if (!error.isUnsupportedGitRoute()) return Result.failure(error)
            return if (includeRelayDiscovery) relayRepos().onSuccess(::rememberRepos)
            else Result.failure(error)
        }
        val status = upstream.getOrNull()
        if (status == null) {
            return if (includeRelayDiscovery) relayRepos().onSuccess(::rememberRepos)
            else Result.success(emptyList())
        }

        val standardRepo = GitRepo(
            id = UPSTREAM_SESSION_REPO_ID,
            name = path.replace('\\', '/').trimEnd('/').substringAfterLast('/').ifBlank { path },
            root = path,
            currentBranch = status.branch,
            dirty = status.changed > 0,
            route = GitRepositoryRoute.UPSTREAM,
        )

        // Plugin discovery is an enhancement. Once upstream answered, plugin
        // absence or breakage cannot take the standard session repository down.
        val relay = if (includeRelayDiscovery) relayRepos().getOrDefault(emptyList()) else emptyList()
        val merged = buildList {
            add(standardRepo)
            addAll(relay.filterNot { sameRoot(it.root, standardRepo.root) })
        }
        rememberRepos(merged)
        rememberRelayRepos(relay)
        return Result.success(merged)
    }

    suspend fun status(repo: String): Result<GitStatus> {
        val target = reposById[repo]
        if (target?.route != GitRepositoryRoute.UPSTREAM) return relayStatus(repo)
        return fallbackOnUnsupported(target, upstreamStatusWithFiles(target.root), ::relayStatus)
    }

    suspend fun branches(repo: String): Result<List<GitBranch>> {
        val target = reposById[repo]
        if (target?.route != GitRepositoryRoute.UPSTREAM) return relayBranches(repo)
        return fallbackOnUnsupported(target, upstreamBranches(target.root), ::relayBranches)
    }

    suspend fun diff(repo: String, path: String, kind: String): Result<GitDiff> {
        val target = reposById[repo]
        if (target?.route != GitRepositoryRoute.UPSTREAM) return relayDiff(repo, path, kind)
        val upstream = dashboard.getJsonElement(
            upstreamPath(
                "/api/git/review/diff",
                mapOf(
                    "path" to target.root,
                    "file" to path,
                    "scope" to "uncommitted",
                    "staged" to (kind == "staged").toString(),
                ),
            ),
        ).mapCatching { element ->
            GitDiff(
                path = path,
                kind = kind,
                diff = json.decodeFromJsonElement<UpstreamDiffResponse>(element).diff,
            )
        }
        return fallbackOnUnsupported(target, upstream) { relay -> relayDiff(relay, path, kind) }
    }

    /** Clean tracked-file preview is a Relay enhancement; file-diff is not equivalent. */
    suspend fun file(repo: String, path: String): Result<GitFile> {
        val relay = relayRepoId(repo)
            ?: return Result.failure(IOException("Tracked-file preview requires the Relay plugin"))
        return relayFile(relay, path)
    }

    // Writes intentionally stay on Relay. The upstream Desktop mutation shape
    // does not carry plugin.api.write or the server-enforced confirmation echoes
    // used by this mobile surface, so it is not an equivalent safety contract.

    suspend fun stage(repo: String, paths: List<String>): Result<GitMutationResult> =
        relayWrite(repo) { relay -> scoped.post("git/stage", buildJsonObject {
            put("repo", relay)
            put("paths", pathsArray(paths))
        }).decodeMutation() }

    suspend fun unstage(repo: String, paths: List<String>): Result<GitMutationResult> =
        relayWrite(repo) { relay -> scoped.post("git/unstage", buildJsonObject {
            put("repo", relay)
            put("paths", pathsArray(paths))
        }).decodeMutation() }

    suspend fun discard(
        repo: String,
        paths: List<String>,
        confirmation: String,
        deleteUntracked: Boolean = false,
    ): Result<GitMutationResult> = relayWrite(repo) { relay -> scoped.post("git/discard", buildJsonObject {
        put("repo", relay)
        put("paths", pathsArray(paths))
        put("confirmation", confirmation)
        put("delete_untracked", deleteUntracked)
    }).decodeMutation() }

    suspend fun commit(repo: String, message: String): Result<GitMutationResult> =
        relayWrite(repo) { relay -> scoped.post("git/commit", buildJsonObject {
            put("repo", relay)
            put("message", message)
        }).decodeMutation() }

    suspend fun commitSelected(
        repo: String,
        message: String,
        paths: List<String>,
    ): Result<GitMutationResult> = relayWrite(repo) { relay -> scoped.post("git/commit_selected", buildJsonObject {
        put("repo", relay)
        put("message", message)
        put("paths", pathsArray(paths))
    }).decodeMutation() }

    suspend fun fetch(repo: String, remote: String = "origin"): Result<GitMutationResult> =
        relayWrite(repo) { relay -> scoped.post("git/fetch", buildJsonObject {
            put("repo", relay)
            put("remote", remote)
        }).decodeMutation() }

    suspend fun pull(repo: String, remote: String = "origin", branch: String = ""): Result<GitMutationResult> =
        relayWrite(repo) { relay -> scoped.post("git/pull", buildJsonObject {
            put("repo", relay)
            put("remote", remote)
            put("branch", branch)
        }).decodeMutation() }

    suspend fun push(
        repo: String,
        confirmation: String,
        remote: String = "origin",
        branch: String = "",
    ): Result<GitMutationResult> = relayWrite(repo) { relay -> scoped.post("git/push", buildJsonObject {
        put("repo", relay)
        put("remote", remote)
        put("branch", branch)
        put("confirmation", confirmation)
    }).decodeMutation() }

    suspend fun checkout(
        repo: String,
        ref: String,
        confirmation: String? = null,
        newBranch: String = "",
        track: Boolean = false,
    ): Result<GitMutationResult> = relayWrite(repo) { relay -> scoped.post("git/checkout", buildJsonObject {
        put("repo", relay)
        put("ref", ref)
        if (confirmation != null) put("confirmation", confirmation)
        if (newBranch.isNotEmpty()) put("new_branch", newBranch)
        put("track", track)
    }).decodeMutation() }

    suspend fun commitMessage(repo: String): Result<GitCommitMessage> = relayWrite(repo) { relay ->
        scoped.post("git/commit_message", buildJsonObject { put("repo", relay) })
            .mapCatching { json.decodeFromJsonElement<GitCommitMessage>(it) }
    }

    suspend fun commitMessageSelected(repo: String, paths: List<String>): Result<GitCommitMessage> =
        relayWrite(repo) { relay -> scoped.post("git/commit_message_selected", buildJsonObject {
            put("repo", relay)
            put("paths", pathsArray(paths))
        }).mapCatching { json.decodeFromJsonElement<GitCommitMessage>(it) } }

    suspend fun stashCheckout(
        repo: String,
        ref: String,
        newBranch: String = "",
        track: Boolean = false,
    ): Result<GitStashCheckoutResult> = relayWrite(repo) { relay -> scoped.post("git/stash_checkout", buildJsonObject {
        put("repo", relay)
        put("ref", ref)
        if (newBranch.isNotEmpty()) put("new_branch", newBranch)
        put("track", track)
    }).mapCatching { json.decodeFromJsonElement<GitStashCheckoutResult>(it) } }

    private suspend fun relayRepos(): Result<List<GitRepo>> = scoped.get("git/repos").mapCatching {
        json.decodeFromJsonElement<ReposResponse>(it).repos
    }

    private suspend fun relayStatus(repo: String): Result<GitStatus> = scoped
        .get("git/status", mapOf("repo" to repo))
        .mapCatching { json.decodeFromJsonElement<GitStatus>(it) }

    private suspend fun relayBranches(repo: String): Result<List<GitBranch>> = scoped
        .get("git/branches", mapOf("repo" to repo))
        .mapCatching { json.decodeFromJsonElement<BranchesResponse>(it).branches }

    private suspend fun relayDiff(repo: String, path: String, kind: String): Result<GitDiff> = scoped
        .get("git/diff", mapOf("repo" to repo, "path" to path, "kind" to kind))
        .mapCatching { json.decodeFromJsonElement<GitDiff>(it) }

    private suspend fun relayFile(repo: String, path: String): Result<GitFile> = scoped
        .get("git/file", mapOf("repo" to repo, "path" to path))
        .mapCatching { json.decodeFromJsonElement<GitFile>(it) }

    private suspend fun upstreamStatus(path: String): Result<UpstreamStatus?> = dashboard
        .getJsonElement(upstreamPath("/api/git/status", mapOf("path" to path)))
        .mapCatching { json.decodeFromJsonElement<UpstreamStatus?>(it) }

    private suspend fun upstreamStatusWithFiles(path: String): Result<GitStatus> {
        val status = upstreamStatus(path).mapCatching {
            it ?: throw IOException("The active session path is not a Git repository")
        }.getOrElse { return Result.failure(it) }
        val review = dashboard.getJsonElement(
            upstreamPath("/api/git/review/list", mapOf("path" to path, "scope" to "uncommitted")),
        ).mapCatching { json.decodeFromJsonElement<UpstreamReviewList>(it) }
            .getOrElse { return Result.failure(it) }
        val statusByPath = status.files.associateBy { it.path }
        val staged = mutableListOf<GitStatusEntry>()
        val modified = mutableListOf<GitStatusEntry>()
        val untracked = mutableListOf<GitStatusEntry>()
        review.files.forEach { file ->
            val entry = GitStatusEntry(file.path, file.added, file.removed)
            val fileStatus = statusByPath[file.path]
            if (fileStatus?.untracked == true) {
                untracked += entry
            } else {
                if (file.staged || fileStatus?.staged == true) staged += entry
                if (fileStatus?.unstaged == true || (!file.staged && fileStatus == null)) {
                    modified += entry
                }
            }
        }
        return Result.success(
            GitStatus(
                counts = GitStatusCounts(
                    staged = status.staged,
                    modified = status.unstaged,
                    untracked = status.untracked,
                    changes = status.changed,
                    additions = status.added,
                    deletions = status.removed,
                ),
                staged = staged,
                modified = modified,
                untracked = untracked,
            ),
        )
    }

    private suspend fun upstreamBranches(path: String): Result<List<GitBranch>> = dashboard
        .getJsonElement(upstreamPath("/api/git/branches", mapOf("path" to path)))
        .mapCatching { element ->
            json.decodeFromJsonElement<UpstreamBranches>(element).branches.map {
                GitBranch(name = it.name, isCurrent = it.checkedOut)
            }
        }

    private suspend fun <T> fallbackOnUnsupported(
        target: GitRepo,
        upstream: Result<T>,
        relayCall: suspend (String) -> Result<T>,
    ): Result<T> {
        if (upstream.isSuccess) return upstream
        val error = upstream.exceptionOrNull()!!
        if (!error.isUnsupportedGitRoute()) return Result.failure(error)
        val relay = relayRepoIdsByRoot[normalizedRoot(target.root)] ?: return Result.failure(error)
        return relayCall(relay)
    }

    private suspend fun <T> relayWrite(repo: String, block: suspend (String) -> Result<T>): Result<T> {
        val relay = relayRepoId(repo)
            ?: return Result.failure(IOException("This Git action requires the Relay plugin enhancement"))
        return block(relay)
    }

    private fun relayRepoId(repo: String): String? {
        val target = reposById[repo] ?: return repo.takeUnless { it == UPSTREAM_SESSION_REPO_ID }
        return if (target.route == GitRepositoryRoute.RELAY) target.id
        else relayRepoIdsByRoot[normalizedRoot(target.root)]
    }

    private fun rememberRepos(repos: List<GitRepo>) {
        repos.forEach { reposById[it.id] = it }
        rememberRelayRepos(repos.filter { it.route == GitRepositoryRoute.RELAY })
    }

    private fun rememberRelayRepos(repos: List<GitRepo>) {
        repos.forEach { relayRepoIdsByRoot[normalizedRoot(it.root)] = it.id }
    }

    private fun upstreamPath(path: String, query: Map<String, String>): String = buildString {
        append(path)
        if (query.isNotEmpty()) {
            append('?')
            append(query.entries.joinToString("&") { (key, value) -> "${encode(key)}=${encode(value)}" })
        }
    }

    private fun encode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun normalizedRoot(path: String): String {
        val normalized = path.trim().replace('\\', '/').trimEnd('/')
        return if (WINDOWS_ROOT.containsMatchIn(normalized) || normalized.startsWith("//")) {
            normalized.lowercase(Locale.ROOT)
        } else {
            normalized
        }
    }

    private fun sameRoot(first: String, second: String): Boolean =
        normalizedRoot(first) == normalizedRoot(second)

    private fun Result<kotlinx.serialization.json.JsonObject>.decodeMutation(): Result<GitMutationResult> =
        mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    private fun Throwable.isUnsupportedGitRoute(): Boolean =
        this is DashboardHttpException && statusCode == 404

    private companion object {
        const val UPSTREAM_SESSION_REPO_ID = "__upstream_session__"
        val WINDOWS_ROOT = Regex("^[A-Za-z]:/")
    }
}

@Serializable
private data class UpstreamStatus(
    val branch: String? = null,
    val staged: Int = 0,
    val unstaged: Int = 0,
    val untracked: Int = 0,
    val changed: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val files: List<UpstreamStatusFile> = emptyList(),
)

@Serializable
private data class UpstreamStatusFile(
    val path: String,
    val staged: Boolean = false,
    val unstaged: Boolean = false,
    val untracked: Boolean = false,
)

@Serializable
private data class UpstreamReviewList(val files: List<UpstreamReviewFile> = emptyList())

@Serializable
private data class UpstreamReviewFile(
    val path: String,
    val added: Int = 0,
    val removed: Int = 0,
    val staged: Boolean = false,
)

@Serializable
private data class UpstreamBranches(val branches: List<UpstreamBranch> = emptyList())

@Serializable
private data class UpstreamBranch(
    val name: String,
    @SerialName("checkedOut") val checkedOut: Boolean = false,
)

@Serializable
private data class UpstreamDiffResponse(val diff: String = "")
