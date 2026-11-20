package collector.source

import collector.config.GitHubIssuesConfig
import collector.config.SourceConfig
import collector.model.Comment
import collector.model.Item
import collector.port.SecretProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import collector.dev.readJson
import collector.dev.writeAtomically
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class GitHubIssuesAdapter(
    private val http: OkHttpClient,
    private val secrets: SecretProvider,
    baseUrl: String = "https://api.github.com/",
    private val repositoryCache: File = File("out/cache/github-repos.json"),
    private val debugDirectory: File = File("out/debug")
) : SourceAdapter {
    override val id = "github-issues"
    private val base = baseUrl.toHttpUrl()
    private val search = GitHubSearch.shared(http)

    override suspend fun fetch(config: SourceConfig, now: Instant): List<Item> =
        fetchWithWarning(config, now).first

    internal suspend fun fetchWithWarning(
        config: SourceConfig, now: Instant, timeout: Duration = (config as? GitHubIssuesConfig)?.sourceTimeoutSeconds?.seconds ?: 90.seconds
    ): Pair<List<Item>, String?> {
        require(config is GitHubIssuesConfig)
        val token = secrets.get("GITHUB_TOKEN")?.takeIf { it.isNotBlank() }
        val warnings = linkedSetOf<String>()
        if (config.includeDiscussions && token == null) warnings += "GITHUB_TOKEN 없음 · GitHub 토론 검색을 건너뜁니다."
        val items = linkedMapOf<String, Candidate>()
        val pending = linkedMapOf<String, Candidate>()
        val cutoff = now.minus(config.window())
        val since = cutoff.truncatedTo(ChronoUnit.SECONDS)
        // is:public keeps private issues the token can read out of storage, debug samples and LLM input.
        val filters = "is:public created:>=$since comments:>=${config.minComments}"
        var privateExcluded = 0
        val queries = config.queries.map { it.removeSuffix(" in:title reactions:>=1") }
        val queryTerms = queries.associateWith(::titleTerm)
        val titleSignalTerms = config.titleSignalWords.map(::titleTerm)
        val excludedTitles = config.excludeTitlePatterns.map { Regex(it, RegexOption.IGNORE_CASE) }
        val date = LocalDate.ofInstant(now, ZoneOffset.UTC)
        val routes = listOf("label", "title-intent").flatMap { route -> queries.map { it to route } }
        val budget = minOf(if (token == null) 9 else 24, routes.size)
        val start = if (routes.isEmpty()) 0 else Math.floorMod(date.toEpochDay() * budget, routes.size)
        val funnels = mutableListOf<Funnel>()
        val sampleCounts = mutableMapOf<String, Int>()
        val discussionCount = minOf(3, queries.size)
        val discussionStart = if (queries.isEmpty()) 0 else
            Math.floorMod(LocalDate.ofInstant(now, ZoneOffset.UTC).toEpochDay() * discussionCount, queries.size)
        val limit = config.fetchPerQuery.coerceIn(1, 100)
        val cached = cacheLock.withLock {
            readJson<Map<String, CachedRepository>>(repositoryCache).orEmpty()
                .filterValues { it.expiresAt > now.epochSecond }
                .mapValuesTo(mutableMapOf()) { it.value.repository }
        }
        val attempted = mutableSetOf<String>()
        var hadFailure = false

        fun reject(funnel: Funnel, stage: String, title: String, url: String) {
            funnel.dropped.merge(stage, 1, Int::plus)
            if ((sampleCounts[funnel.query] ?: 0) >= 5) return
            val parsed = url.toHttpUrlOrNull() ?: return
            if (parsed.host != "github.com") return
            funnel.rejected += Rejected(redactMentions(title), "https://github.com" + parsed.encodedPath)
            sampleCounts.merge(funnel.query, 1, Int::plus)
        }

        fun add(candidate: Candidate, positive: Int) {
            val item = candidate.item
            val funnel = candidate.funnel
            fun passes(condition: Boolean, stage: String): Boolean {
                if (!condition) reject(funnel, stage, item.title, item.url)
                return condition
            }
            if (!passes(positive >= config.minPositiveReactions, "긍정 반응 하한")) return
            funnel.afterPositiveReactions++
            if (!passes(item.rawScore >= config.minRawScore, "점수 하한")) return
            funnel.afterScore++
            if (!passes(item.tags.none { tag -> config.excludeLabels.any { excluded ->
                if (excluded.endsWith(":")) tag.startsWith(excluded, ignoreCase = true)
                else tag.equals(excluded, ignoreCase = true)
            } }, "제외 라벨")) return
            funnel.afterExcludedLabels++
            if (!passes(excludedTitles.none { it.containsMatchIn(item.title) }, "제외 제목")) return
            if (!passes(item.commentCount >= config.minComments && item.publishedAt >= cutoff, "댓글·기간 조건")) return
            if (!passes(item.externalId !in items && item.externalId !in pending, "중복")) return
            pending[item.externalId] = candidate
        }

        suspend fun attempt(warning: String, action: suspend () -> Unit) {
            try {
                action()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                hadFailure = true
                warnings += warning
            }
        }

        fun keep(candidate: Candidate, metadata: Repository) {
            val item = candidate.item
            items[item.externalId] = candidate.copy(item = item.copy(
                extra = item.extra + ("stars" to metadata.stars.toString())))
        }

        fun finishCandidate(candidate: Candidate, metadata: Repository?, external: Int) {
            val item = candidate.item
            val funnel = candidate.funnel
            fun drop(stage: String) = reject(funnel, stage, item.title, item.url)
            if (metadata?.private == true) {
                // Defense in depth behind is:public: drop silently so private titles never reach the debug samples.
                privateExcluded++
                return
            }
            val lowStars = metadata != null && metadata.stars < config.minRepositoryStars
            val ownerMemo = lowStars && candidate.ownerPost && metadata?.owner?.type == "User"
            if (lowStars && !ownerMemo) return drop("스타 하한")
            if (metadata == null) funnel.starsUnverified++
            funnel.afterStars++
            if (ownerMemo && external < 3) return drop("소유자 메모")
            funnel.afterOwnerMemo++
            if (candidate.ambiguous && metadata?.isAi() != true) return drop("다의어 검사")
            funnel.afterAmbiguity++
            if (metadata == null) items[item.externalId] = candidate else keep(candidate, metadata)
            funnel.finalCount++
        }

        suspend fun verifyPending() {
            for ((key, candidate) in pending.toMap()) {
                val repo = candidate.item.extra["repository"] ?: continue
                val cacheKey = repo.lowercase()
                val existing = cached[cacheKey]
                if ((existing == null || candidate.ambiguous && existing.topics == null) && attempted.add(cacheKey)) {
                    try {
                        cached[cacheKey] = repository(repo, token, now, warnings)
                    } catch (_: InterruptedIOException) {
                        // Leave timed-out lookups pending for the final cache check.
                    } catch (_: IOException) {
                        warnings += "GitHub 이슈 · 저장소 메타데이터 조회 실패"
                        cached[cacheKey] = Repository(0)
                    }
                }
                val metadata = cached[cacheKey] ?: continue
                val ownerMemo = metadata.stars < config.minRepositoryStars &&
                    candidate.ownerPost && metadata.owner?.type == "User"
                var external = 0
                if (ownerMemo && (!candidate.ambiguous || metadata.isAi())) {
                    attempt("GitHub 이슈 · 외부 긍정 반응 조회 실패") {
                        external = if (candidate.issueNumber != null) {
                            externalIssueReactions(repo, candidate.issueNumber, token)
                        } else {
                            externalDiscussionReactions(checkNotNull(candidate.discussionId), repo, checkNotNull(token), warnings)
                        }
                    }
                }
                pending.remove(key)
                finishCandidate(candidate, metadata, external)
            }
        }

        fun finishPending() {
            // A timeout can interrupt a lookup before later cached candidates are visited.
            for (candidate in pending.values) {
                val metadata = candidate.item.extra["repository"]?.lowercase()?.let(cached::get)
                finishCandidate(candidate, metadata, 0)
            }
            pending.clear()
        }

        fun titleQuery(query: String): String {
            val title = config.titleIntents[query]?.let { "$query $it" } ?: query
            return "$title in:title"
        }

        fun ambiguous(query: String): Boolean = config.ambiguousTerms.any {
            it.trim('"').equals(query.trim('"'), ignoreCase = true)
        }

        val completed = withTimeoutOrNull(timeout) {
            for (offset in 0 until budget) {
                val (query, route) = routes[(start + offset) % routes.size]
                val funnel = Funnel(redactMentions(query), route).also(funnels::add)
                val titleIntent = route == "title-intent"
                val searchQuery = if (titleIntent) {
                    "is:issue AND is:public AND $query AND in:title AND $titleIntentGroup AND reactions:>=1 AND created:>=$since AND comments:>=${config.minComments}"
                } else "is:issue $query in:title reactions:>=1 $requestLabels $filters"
                val url = base.newBuilder().addPathSegments("search/issues")
                    .addQueryParameter("q", searchQuery)
                    .apply { if (titleIntent) addQueryParameter("advanced_search", "true") }
                    .addQueryParameter("sort", config.sort)
                    .addQueryParameter("order", "desc")
                    .addQueryParameter("per_page", limit.toString()).build()
                attempt("GitHub 이슈 · 검색어 ${offset + 1}번 부분 실패") {
                    val response = search.get(http, request(url, token).build(), token, retryLimited = false,
                        priority = GitHubSearch.Priority.DAILY_ISSUES) { body, _ ->
                        Http.json.decodeFromString<IssueSearch>(body)
                    }
                    if (response.incomplete) warnings += "GitHub 이슈 검색 응답에 부분 결과가 포함되었습니다."
                    funnel.totalCount = response.totalCount
                    funnel.received = response.items.size
                    for (result in response.items) {
                        if ("pull_request" in result) {
                            val title = result["title"]?.jsonPrimitive?.contentOrNull
                            val url = result["html_url"]?.jsonPrimitive?.contentOrNull
                            reject(funnel, "PR·봇 제외", title.orEmpty(), url.orEmpty())
                            continue
                        }
                        val issue = Http.json.decodeFromJsonElement<Issue>(result)
                        if (issue.user?.isBot() == true) {
                            reject(funnel, "PR·봇 제외", issue.title, issue.url)
                            continue
                        }
                        funnel.afterPrAndBot++
                        if (titleIntent && (!queryTerms.getValue(query).containsMatchIn(issue.title) ||
                                titleSignalTerms.none { it.containsMatchIn(issue.title) })) {
                            reject(funnel, "요청·불편 표현 없음", issue.title, issue.url)
                            continue
                        }
                        val repo = repositoryFromUrl(issue.url)
                        val positive = issue.reactions?.positive ?: 0
                        val item = item(config, issue.id.toString(), issue.title, issue.url, issue.created,
                            issue.body, positive, issue.comments, repo, query, issue.labels.map { it.name })
                        add(Candidate(item, funnel, issueNumber = issue.number, ownerPost = isOwner(issue.user, repo),
                            ambiguous = ambiguous(query)), positive)
                    }
                }
                verifyPending()
            }
            if (config.includeDiscussions && token != null) {
                for (offset in 0 until discussionCount) {
                    val index = (discussionStart + offset) % queries.size
                    val query = queries[index]
                    val route = if (query in config.titleIntents) "discussion-title-intent" else "discussion-category"
                    val funnel = Funnel(redactMentions(query), route).also(funnels::add)
                    val warning = "GitHub 토론 · 검색어 ${index + 1}번 부분 실패"
                    attempt(warning) {
                        val request = graphRequest(token, discussionSearch, buildJsonObject {
                            put("query", "${titleQuery(query)} $filters")
                            put("limit", limit)
                        })
                        val response = search.get(http, request, token, retryLimited = false,
                            priority = GitHubSearch.Priority.DAILY_ISSUES) { body, _ ->
                            Http.json.decodeFromString<GraphResponse<DiscussionSearch>>(body)
                        }
                        if (response.errors.isNotEmpty()) {
                            hadFailure = true
                            warnings += warning
                        }
                        // Discussion search has no documented reactions qualifier.
                        val discussions = response.data?.search
                        val nodes = discussions?.nodes.orEmpty().filterNotNull()
                        funnel.totalCount = discussions?.discussionCount
                        funnel.received = nodes.size
                        for (discussion in nodes) {
                            if (discussion.repository.isPrivate) {
                                privateExcluded++
                                continue
                            }
                            if (discussion.author?.isBot() == true) {
                                reject(funnel, "PR·봇 제외", discussion.title, discussion.url)
                                continue
                            }
                            funnel.afterPrAndBot++
                            if (discussion.upvoteCount + discussion.reactions.totalCount < 2) {
                                reject(funnel, "토론 반응 하한", discussion.title, discussion.url)
                                continue
                            }
                            if (query !in config.titleIntents && discussion.category?.name?.lowercase() !in discussionCategories) {
                                reject(funnel, "토론 카테고리", discussion.title, discussion.url)
                                continue
                            }
                            val repo = discussion.repository.nameWithOwner
                            val item = item(config, "discussion:${discussion.id}", discussion.title, discussion.url,
                                discussion.createdAt, discussion.body, discussion.upvoteCount, discussion.comments.totalCount,
                                repo, query, discussion.labels?.nodes.orEmpty().filterNotNull().map { it.name })
                            add(Candidate(item, funnel, discussionId = discussion.id, ownerPost = isOwner(discussion.author, repo),
                                ambiguous = ambiguous(query)), discussion.upvoteCount)
                        }
                    }
                    verifyPending()
                }
            }
            finishPending()
            for (candidate in items.values.sortedByDescending { it.item.rawScore }.take(config.commentItems.coerceAtLeast(0))) {
                attempt("GitHub 이슈 · 일부 댓글을 가져오지 못했습니다.") {
                    val comments = comments(candidate, token, warnings)
                    val enriched = candidate.item.copy(comments = comments)
                    items[enriched.externalId] = candidate.copy(item = enriched)
                }
            }
            true
        }
        if (completed == null) finishPending()
        if (privateExcluded > 0) warnings += "GitHub 이슈 · 비공개 저장소 ${privateExcluded}건 제외"
        try {
            debugDirectory.resolve("github-issues-$date.json").writeAtomically(
                debugJson.encodeToString(ListSerializer(Funnel.serializer()), funnels))
        } catch (_: IOException) {
            warnings += "GitHub 이슈 · 단계별 기록 저장 실패"
        }
        val dropped = mutableMapOf<String, Int>()
        for (funnel in funnels) for ((stage, count) in funnel.dropped) dropped.merge(stage, count, Int::plus)
        val largestDrop = dropped.maxByOrNull { it.value }?.key ?: "없음"
        val summary = "받음 ${funnels.sumOf { it.received }} → 최종 ${items.size}, 가장 많이 뺀 단계: $largestDrop"
        warnings += summary
        val unverified = funnels.sumOf { it.starsUnverified }
        if (unverified > 0) warnings += "GitHub 이슈 · 스타 하한 판정 보류 ${unverified}건"
        if (completed == null) warnings += "GitHub 이슈 · 수집 시간 초과로 부분 결과를 사용합니다."
        if (items.isEmpty() && (completed == null || hadFailure)) {
            val reason = if (completed == null) "수집 시간 초과" else "수집 요청 실패"
            throw GitHubCollectionException("GitHub 이슈 · $reason · ${warnings.joinToString(" · ")}")
        }
        return items.values.map { it.item }.sortedByDescending { it.rawScore } to
            warnings.joinToString(" · ").ifBlank { null }
    }

    private fun item(
        config: GitHubIssuesConfig, key: String, title: String, url: String, created: String,
        body: String?, reactions: Int, comments: Int, repo: String?, query: String, labels: List<String>
    ): Item {
        val text = sanitize(body).take(3000)
        return Item(id, key, redactMentions(title), url, 2 * reactions + comments.coerceAtMost(10),
            publishedAt = Instant.parse(created), snippet = plainText(text), commentCount = comments,
            extra = buildMap {
                repo?.let { put("repository", it) }
                put("matchedQuery", redactMentions(query))
                put("body", text)
            }, tags = labels, profileHint = config.profile)
    }

    private suspend fun repository(name: String, token: String?, now: Instant, warnings: MutableSet<String>): Repository {
        val key = name.lowercase()
        val url = base.newBuilder().addPathSegment("repos").addPathSegments(name).build()
        val response = Http.authenticatedJson<Repository>(http, request(url, token).build())
        val metadata = response.copy(topics = response.topics.orEmpty(),
            description = response.description?.take(200)?.let(::redactMentions))
        cacheLock.withLock {
            try {
                val cache = readJson<Map<String, CachedRepository>>(repositoryCache).orEmpty()
                    .filterValues { it.expiresAt > now.epochSecond }
                repositoryCache.writeAtomically(Http.json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, CachedRepository>>(),
                    cache + (key to CachedRepository(now.plus(7, ChronoUnit.DAYS).epochSecond, metadata))))
            } catch (_: IOException) {
                warnings += "GitHub 이슈 · 저장소 캐시 저장 실패"
            }
        }
        return metadata
    }

    private fun isOwner(author: Actor?, repo: String?) =
        repo != null && author?.login.equals(repo.substringBefore('/'), ignoreCase = true)

    private suspend fun externalIssueReactions(repo: String, number: Int, token: String?): Int {
        var page = 1
        var external = 0
        while (external < 3) {
            val url = base.newBuilder().addPathSegment("repos").addPathSegments(repo)
                .addPathSegments("issues/$number/reactions")
                .addQueryParameter("per_page", "100").addQueryParameter("page", page.toString()).build()
            val reactions = Http.authenticatedJson<List<Reaction>>(http, request(url, token).build())
            external += reactions.count { it.isExternal(repo) }
            if (reactions.size < 100) break
            page++
        }
        return external
    }

    private suspend fun externalDiscussionReactions(id: String, repo: String, token: String, warnings: MutableSet<String>): Int {
        var cursor: String? = null
        var external = 0
        do {
            val request = graphRequest(token, discussionReactions, buildJsonObject {
                put("id", id)
                put("cursor", cursor)
            })
            val response = Http.authenticatedJson<GraphResponse<DiscussionReactionThread>>(http, request)
            if (response.errors.isNotEmpty()) warnings += "GitHub 토론 · 일부 긍정 반응에 오류가 포함되었습니다."
            val reactions = response.data?.node?.reactions ?: break
            external += reactions.nodes.orEmpty().filterNotNull().count { it.isExternal(repo) }
            cursor = reactions.pageInfo?.takeIf { it.hasNextPage }?.endCursor
        } while (cursor != null && external < 3)
        return external
    }

    private suspend fun comments(candidate: Candidate, token: String?, warnings: MutableSet<String>): List<Comment> {
        val bodies = if (candidate.discussionId != null) {
            val request = graphRequest(checkNotNull(token), discussionComments, buildJsonObject {
                put("id", candidate.discussionId)
            })
            val response = Http.authenticatedJson<GraphResponse<DiscussionThread>>(http, request)
            if (response.errors.isNotEmpty()) warnings += "GitHub 토론 · 일부 댓글에 오류가 포함되었습니다."
            response.data?.node?.comments?.nodes.orEmpty().filterNotNull()
                .filterNot { it.author?.isBot() == true }.map { it.body to ((it.upvoteCount ?: 0) + (it.reactions?.total ?: 0)) }
        } else {
            val repo = candidate.item.extra["repository"] ?: return emptyList()
            val url = base.newBuilder().addPathSegment("repos").addPathSegments(repo)
                .addPathSegments("issues/${candidate.issueNumber}/comments")
                .addQueryParameter("per_page", "10").build()
            Http.authenticatedJson<List<IssueReply>>(http, request(url, token).build())
                .filterNot { it.user?.isBot() == true }.map { it.body to it.reactions?.total }
        }
        return bodies.sortedByDescending { it.second ?: 0 }.mapNotNull { (body, _) ->
            plainText(sanitize(body), 500).takeIf { it.isNotBlank() }?.let { Comment("익명", it) }
        }.take(3)
    }

    private fun request(url: HttpUrl, token: String?) = Request.Builder().url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .apply { if (token != null) header("Authorization", "Bearer $token") }

    private fun graphRequest(token: String, query: String, variables: JsonObject): Request =
        request(base.newBuilder().addPathSegment("graphql").build(), token).post(buildJsonObject {
            put("query", query)
            put("variables", variables)
        }.toString().toRequestBody("application/json".toMediaType())).build()

    private fun sanitize(body: String?) = redactMentions(body.orEmpty().replace(codeBlock, "[코드 생략]"))
    private fun redactMentions(text: String) = text.replace(mention, "@user")

    private data class Candidate(val item: Item, val funnel: Funnel, val issueNumber: Int? = null, val discussionId: String? = null,
        val ownerPost: Boolean = false, val ambiguous: Boolean = false)

    @Serializable
    private class Funnel(
        val query: String, val route: String,
        @SerialName("total_count") var totalCount: Int? = null,
        var received: Int = 0,
        var afterPrAndBot: Int = 0,
        var afterPositiveReactions: Int = 0,
        var afterScore: Int = 0,
        var afterExcludedLabels: Int = 0,
        var afterStars: Int = 0,
        var starsUnverified: Int = 0,
        var afterOwnerMemo: Int = 0,
        var afterAmbiguity: Int = 0,
        var finalCount: Int = 0,
        val rejected: MutableList<Rejected> = mutableListOf(),
        val dropped: MutableMap<String, Int> = linkedMapOf()
    )

    @Serializable private data class Rejected(val title: String, val url: String)

    @Serializable private data class CachedRepository(val expiresAt: Long, val repository: Repository)
    @Serializable private data class Repository(
        @SerialName("stargazers_count") val stars: Int,
        val private: Boolean = false,
        val language: String? = null, val license: License? = null, val owner: RepositoryOwner? = null,
        val topics: List<String>? = null, val description: String? = null
    ) {
        fun isAi() = aiTerms.containsMatchIn(description.orEmpty()) || topics.orEmpty().any(aiTerms::containsMatchIn)
    }
    @Serializable private data class License(@SerialName("spdx_id") val id: String? = null)
    @Serializable private data class RepositoryOwner(val type: String? = null)

    @Serializable private data class Reaction(val content: String, val user: Actor? = null) {
        fun isExternal(repo: String): Boolean {
            val login = user?.login ?: return false
            return !login.equals(repo.substringBefore('/'), ignoreCase = true) && !user.isBot() &&
                content.lowercase() in positiveReactions
        }
    }

    @Serializable private data class Actor(
        val login: String? = null, val type: String? = null, @SerialName("__typename") val typename: String? = null
    ) {
        fun isBot() = type == "Bot" || typename == "Bot" || login?.endsWith("[bot]", ignoreCase = true) == true
    }
    @Serializable private data class Label(val name: String)
    @Serializable private data class Reactions(
        @SerialName("total_count") val total: Int = 0, @SerialName("+1") val plusOne: Int = 0,
        val heart: Int = 0, val hooray: Int = 0, val rocket: Int = 0
    ) {
        val positive get() = plusOne + heart + hooray + rocket
    }
    @Serializable private data class IssueSearch(
        val items: List<JsonObject>, @SerialName("total_count") val totalCount: Int? = null,
        @SerialName("incomplete_results") val incomplete: Boolean = false
    )
    @Serializable private data class Issue(
        val id: Long, val number: Int, val title: String, @SerialName("html_url") val url: String,
        @SerialName("created_at") val created: String, val body: String? = null, val user: Actor? = null,
        val comments: Int = 0, val reactions: Reactions? = null, val labels: List<Label> = emptyList()
    )
    @Serializable private data class IssueReply(
        val body: String? = null, val user: Actor? = null, val reactions: Reactions? = null
    )
    @Serializable private data class GraphResponse<T>(val data: T? = null, val errors: List<JsonObject> = emptyList())
    @Serializable private data class DiscussionSearch(val search: Nodes<Discussion>? = null)
    @Serializable private data class Nodes<T>(val nodes: List<T?>? = null, val totalCount: Int = 0, val pageInfo: PageInfo? = null, val discussionCount: Int? = null)
    @Serializable private data class DiscussionRepository(
        val nameWithOwner: String, val isPrivate: Boolean = false
    )
    @Serializable private data class Discussion(
        val id: String, val title: String, val url: String, val createdAt: String, val body: String? = null,
        val author: Actor? = null, val repository: DiscussionRepository, val upvoteCount: Int = 0,
        val comments: Nodes<JsonObject> = Nodes(), val labels: Nodes<Label>? = null,
        val reactions: Nodes<JsonObject> = Nodes(), val category: Label? = null
    )
    @Serializable private data class DiscussionReactionThread(val node: DiscussionReactionNode? = null)
    @Serializable private data class DiscussionReactionNode(val reactions: Nodes<Reaction>? = null)
    @Serializable private data class PageInfo(val hasNextPage: Boolean, val endCursor: String? = null)
    @Serializable private data class DiscussionThread(val node: DiscussionReplies? = null)
    @Serializable private data class DiscussionReplies(val comments: Nodes<DiscussionReply>)
    @Serializable private data class DiscussionReply(
        val body: String? = null, val author: Actor? = null,
        val reactions: Reactions? = null, val upvoteCount: Int? = null
    )

    companion object {
        private const val requestLabels = "label:enhancement,feature,\"feature request\",\"type: feature\",\"kind/feature\",proposal,idea"
        private val debugJson = Json { encodeDefaults = true }
        private val titleIntents = listOf("support", "\"add support\"", "\"feature request\"", "\"please add\"", "\"is there a way\"", "integration")
        private val titleIntentGroup = titleIntents.joinToString(" OR ", "(", ")")

        private fun titleTerm(term: String) =
            Regex("\\b" + Regex.escape(term.trim('"')) + "\\b", RegexOption.IGNORE_CASE)
        private val discussionCategories = setOf("ideas", "feature requests", "q&a")
        private val aiTerms = Regex("(?<![a-z0-9])(llm|ai|machine-learning|deep-learning|rag|langchain|ollama|agent|generative-ai|nlp)(?![a-z0-9])",
            RegexOption.IGNORE_CASE)
        private val cacheLock = Mutex()
        private val positiveReactions = setOf("+1", "thumbs_up", "heart", "hooray", "rocket")
        private val codeBlock = Regex("(?ms)^ {0,3}(`{3,}|~{3,})[^\\n]*\\n.*?(?:^ {0,3}\\1[`~]*[ \\t]*(?=\\r?$)|\\z)")
        private val mention = Regex("(?<![\\w@.+-])@[A-Za-z0-9_-]+(?:/[A-Za-z0-9_-]+)?")
        private val discussionSearch = """
            query(${'$'}query: String!, ${'$'}limit: Int!) {
              search(type: DISCUSSION, query: ${'$'}query, first: ${'$'}limit) {
                discussionCount
                nodes { ... on Discussion {
                  id title url createdAt body upvoteCount author { login __typename }
                  comments(first: 1) { totalCount }
                  reactions(first: 1) { totalCount }
                  category { name }
                  repository { nameWithOwner isPrivate } labels(first: 100) { nodes { name } }
                } }
              }
            }
        """.trimIndent()
        private val discussionReactions = """
            query(${'$'}id: ID!, ${'$'}cursor: String) {
              node(id: ${'$'}id) { ... on Discussion {
                reactions(first: 100, after: ${'$'}cursor) {
                  nodes { content user { login __typename } }
                  pageInfo { hasNextPage endCursor }
                }
              } }
            }
        """.trimIndent()
        private val discussionComments = """
            query(${'$'}id: ID!) {
              node(id: ${'$'}id) { ... on Discussion {
                comments(first: 10) { nodes {
                  body author { login __typename } upvoteCount reactions(first: 1) { total_count: totalCount }
                } }
              } }
            }
        """.trimIndent()

        internal fun repositoryFromUrl(url: String): String? {
            val parsed = url.toHttpUrlOrNull() ?: return null
            val parts = parsed.pathSegments
            if (parsed.host != "github.com" || parts.size < 4 || parts[2] !in setOf("issues", "discussions")) return null
            val repo = parts.take(2)
            return repo.joinToString("/").takeIf { repo.all { segment -> segment.matches(Regex("[A-Za-z0-9_.-]+")) } }
        }

        internal fun evidenceLabel(url: String): String =
            repositoryFromUrl(url)?.let { "GitHub 이슈 · $it" } ?: "GitHub 이슈"
    }
}
