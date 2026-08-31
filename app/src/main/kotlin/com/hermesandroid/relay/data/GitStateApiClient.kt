package com.hermesandroid.relay.data

import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.plugins.runtime.ScopedPluginApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put

// Read + write client for the Hermes-Relay Git State endpoints.
// All requests are confined to the ``hermes-relay`` plugin namespace and the
// ``git/*`` sub-path via ScopedPluginApiClient, which rejects traversal and
// encodes query values.

private fun pathsArray(paths: List<String>) = buildJsonArray { paths.forEach { add(JsonPrimitive(it)) } }

class GitStateApiClient(
    dashboard: DashboardApiClient,
) {
    private val scoped = ScopedPluginApiClient("hermes-relay", dashboard)
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun repos(): Result<List<GitRepo>> = scoped
        .get("git/repos")
        .mapCatching { element ->
            json.decodeFromJsonElement<ReposResponse>(element).repos
        }

    suspend fun status(repo: String): Result<GitStatus> = scoped
        .get("git/status", mapOf("repo" to repo))
        .mapCatching { element -> json.decodeFromJsonElement<GitStatus>(element) }

    suspend fun branches(repo: String): Result<List<GitBranch>> = scoped
        .get("git/branches", mapOf("repo" to repo))
        .mapCatching { element ->
            json.decodeFromJsonElement<BranchesResponse>(element).branches
        }

    suspend fun diff(repo: String, path: String, kind: String): Result<GitDiff> = scoped
        .get("git/diff", mapOf("repo" to repo, "path" to path, "kind" to kind))
        .mapCatching { element -> json.decodeFromJsonElement<GitDiff>(element) }

    suspend fun file(repo: String, path: String): Result<GitFile> = scoped
        .get("git/file", mapOf("repo" to repo, "path" to path))
        .mapCatching { element -> json.decodeFromJsonElement<GitFile>(element) }

    // ── Write operations ───────────────────────────────────────────────────
    // Every write requires the plugin.api.write grant, which the app enforces
    // (see GitStateViewModel: a POST is never sent without the grant). The
    // server additionally enforces per-use confirmation strings for destructive
    // ops (discard/push/dirty-checkout) — the caller passes the echoed token.

    suspend fun stage(repo: String, paths: List<String>): Result<GitMutationResult> =
        scoped.post("git/stage", buildJsonObject {
            put("repo", repo)
            put("paths", pathsArray(paths))
        }).mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    suspend fun unstage(repo: String, paths: List<String>): Result<GitMutationResult> =
        scoped.post("git/unstage", buildJsonObject {
            put("repo", repo)
            put("paths", pathsArray(paths))
        }).mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    suspend fun discard(
        repo: String,
        paths: List<String>,
        confirmation: String,
        deleteUntracked: Boolean = false,
    ): Result<GitMutationResult> = scoped.post("git/discard", buildJsonObject {
        put("repo", repo)
        put("paths", pathsArray(paths))
        put("confirmation", confirmation)
        put("delete_untracked", deleteUntracked)
    }).mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    suspend fun commit(repo: String, message: String): Result<GitMutationResult> =
        scoped.post("git/commit", buildJsonObject {
            put("repo", repo)
            put("message", message)
        }).mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    suspend fun commitSelected(
        repo: String,
        message: String,
        paths: List<String>,
    ): Result<GitMutationResult> = scoped.post("git/commit_selected", buildJsonObject {
        put("repo", repo)
        put("message", message)
        put("paths", pathsArray(paths))
    }).mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    suspend fun fetch(repo: String, remote: String = "origin"): Result<GitMutationResult> =
        scoped.post("git/fetch", buildJsonObject {
            put("repo", repo)
            put("remote", remote)
        }).mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    suspend fun pull(repo: String, remote: String = "origin", branch: String = ""): Result<GitMutationResult> =
        scoped.post("git/pull", buildJsonObject {
            put("repo", repo)
            put("remote", remote)
            put("branch", branch)
        }).mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    suspend fun push(
        repo: String,
        confirmation: String,
        remote: String = "origin",
        branch: String = "",
    ): Result<GitMutationResult> = scoped.post("git/push", buildJsonObject {
        put("repo", repo)
        put("remote", remote)
        put("branch", branch)
        put("confirmation", confirmation)
    }).mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    suspend fun checkout(
        repo: String,
        ref: String,
        confirmation: String? = null,
        newBranch: String = "",
        track: Boolean = false,
    ): Result<GitMutationResult> = scoped.post("git/checkout", buildJsonObject {
        put("repo", repo)
        put("ref", ref)
        if (confirmation != null) put("confirmation", confirmation)
        if (newBranch.isNotEmpty()) put("new_branch", newBranch)
        put("track", track)
    }).mapCatching { json.decodeFromJsonElement<GitMutationResult>(it) }

    // ── Phase 3 extras ─────────────────────────────────────────────────────

    /** Generate a commit-message suggestion from the staged diff. */
    suspend fun commitMessage(repo: String): Result<GitCommitMessage> =
        scoped.post("git/commit_message", buildJsonObject {
            put("repo", repo)
        }).mapCatching { json.decodeFromJsonElement<GitCommitMessage>(it) }

    /** Generate a commit-message suggestion from the given paths' staged diff. */
    suspend fun commitMessageSelected(
        repo: String,
        paths: List<String>,
    ): Result<GitCommitMessage> = scoped.post("git/commit_message_selected", buildJsonObject {
        put("repo", repo)
        put("paths", pathsArray(paths))
    }).mapCatching { json.decodeFromJsonElement<GitCommitMessage>(it) }

    /** Checkout that auto-stashes a dirty tree first. */
    suspend fun stashCheckout(
        repo: String,
        ref: String,
        newBranch: String = "",
        track: Boolean = false,
    ): Result<GitStashCheckoutResult> = scoped.post("git/stash_checkout", buildJsonObject {
        put("repo", repo)
        put("ref", ref)
        if (newBranch.isNotEmpty()) put("new_branch", newBranch)
        put("track", track)
    }).mapCatching { json.decodeFromJsonElement<GitStashCheckoutResult>(it) }
}
