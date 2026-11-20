package collector.trend

object CandidateMarker {
    fun matches(growth: Double?, isNew: Boolean, demand: Int, sources: Int, overheated: Boolean,
                currentCount: Int, currentDays: Int, config: TrendConfig = TrendConfig.defaults): Boolean =
        currentCount >= config.risingCount && currentDays >= 2 &&
            (isNew || growth != null && growth >= config.candidateGrowth) &&
            demand >= config.candidateDemand && sources >= config.candidateSources && !overheated
}
