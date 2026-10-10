# 标注抽查（prometheus/prometheus，2026-07-11 ~ 2026-10-08）

## Claude 抽查结论

- 40 条全部对照全文核对过：每条都看了标为相关的文档能不能回答，也看了检索前 8 里没标的文档有没有漏标。
- 改了 2 处：第 7 条补标 issue 正文（第一轮遗漏），第 6 条补标一条部分相关的评论。
- 只有 **4、6、7 三条** 需要你判断，已标 ⚠️。其余 37 条我认为没问题。
- 检索前几名经常出现几条没信息量的评论，它们不相关是对的：`issue:19887:note:6059195968`（“I'd like to take this issue”）、`mr:19165:comment:5922338679`（“could you elaborate?”）、`issue:18324:note:5286126719`（认领任务）。
- 5 条答不了的问题都确认过，索引里确实没有答案。第 40 条“默认抓取间隔改动”会召回 `mr:19911`（讲的是抓取间隔和 lookback），但它答不了这个问题。

---

## 1. [paraphrase] Why do the service discovery refresh failure metrics vanish after reloading the config, until Prometheus is restarted?
> Claude：确认
- ✅ [`issue:19887:description`](https://github.com/prometheus/prometheus/issues/19887) `prometheus_sd_refresh_*` per-config metrics do not properly reset on reload when scrape jobs are modified ### What did you do? After https://github.com/prometh…
- ✅ [`mr:19878:description`](https://github.com/prometheus/prometheus/pull/19878) discovery: keep refresh metrics for running providers on reload this fixes a bug from #17614 where refresh metrics got deleted on reload even though a discovere…
- ✅ [`mr:19878:review:5399204007`](https://github.com/prometheus/prometheus/pull/19878#pullrequestreview-5399204007) Checked `baaf9b5d` against the provider reuse and rename paths. The deferred cleanup preserves refresh series while a same-mechanism provider remains, and the a…
- ✅ [`mr:19878:review:5458227034`](https://github.com/prometheus/prometheus/pull/19878#pullrequestreview-5458227034) {"body":"Reviewed the whole change against the current ApplyConfig/\registerProviders flow (CI is green across the board).\n\n**What checks out**\n\n- **Mechani…
- 检索前 3：`mr:19878:description` ✅ (0.84), `issue:19887:description` ✅ (0.83), `issue:19887:note:6059195968` (0.82)

## 2. [lexical] Should samples produced by a subquery be counted again in the TotalSamples query statistic?
> Claude：确认
- ✅ [`mr:19165:description`](https://github.com/prometheus/prometheus/pull/19165) promql: count subquery samples in total sample stats ## Summary Count the samples processed by a subquery's inner evaluation in the parent query's `TotalSamples…
- ✅ [`mr:19165:comment:5904285019`](https://github.com/prometheus/prometheus/pull/19165#issuecomment-5904285019) @bboreham [wrote](https://github.com/prometheus/prometheus/pull/19165#pullrequestreview-4959750511): > I am not sure that counting computed subquery samples the…
- ✅ [`mr:19165:comment:5911963730`](https://github.com/prometheus/prometheus/pull/19165#issuecomment-5911963730) This comes down to a question of what TotalSamples is actually counting, and what it means to execute a subquery. The original argument was that `max_over_time(…
- ✅ [`mr:19165:comment:5985633761`](https://github.com/prometheus/prometheus/pull/19165#issuecomment-5985633761) I think I'm missing something sorry. My understanding is this: let's say `foo` has a sample every minute, exactly on the minute (ie. at 12:00:00, 12:01:00 etc.)…
- ✅ [`mr:19165:comment:6034920307`](https://github.com/prometheus/prometheus/pull/19165#issuecomment-6034920307) The cost is real, subquery result remains materialized while the outer function copies its active window. But the same is true for vectors selectors, which do n…
- ✅ [`mr:19873:description`](https://github.com/prometheus/prometheus/pull/19873) promql: do not count subquery materialized samples in total samples TotalSamples measures logical underlying sample consumption. Subqueries already merge that c…
- 检索前 3：`mr:19165:comment:5922338679` (0.86), `mr:19165:comment:5911963730` ✅ (0.85), `mr:19873:description` ✅ (0.81)

## 3. [paraphrase] The template helper that strips domain names cuts IPv6 addresses whose interface zone contains a dot
> Claude：确认
- ✅ [`issue:19897:description`](https://github.com/prometheus/prometheus/issues/19897) template: stripDomain truncates IPv6 zone identifiers containing dots ### What did you do? At main `5ba0e8648a4314e7d12adc6dbeb34b9d76cafab2`, I expanded `{{ . …
- ✅ [`mr:19908:description`](https://github.com/prometheus/prometheus/pull/19908) template: stripDomain truncates IPv6 zone identifiers containing dots ## Problem `stripDomain` used `net.ParseIP` to detect IP literals. `net.ParseIP` rejects a…
- 检索前 3：`mr:19908:description` ✅ (0.86), `issue:19897:description` ✅ (0.84), `issue:2537:note:6065515151` (0.64)

## 4. [lexical] What happens when BucketFraction gets an empty bucket list?
> Claude：确认。`mr:19927:comment:6015925239`（“Please do BucketFraction as well”）只是提出要修，不说明会发生什么，未标。⚠️ 请你判断
- ✅ [`issue:19943:description`](https://github.com/prometheus/prometheus/issues/19943) promql: BucketFraction panics for nil or empty buckets ### What did you do? Called `promql.BucketFraction(0, 1, nil)`. A non-nil empty `promql.Buckets{}` produc…
- ✅ [`mr:19944:description`](https://github.com/prometheus/prometheus/pull/19944) promql: handle empty input in BucketFraction Fixes #19943 The exported `BucketFraction` helper panics with `index out of range [-1]` for a nil or empty bucket s…
- 检索前 3：`issue:19943:description` ✅ (0.73), `mr:19944:description` ✅ (0.71), `mr:19927:description` (0.65)

## 5. [lexical] Why did the BucketQuantile empty-slice fix not claim any user-facing PromQL failure?
> Claude：确认
- ✅ [`mr:19927:description`](https://github.com/prometheus/prometheus/pull/19927) promql: handle empty input in BucketQuantile The exported `BucketQuantile` helper documents `NaN` for fewer than two buckets, but an empty slice currently panic…
- 检索前 3：`mr:19927:description` ✅ (0.85), `mr:19944:description` (0.85), `issue:19943:description` (0.82)

## 6. [paraphrase] How were the OpenMetrics 2.0 parser follow-up items split across pull requests to keep reviews small?
> Claude：补标了 `issue:19745:note:5762980833`：它说明第 14 项从 #19747 拆了出来，算部分回答。⚠️ 请你判断
- ✅ [`issue:19745:note:5737372368`](https://github.com/prometheus/prometheus/issues/19745#issuecomment-5737372368) ### Suggested PR Breakdown To keep reviews manageable (~200–300 lines per PR), we can split the work into three PRs: * **PR 1 (Composite Numeric Validation):** …
- ✅ [`issue:19745:note:5762980833`](https://github.com/prometheus/prometheus/issues/19745#issuecomment-5762980833) #### 14. Enforce canonical composite field ordering from the OpenMetrics 2.0 ABNF spec and fix out-of-order test fixtures - [ ] Enforce the canonical composite …
- 检索前 3：`issue:19745:note:5737372368` ✅ (0.77), `issue:19745:note:5762980833` ✅ (0.72), `mr:19050:description` (0.71)

## 7. [paraphrase] A negative histogram count in the OM2 text parser ends up as an enormous unsigned number
> Claude：补标了 issue 正文（第 6 项就是这个问题）。`issue:19745:note:5763653270` 讲的是 classic 直方图的负 count（第 15 项），不涉及无符号溢出，未标。⚠️ 请你判断
- ✅ [`issue:19745:description`](https://github.com/prometheus/prometheus/issues/19745) model/textparse: follow-up improvements and spec validations for OpenMetrics 2.0 parser Follow-up tracking issue to address the remaining open review comments f…
- ✅ [`issue:19745:note:5736573241`](https://github.com/prometheus/prometheus/issues/19745#issuecomment-5736573241) I'd like to pick up the numeric validation items — 6, 7 and 8. They are the same shape of bug (a dropped `ParseFloat` error or a missing range check) and they a…
- 检索前 3：`issue:19745:note:5763653270` (0.72), `issue:19745:description` ✅ (0.72), `mr:19868:description` (0.68)

## 8. [paraphrase] Proposal to let operators correct a metric's type, unit and help text instead of trusting the exporter
> Claude：确认
- ✅ [`issue:19832:description`](https://github.com/prometheus/prometheus/issues/19832) Proposal: Metadata manipulation — let users fix a metric's type, unit and help ### TL;DR Prometheus lets operators rewrite **labels** freely (`metric_relabel_co…
- ✅ [`issue:19832:note:5831061653`](https://github.com/prometheus/prometheus/issues/19832#issuecomment-5831061653) Some quick idea on how this could be possible: ### A. Specify relabeling of `__type__` / `__unit__` and special __help__ label (not propagated to label) Declare…
- ✅ [`issue:19832:note:5915874816`](https://github.com/prometheus/prometheus/issues/19832#issuecomment-5915874816) My take is that A+B makes sense, with the family-level stage supplying HELP and the initial type/unit values, and metric relabeling applying any per-series over…
- 检索前 3：`issue:19832:description` ✅ (0.73), `issue:19832:note:5831061653` ✅ (0.71), `issue:19745:description` (0.69)

## 9. [lexical] What are the drawbacks of fixing metadata by relabeling __type__ and __unit__?
> Claude：确认
- ✅ [`issue:19832:note:5831061653`](https://github.com/prometheus/prometheus/issues/19832#issuecomment-5831061653) Some quick idea on how this could be possible: ### A. Specify relabeling of `__type__` / `__unit__` and special __help__ label (not propagated to label) Declare…
- ✅ [`issue:19832:note:5915874816`](https://github.com/prometheus/prometheus/issues/19832#issuecomment-5915874816) My take is that A+B makes sense, with the family-level stage supplying HELP and the initial type/unit values, and metric relabeling applying any per-series over…
- 检索前 3：`issue:19832:description` (0.79), `issue:19832:note:5831061653` ✅ (0.78), `mr:19200:comment:5276752713` (0.67)

## 10. [lexical] Has the NHCBParser start timestamp optimisation been completed, and which pull requests did it?
> Claude：确认
- ✅ [`issue:18324:note:6031436266`](https://github.com/prometheus/prometheus/issues/18324#issuecomment-6031436266) This is now done. 1. `StartTimestamp()` is no longer called in `NHCBParser` unless ST parsing is requested: #19446 (thanks @prymitive). `NHCBParser` takes a `pa…
- 检索前 3：`issue:18324:note:5286126719` (0.85), `issue:18324:note:6031436266` ✅ (0.82), `mr:19881:description` (0.71)

## 11. [paraphrase] Docker Swarm discovery never fills container labels; is an extra API call per task acceptable?
> Claude：确认
- ✅ [`mr:19284:description`](https://github.com/prometheus/prometheus/pull/19284) discovery/moby: enrich task labels via container inspect (#12244) #### Which issue(s) does the PR fix: Fixes #12244 #### Release notes for end users (**ALL** co…
- ✅ [`mr:19284:comment:5224713573`](https://github.com/prometheus/prometheus/pull/19284#issuecomment-5224713573) Hi maintainers, I noticed that #12244 has now been closed via #19296, which clarifies the current Swarm task label behavior. My PR takes a different approach by…
- 检索前 3：`mr:19284:description` ✅ (0.80), `mr:19284:comment:5224713573` ✅ (0.78), `issue:19887:note:6059195968` (0.69)

## 12. [paraphrase] Replacing removed labels with a whole-series hash could hide duplicate label set errors in queries
> Claude：确认
- ✅ [`mr:19200:comment:5276752713`](https://github.com/prometheus/prometheus/pull/19200#issuecomment-5276752713) IIUC, this is being added for the functionality of `ProjectionLabels`, right? I also though about such feature a while ago. In my opinion, while using whole ser…
- ✅ [`mr:19200:comment:6056167991`](https://github.com/prometheus/prometheus/pull/19200#issuecomment-6056167991) If I understood your comment correctly, in my $privatefork of thanos-io/promql-engine I avoided this issue by making the engine aware of the OriginalLabelsHash(…
- 检索前 3：`mr:19200:comment:5276752713` ✅ (0.82), `mr:19200:description` (0.77), `issue:19832:note:5915874816` (0.69)

## 13. [paraphrase] Why keep the original labels hash as an integer rather than encoding it into a label?
> Claude：确认
- ✅ [`mr:19200:description`](https://github.com/prometheus/prometheus/pull/19200) storage: add OriginalLabelsHash() method to SeriesSet Add a way to capture the original hash of labels that is "out of bound" of the labels model. This is becau…
- ✅ [`mr:19200:comment:6056167991`](https://github.com/prometheus/prometheus/pull/19200#issuecomment-6056167991) If I understood your comment correctly, in my $privatefork of thanos-io/promql-engine I avoided this issue by making the engine aware of the OriginalLabelsHash(…
- 检索前 3：`mr:19200:comment:6056167991` ✅ (0.79), `mr:19200:description` ✅ (0.78), `issue:19832:note:5831061653` (0.67)

## 14. [paraphrase] OTel histograms with an explicit infinite bucket boundary write the +Inf bucket twice
> Claude：确认
- ✅ [`mr:19425:description`](https://github.com/prometheus/prometheus/pull/19425) otlptranslator: keep le="+Inf" equal to count when a bound is +Inf The OTel Go SDK accepts `+Inf` as an explicit bucket boundary: `AggregationExplicitBucketHist…
- ✅ [`mr:19425:comment:5315824177`](https://github.com/prometheus/prometheus/pull/19425#issuecomment-5315824177) One design question the description doesn't settle: the NHCB path rejects an explicit `+Inf` bound outright, in `checkHistogramCustomBounds`, while this path no…
- 检索前 3：`mr:19425:description` ✅ (0.79), `issue:19745:description` (0.71), `issue:19745:note:5763653270` (0.71)

## 15. [lexical] Should the OTLP translator reject an explicit +Inf bound like the NHCB path does, or just warn?
> Claude：确认。PR 正文只说“会发 warning”，没讨论该拒绝还是该警告，未标
- ✅ [`mr:19425:comment:5315824177`](https://github.com/prometheus/prometheus/pull/19425#issuecomment-5315824177) One design question the description doesn't settle: the NHCB path rejects an explicit `+Inf` bound outright, in `checkHistogramCustomBounds`, while this path no…
- 检索前 3：`mr:19425:comment:5315824177` ✅ (0.79), `mr:19425:description` (0.76), `mr:19429:comment:5302664198` (0.71)

## 16. [paraphrase] A test fixture whose histogram count does not match the sum of its bucket counts
> Claude：确认
- ✅ [`mr:19429:description`](https://github.com/prometheus/prometheus/pull/19429) otlptranslator: make the histogram_1 test fixture OTLP-conforming `TestFromMetrics`'s `histogram_1` fixture sets `count` to 15 with `bucket_counts` of `{3, 11, …
- 检索前 3：`mr:19429:description` ✅ (0.77), `mr:19429:comment:5727946567` (0.76), `issue:19745:description` (0.75)

## 17. [lexical] Were the TestTemporality exponential histogram fixtures with no buckets intentional?
> Claude：确认
- ✅ [`mr:19429:comment:5302664198`](https://github.com/prometheus/prometheus/pull/19429#issuecomment-5302664198) @linasm — I noticed that you added the `Validate()` assertion in #19391 and the NaN bound rejection in #17287, so this question is for you. The second commit he…
- ✅ [`mr:19429:comment:5727686755`](https://github.com/prometheus/prometheus/pull/19429#issuecomment-5727686755) >I left them alone because those tests are about temporality — is that minimal input deliberate, or worth a separate change? Does not feel like deliberate. Feel…
- ✅ [`mr:19429:comment:5727946567`](https://github.com/prometheus/prometheus/pull/19429#issuecomment-5727946567) Done in a89fd357b: added a minimal bucket to the `TestTemporality` exponential-histogram fixtures and the same `Validate()` check `TestFromMetrics` already has,…
- 检索前 3：`mr:19429:comment:5727946567` ✅ (0.84), `mr:19429:description` (0.76), `issue:19745:description` (0.71)

## 18. [lexical] Should the promtool lint about scrape interval and lookback delta also include the scrape timeout?
> Claude：确认
- ✅ [`mr:19911:description`](https://github.com/prometheus/prometheus/pull/19911) promtool: account for scrape timeout in lookback lint Related to #19909 The `too-long-scrape-interval` lint only checked the scrape interval against the lookbac…
- ✅ [`mr:19911:comment:6032882601`](https://github.com/prometheus/prometheus/pull/19911#issuecomment-6032882601) I'm not sure about this. It's changing an existing lint test into something else. The difference from the existing lint test is because the scrape is inserted a…
- ✅ [`mr:19911:comment:6034610485`](https://github.com/prometheus/prometheus/pull/19911#issuecomment-6034610485) Hi @dgl thank you for a reply. You are totally right. Should I drop this code change and document the behavior instead, or do you think there is a better fix fo…
- 检索前 3：`mr:19911:description` ✅ (0.91), `mr:19911:comment:6032882601` ✅ (0.81), `issue:19887:note:6059195968` (0.69)

## 19. [paraphrase] Is there a command to scan stored data for classic histograms whose buckets contradict each other?
> Claude：确认
- ✅ [`mr:19427:description`](https://github.com/prometheus/prometheus/pull/19427) promtool: add tsdb check-histograms This adds an `[Experimental]` subcommand that scans a TSDB for classic histograms whose stored series contradict each other …
- 检索前 3：`issue:19745:note:5763653270` (0.73), `mr:19427:description` ✅ (0.73), `issue:19745:description` (0.72)

## 20. [paraphrase] promtool series and label queries cannot be used with tenant headers
> Claude：确认
- ✅ [`mr:19335:description`](https://github.com/prometheus/prometheus/pull/19335) promtool: support custom headers for series and labels queries ## Summary `promtool query instant` and `promtool query range` already support `--header`, but `q…
- ✅ [`mr:19335:comment:5212410242`](https://github.com/prometheus/prometheus/pull/19335#issuecomment-5212410242) Thanks, I think it might make sense to actually move this flag to the queryCmd itself, we already define `--http.confg.file` at that level.…
- 检索前 3：`mr:19335:description` ✅ (0.84), `issue:19832:note:5831061653` (0.70), `mr:19200:comment:5276752713` (0.69)

## 21. [lexical] Exemplars disappear after compaction followed by a restart
> Claude：确认
- ✅ [`mr:19940:description`](https://github.com/prometheus/prometheus/pull/19940) tsdb: preserve exemplars across compaction and restart <!-- - Please give your PR a title in the form "area: short description". For example "tsdb: reduce disk …
- ✅ [`mr:19940:comment:6019143220`](https://github.com/prometheus/prometheus/pull/19940#issuecomment-6019143220) Can you also fix AppenderV2?…
- ✅ [`mr:19940:comment:6019443293`](https://github.com/prometheus/prometheus/pull/19940#issuecomment-6019443293) > Can you also fix AppenderV2? Fixed in https://github.com/prometheus/prometheus/pull/19940/commits/f9e322f449d787ac431bf7efa05d7b28f14b2ac5.…
- 检索前 3：`mr:19940:description` ✅ (0.78), `mr:19812:description` (0.66), `issue:19745:note:6015695165` (0.64)

## 22. [paraphrase] Restoring from a memory snapshot forgets when series references expire
> Claude：确认。`mr:19940:description` 讲的是 exemplar 保护，只顺带提到 snapshot，未标
- ✅ [`mr:19590:description`](https://github.com/prometheus/prometheus/pull/19590) tsdb: preserve WAL expiries in memory snapshots #### Which issue(s) does the PR fix: Addresses the full-TSDB memory-snapshot cases in #15574. Agent-mode reports…
- 检索前 3：`mr:19590:description` ✅ (0.78), `mr:19940:description` (0.75), `mr:19812:description` (0.73)

## 23. [paraphrase] What breaking effects came with upgrading the Go client library to 1.25?
> Claude：确认
- ✅ [`mr:19913:description`](https://github.com/prometheus/prometheus/pull/19913) Upgrade client_golang to 1.25.0 Tests v1.25.0 (final) of client_golang. The RC was validated in https://github.com/prometheus/prometheus/pull/19913/ with no iss…
- ✅ [`mr:19913:comment:5999080222`](https://github.com/prometheus/prometheus/pull/19913#issuecomment-5999080222) Looks like a known breaking changes, so all good 💪🏽…
- 检索前 3：`mr:19913:description` ✅ (0.76), `mr:19911:comment:6034610485` (0.69), `mr:19942:description` (0.66)

## 24. [paraphrase] Discovering Amazon managed RabbitMQ brokers as scrape targets
> Claude：确认
- ✅ [`mr:19750:description`](https://github.com/prometheus/prometheus/pull/19750) discovery/aws: Add MQ Role <!-- - Please give your PR a title in the form "area: short description". For example "tsdb: reduce disk usage by 95%" - Please sign …
- ✅ [`mr:19750:comment:5741945420`](https://github.com/prometheus/prometheus/pull/19750#issuecomment-5741945420) @sysadmind @SuperQ Would you be able to take a look when you get a minute? This adds an MQ role to the AWS SD.…
- 检索前 3：`issue:19887:note:6059195968` (0.68), `mr:19750:description` ✅ (0.66), `issue:19832:note:5872597901` (0.64)

## 25. [lexical] Printing inf ^ 2 gives a query that parses back with a different meaning
> Claude：确认
- ✅ [`mr:19821:description`](https://github.com/prometheus/prometheus/pull/19821) promql/parser: parenthesise a signed left operand of ^ #### Which issue(s) does the PR fix: None filed. `^` binds tighter than a unary sign, but the printer wro…
- 检索前 3：`mr:19821:description` ✅ (0.74), `mr:19425:comment:5315824177` (0.64), `mr:19425:description` (0.64)

## 26. [lexical] group_right swaps operands but the fill defaults stay on the wrong side
> Claude：确认
- ✅ [`mr:19939:description`](https://github.com/prometheus/prometheus/pull/19939) promql: preserve fill sides in one-to-many matches When `group_right` swaps operands internally, fill defaults must swap with them. Otherwise the experimental `…
- 检索前 3：`mr:19939:description` ✅ (0.79), `mr:19911:comment:6034610485` (0.64), `issue:19745:description` (0.62)

## 27. [paraphrase] Handling queries during a migration where both classic and custom-bucket native histograms exist for one metric
> Claude：确认
- ✅ [`mr:19881:description`](https://github.com/prometheus/prometheus/pull/19881) storage: resolve classic and NHCB histogram collisions in NHCBAsClassicQuerier > [!NOTE] > This is chained with https://github.com/prometheus/prometheus/pull/18…
- 检索前 3：`issue:19976:description` (0.75), `mr:19881:description` ✅ (0.72), `issue:19745:note:5763653270` (0.71)

## 28. [paraphrase] Moving the remote write receiver to the new appender interface
> Claude：确认
- ✅ [`mr:19812:description`](https://github.com/prometheus/prometheus/pull/19812) storage/remote: migrate remote write receiver to AppenderV2 and ExemplarAppenderV2 ~Depends on #19811~ Alternative to https://github.com/prometheus/prometheus/p…
- ✅ [`mr:19812:review:5435163462`](https://github.com/prometheus/prometheus/pull/19812#pullrequestreview-5435163462) I've opened a [PR](https://github.com/prometheus/prometheus/pull/19912) to your branch with what should be metadata handling fixes. PTAL.…
- 检索前 3：`mr:19812:description` ✅ (0.78), `mr:19940:comment:6019143220` (0.67), `mr:19911:comment:5997811016` (0.64)

## 29. [paraphrase] Does DNS service discovery resolve .local multicast names?
> Claude：确认
- ✅ [`issue:2537:note:5409844598`](https://github.com/prometheus/prometheus/issues/2537#issuecomment-5409844598) Hello from Bug Scrub. We'd like to highlight the limitations of `dns_sd_config` in https://prometheus.io/docs/prometheus/latest/configuration/configuration/#dns…
- ✅ [`issue:2537:note:6065515151`](https://github.com/prometheus/prometheus/issues/2537#issuecomment-6065515151) I'll take the docs and tests machine424 asked for: a note under `dns_sd_config` that mDNS names aren't resolved, and a test in `dns_test.go`. No mDNS implementa…
- 检索前 3：`issue:2537:note:6065515151` ✅ (0.77), `issue:2537:note:5409844598` ✅ (0.68), `mr:19878:review:5399204007` (0.66)

## 30. [lexical] Created timestamp is ignored when the _created line lists labels in a different order
> Claude：确认
- ✅ [`mr:19942:description`](https://github.com/prometheus/prometheus/pull/19942) textparse: match start timestamps across label order permutations An OpenMetrics counter can expose `calls_total{a="x",b="y"} 1` followed by `calls_created{b="y…
- 检索前 3：`mr:19942:description` ✅ (0.70), `mr:19200:comment:5276752713` (0.68), `issue:19832:description` (0.66)

## 31. [paraphrase] Exporters that sort histogram lines alphabetically break conversion to native histograms
> Claude：确认。这是真实的检索失败：`mr:19050` 没进前 8
- ✅ [`mr:19050:description`](https://github.com/prometheus/prometheus/pull/19050) test(textparse): Add NHCBParser test for ungrouped series This PR adds a test that documents the negative effect on NHCB parsing of scrape targets that break th…
- 检索前 3：`issue:19745:description` (0.67), `mr:19425:comment:5315824177` (0.67), `issue:19976:description` (0.66)

## 32. [lexical] Should delta-to-cumulative conversion for OTLP be deprecated or removed right away?
> Claude：确认
- ✅ [`mr:19950:description`](https://github.com/prometheus/prometheus/pull/19950) remote/otlp: deprecate delta-to-cumulative conversion Deprecate the `otlp-deltatocumulative` feature flag in favor of native delta ingestion. The existing conve…
- ✅ [`mr:19951:description`](https://github.com/prometheus/prometheus/pull/19951) remote/otlp: remove delta-to-cumulative conversion This is a competing alternative to #19950. That PR deprecates `otlp-deltatocumulative` while preserving its i…
- ✅ [`mr:19951:comment:6034775623`](https://github.com/prometheus/prometheus/pull/19951#issuecomment-6034775623) my gut reaction is that removing d2c would probably have to wait for prom 4.0, but Bryan and I think we should go ahead and announce prom 4 right away anyway :)…
- 检索前 3：`mr:19951:description` ✅ (0.85), `mr:19950:description` ✅ (0.82), `mr:19429:comment:5302664198` (0.70)

## 33. [lexical] Rule unit tests cannot repeat NaN or stale values with the x notation
> Claude：确认
- ✅ [`mr:19972:description`](https://github.com/prometheus/prometheus/pull/19972) promql/parser: allow the expanding notation on Inf, NaN and stale #### Which issue(s) does the PR fix: None filed. In a series description, `1x3` and `_x3` expa…
- 检索前 3：`mr:19972:description` ✅ (0.76), `mr:19429:comment:5302664198` (0.70), `issue:19745:note:5736573241` (0.69)

## 34. [lexical] histogram_quantile picks the wrong bucket when the histogram sum is NaN
> Claude：确认
- ✅ [`mr:19973:description`](https://github.com/prometheus/prometheus/pull/19973) promql: preserve quantile bucket for histograms with NaN sum When a native histogram's sum is NaN, `HistogramQuantile` scans the remaining buckets to determine …
- 检索前 3：`mr:19973:description` ✅ (0.80), `mr:19944:description` (0.69), `issue:19745:note:5763653270` (0.69)

## 35. [paraphrase] Plan to switch Prometheus's own internal summaries to native histograms
> Claude：确认
- ✅ [`issue:19976:description`](https://github.com/prometheus/prometheus/issues/19976) Migrate Summary/Histograms to Native Histograms ### Proposal Migrate all remaining internal Summary and Histogram metrics to Native Histograms.…
- 检索前 3：`issue:19976:description` ✅ (0.78), `issue:19745:description` (0.73), `issue:19832:note:5872597901` (0.70)

## 36. [unanswerable] Why was the Thanos sidecar removed from the official Helm chart?
> Claude：确认
- 相关文档：**无（索引里答不了）**
- 检索前 3：`issue:19802:note:6045588473` (0.57), `mr:19200:comment:6056167991` (0.57), `issue:19745:description` (0.56)

## 37. [unanswerable] Kubernetes endpoint slice discovery leaking memory after many pod restarts
> Claude：确认
- 相关文档：**无（索引里答不了）**
- 检索前 3：`issue:19887:note:6059195968` (0.66), `issue:19745:description` (0.65), `issue:19832:note:5872597901` (0.62)

## 38. [unanswerable] Did maintainers agree to publish Windows ARM64 release binaries?
> Claude：确认
- 相关文档：**无（索引里答不了）**
- 检索前 3：`issue:19802:note:6045588473` (0.67), `mr:19940:comment:6019143220` (0.64), `mr:19590:description` (0.63)

## 39. [unanswerable] Alertmanager silences expiring early after a cluster failover
> Claude：确认
- 相关文档：**无（索引里答不了）**
- 检索前 3：`mr:19911:comment:5997811016` (0.62), `mr:19590:description` (0.61), `mr:19940:description` (0.60)

## 40. [unanswerable] Why was the default scrape interval changed from one minute to fifteen seconds?
> Claude：确认
- 相关文档：**无（索引里答不了）**
- 检索前 3：`mr:19911:comment:6032882601` (0.73), `issue:19887:note:6059195968` (0.71), `mr:19911:description` (0.70)

---

## 补充的 15 条答不了的问题（41–55）

索引里应该找不到答案。请看一眼：如果你觉得哪条其实能从“检索前 3”里找到答案，告诉我编号。

## 41. [unanswerable-near] What benchmark numbers showed the cost of the extra Docker Swarm container inspect call?
> 为什么答不了：PR 19284 asks whether the cost is acceptable but gives no measurements
- 检索前 3（Qwen3）：`mr:19284:description` (0.61), `mr:19284:comment:5224713573` (0.56), `issue:19832:note:5872597901` (0.47)

## 42. [unanswerable-near] How much smaller did chunks get with the XOR2 window selection change?
> 为什么答不了：PR 19962 mentions benchmarks but its indexed text has no results
- 检索前 3（Qwen3）：`mr:19962:description` (0.55), `mr:19165:comment:6034920307` (0.45), `mr:19924:description` (0.45)

## 43. [unanswerable-near] Was the experimental fill_left / fill_right modifier promoted to stable?
> 为什么答不了：PR 19939 fixes the modifier; nothing about promotion
- 检索前 3（Qwen3）：`mr:19939:description` (0.66), `mr:19911:comment:6034610485` (0.41), `mr:19590:description` (0.39)

## 44. [unanswerable-near] Did the client_golang 1.25 upgrade change the default histogram buckets?
> 为什么答不了：PR 19913 lists other effects only
- 检索前 3（Qwen3）：`mr:19913:description` (0.66), `mr:19971:description` (0.58), `issue:19745:note:6015695165` (0.55)

## 45. [unanswerable-near] Why does the remote write compliance test time out in CI?
> 为什么答不了：PR 19911 only says it timed out and looks unrelated
- 检索前 3（Qwen3）：`mr:19911:comment:5997811016` (0.86), `mr:19651:comment:6041958588` (0.53), `mr:19590:description` (0.50)

## 46. [unanswerable-near] How should alerting rules be migrated when a scrape job is renamed?
> 为什么答不了：PR 19878 covers SD metrics on rename, not alert rules
- 检索前 3（Qwen3）：`mr:19911:comment:6032882601` (0.59), `mr:19878:description` (0.57), `issue:19887:description` (0.56)

## 47. [unanswerable-near] Which Prometheus release made the OpenMetrics 2.0 parser the default for scrapes?
> 为什么答不了：issue 19745 tracks parser follow-ups, not a default switch
- 检索前 3（Qwen3）：`issue:19745:description` (0.62), `mr:19918:comment:6042159300` (0.58), `mr:19812:review:5435163462` (0.57)

## 48. [unanswerable-near] What were the TotalSamples numbers on real production dashboards after the subquery change?
> 为什么答不了：PR 19165/19873 discuss semantics, no production data
- 检索前 3（Qwen3）：`mr:19873:description` (0.68), `mr:19165:description` (0.66), `mr:19165:comment:5911963730` (0.64)

## 49. [unanswerable] Does Prometheus support scraping targets over HTTP/3?
- 检索前 3（Qwen3）：`issue:19832:note:5872597901` (0.55), `mr:19165:comment:5907861997` (0.53), `issue:19887:note:6059945941` (0.52)

## 50. [unanswerable] Why did remote write switch its compression to zstd?
- 检索前 3（Qwen3）：`mr:19911:comment:5997811016` (0.51), `mr:19962:description` (0.47), `mr:19812:description` (0.46)

## 51. [unanswerable] How do I configure TLS client certificates for Consul service discovery?
- 检索前 3（Qwen3）：`issue:19887:note:6059195968` (0.46), `mr:19878:review:5458227034` (0.44), `issue:2537:note:6065515151` (0.42)

## 52. [unanswerable] What retention size is recommended for a 1 TB disk?
- 检索前 3（Qwen3）：`mr:19962:description` (0.40), `mr:19940:description` (0.40), `mr:19881:description` (0.39)

## 53. [unanswerable] How did the new UI implement dark mode?
- 检索前 3（Qwen3）：`mr:17930:comment:6061985114` (0.59), `mr:19913:comment:5999080222` (0.42), `issue:19976:description` (0.41)

## 54. [unanswerable] Why is PromQL query concurrency limited to 20 by default?
- 检索前 3（Qwen3）：`mr:19873:description` (0.60), `mr:19165:description` (0.54), `issue:19943:description` (0.53)

## 55. [unanswerable] What caused WAL corruption on ARM machines after a power loss?
- 检索前 3（Qwen3）：`mr:19590:description` (0.44), `mr:19651:comment:6041958588` (0.37), `mr:19940:description` (0.35)
