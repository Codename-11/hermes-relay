package com.hermesandroid.relay.data

import com.hermesandroid.relay.network.upstream.DashboardApiClient
import com.hermesandroid.relay.plugins.runtime.ScopedPluginApiClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

// Read-only client for the Hermes-Relay Git State endpoints.
// All requests are confined to the ``hermes-relay`` plugin namespace and the
// ``git/*`` sub-path via ScopedPluginApiClient, which rejects traversal and
// encodes query values. No write operations are exposed.
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
}
