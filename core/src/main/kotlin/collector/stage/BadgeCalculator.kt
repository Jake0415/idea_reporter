package collector.stage

import collector.config.PipelineConfig
import collector.model.*
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class BadgeCalculator(private val config: PipelineConfig.Badges) {
    fun badges(cited: List<Signal>, topics: List<TopicHistory>, snapshots: List<MetricSnapshot>,
               now: Instant, today: LocalDate): List<String> {
        return buildList {
            if (cited.flatMap { listOf(it.item.source) + it.item.alsoSeenIn }.distinct().size >= 2) add("여러 곳에서 화제")
            if (cited.any { signal ->
                topics.filter { it.topicKey == signal.classification.topicKey }
                    .map { LocalDate.parse(it.runDate) }
                    .filter { it in today.minusDays(config.streakDays.toLong() - 1)..today }
                    .distinct().size >= config.streakMin
            }) add("연속 등장")
            if (cited.any { rising(it.item, snapshots, now) }) add("급상승")
        }
    }

    private fun rising(item: Item, snapshots: List<MetricSnapshot>, now: Instant): Boolean {
        val hours = Duration.between(item.publishedAt, now).seconds / 3600.0
        if (hours <= 0.0) return false
        return when (item.source) {
            "hn" -> item.rawScore / hours >= config.hnPointsPerHour
            "github" -> {
                val cutoff = now.minus(Duration.ofHours(20))
                val prior = snapshots.filter { it.itemKey == item.key && it.at <= cutoff }.maxByOrNull { it.at }
                val starsPerDay = if (prior == null) item.rawScore * 24 / hours else
                    (item.rawScore - prior.rawScore) * 86400.0 / Duration.between(prior.at, now).seconds.coerceAtLeast(1)
                starsPerDay >= config.githubStarsPerDay
            }
            else -> false
        }
    }
}
