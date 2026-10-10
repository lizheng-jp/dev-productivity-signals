# 评估问题清单（prometheus/prometheus，v1 语料）

共 55 个问题：35 个答得了（20 个换说法、15 个用原文关键词），20 个答不了。
答得了的问题下面列出参考答案的关键事实，Agent 的回答要覆盖这些事实才算答对。

- **关键词**：问题里用了原文的词，比如函数名
- **换说法**：故意不用原文的词，考查语义理解
- **答不了**：语料里没有答案，Agent 应该回答“证据不足”

## 一、答得了的问题（35 个）

**1. [换说法]** Why do the service discovery refresh failure metrics vanish after reloading the config, until Prometheus is restarted?
   - 事实 1：A reload deleted the per-job prometheus_sd_refresh_* series even though a discovery provider for that job was still running; the bug came from #17614.
   - 事实 2：It happens when a job's discovery settings change (for example the http_sd url) or the job is renamed, and the series only come back after a restart.
   - 事实 3：PR #19878 fixes it: providers remember the job name they were created for, and a reload deletes refresh metrics only for cancelled providers with no other running provider of the same mechanism and job.

**2. [关键词]** Should samples produced by a subquery be counted again in the TotalSamples query statistic?
   - 事实 1：PR #19165 counted the samples processed inside a subquery in the parent's TotalSamples, which previously under-reported subquery work.
   - 事实 2：Reviewers objected that also counting the subquery's materialized output is inconsistent with range vector selectors, whose function output is not counted.
   - 事实 3：PR #19873, from a maintainer, proposes not counting materialized subquery points again, because inner consumption is already merged into the parent and vector selectors are not charged either.
   - 事实 4：The discussion does not show a final merged decision.

**3. [换说法]** The template helper that strips domain names cuts IPv6 addresses whose interface zone contains a dot
   - 事实 1：stripDomain used net.ParseIP, which rejects addresses with a zone identifier, so such addresses were treated as hostnames and cut at the first dot (fe80::1%eth0.100 became fe80::1%eth0).
   - 事实 2：PR #19908 switches the check to netip.ParseAddr, which accepts zones; hostname handling is unchanged.

**4. [关键词]** What happens when BucketFraction gets an empty bucket list?
   - 事实 1：It panics with index out of range [-1] for a nil or empty slice, because it reads the last bucket before checking the length.
   - 事实 2：PR #19944 makes it return NaN for nil and empty slices, mirroring the BucketQuantile fix (#19927).
   - 事实 3：The PromQL caller already skips empty bucket lists, so no query crash was demonstrated.

**5. [关键词]** Why did the BucketQuantile empty-slice fix not claim any user-facing PromQL failure?
   - 事实 1：Current PromQL call sites already guard against empty bucket inputs, so the panic affected only the exported helper's contract, not query evaluation.
   - 事实 2：The fix returns NaN for nil and empty slices, as the helper already documented for fewer than two buckets.

**6. [换说法]** How were the OpenMetrics 2.0 parser follow-up items split across pull requests to keep reviews small?
   - 事实 1：The work was split into three PRs of roughly 200-300 lines: numeric validation items 6, 7 and 8 (#19746); lexer, label, exemplar, metadata and option items 1-5, 9-11 and 13 (#19748); and zero-allocation parsing item 12 (#19747).
   - 事实 2：Items 6, 7 and 8 were grouped because they are the same kind of bug in the composite value parsing path.
   - 事实 3：Field-order enforcement (item 14) was split out of #19747 to keep that PR limited to performance with no behaviour change.

**7. [换说法]** A negative histogram count in the OM2 text parser ends up as an enormous unsigned number
   - 事实 1：A negative count or zero_count is cast to uint64 in buildNativeHistogram and wraps around, for example -5 becomes 18446744073709551611.
   - 事实 2：The tracking issue lists it as item 6: validate count and zero_count are non-negative before the cast; gauge gcount hits the same cast.

**8. [换说法]** Proposal to let operators correct a metric's type, unit and help text instead of trusting the exporter
   - 事实 1：Issue #19832 proposes letting operators rewrite TYPE, UNIT and HELP metadata, which today cannot be changed while labels can be relabelled freely.
   - 事实 2：It matters because with type-and-unit labels, metadata becomes part of series identity and PromQL behaviour; use cases include untyped exporters, missing units, OTLP/remote-write parity and huge HELP strings.
   - 事实 3：The suggested direction is option A (post-relabel __type__/__unit__ are authoritative) plus option B (a family-level metadata_relabel_configs stage for HELP).
   - 事实 4：It is still a request for feedback, not a decided design.

**9. [关键词]** What are the drawbacks of fixing metadata by relabeling __type__ and __unit__?
   - 事实 1：The metadata record never sees relabelling, so series labels, the metadata WAL record and /api/v1/metadata can disagree.
   - 事实 2：Type set per series clashes with family-level metadata: one part of a classic histogram family can get a different __type__ than the others.
   - 事实 3：HELP has no label form, so it cannot be set or dropped this way.
   - 事实 4：Type and unit values are not validated (typos like guage are accepted) and the change does not reach the OTLP or remote-write receive paths.
   - 事实 5：Renaming a metric through relabelling silently drops its metadata.

**10. [关键词]** Has the NHCBParser start timestamp optimisation been completed, and which pull requests did it?
   - 事实 1：Yes, it is done.
   - 事实 2：#19446 calls StartTimestamp() only when start-timestamp parsing is requested; #17156 makes the protobuf parser convert to NHCB directly; #19880 does the same for the OM2 parser (39-63% less CPU).
   - 事实 3：The relabel fast path #18327 is still open.

**11. [换说法]** Docker Swarm discovery never fills container labels; is an extra API call per task acceptable?
   - 事实 1：The Swarm tasks API returns empty container labels, so __meta_dockerswarm_container_label_* never populates; PR #19284 adds a container inspect call per task to fill them.
   - 事实 2：The cost is one extra Docker API call per task per refresh, and docker-socket-proxy setups need inspect permission; the author asked whether it should be opt-in.
   - 事实 3：No maintainer decision on the cost appears in the discussion.

**12. [换说法]** Replacing removed labels with a whole-series hash could hide duplicate label set errors in queries
   - 事实 1：If a query destroys the distinguishing label (for example with label_replace), it should fail with 'vector cannot contain metrics with the same labelset', but a whole-series hash keeps the series distinct and hides that error.
   - 事实 2：The suggested alternative is to hash only the removed labels, so duplicates are still detected.
   - 事实 3：The PR author says his engine avoids the problem by checking the original hash only when labels collide after removal.

**13. [换说法]** Why keep the original labels hash as an integer rather than encoding it into a label?
   - 事实 1：The hash is already stored as a uint64; encoding it as a label string wastes bytes even with base-91 style encodings.
   - 事实 2：Comparing uint64 values is much faster than comparing strings, a large gain for Thanos deduplicating millions of series.
   - 事实 3：The trade-off is extra code and a hole in the everything-is-a-label model.

**14. [换说法]** OTel histograms with an explicit infinite bucket boundary write the +Inf bucket twice
   - 事实 1：The OTel SDK accepts +Inf as an explicit bound, and the translator then wrote le="+Inf" twice for the same series and timestamp: once from the bounds loop and once synthesized from the count.
   - 事实 2：PR #19425 skips the infinite bound in the loop, lets the count carry the +Inf bucket, and emits a warning.

**15. [关键词]** Should the OTLP translator reject an explicit +Inf bound like the NHCB path does, or just warn?
   - 事实 1：The PR normalizes the +Inf bound and warns instead of rejecting, so the histogram keeps flowing and no already-written sample changes.
   - 事实 2：The warning was added because the NHCB path rejects an explicit +Inf bound, and silently repairing it would be inconsistent.
   - 事实 3：The author left reject-versus-warn to the maintainers; no decision is recorded.

**16. [换说法]** A test fixture whose histogram count does not match the sum of its bucket counts
   - 事实 1：TestFromMetrics' histogram_1 fixture had count 15 but bucket counts 3, 11, 0 summing to 14; the sum of 155 showed one observation belonged in the overflow bucket, which was set to 1.
   - 事实 2：Expected native histograms in that block are now checked with Validate so a non-conforming fixture fails the test.
   - 事实 3：The TestTemporality exponential fixtures were also invalid: count 1 with no buckets.

**17. [关键词]** Were the TestTemporality exponential histogram fixtures with no buckets intentional?
   - 事实 1：No; the maintainer said it did not look deliberate.
   - 事实 2：Commit a89fd357b added a minimal bucket to those fixtures and the same Validate check.

**18. [关键词]** Should the promtool lint about scrape interval and lookback delta also include the scrape timeout?
   - 事实 1：PR #19911 proposes flagging scrape_interval + scrape_timeout >= lookback_delta, because a slow scrape can let the up series disappear and reset alert state.
   - 事实 2：The maintainer was sceptical: samples are stamped at scrape start so such configurations can be valid, and a lint should flag only problems fixable with a simple change.
   - 事实 3：No decision to change the lint is recorded.

**19. [换说法]** Is there a command to scan stored data for classic histograms whose buckets contradict each other?
   - 事实 1：Yes: an experimental promtool tsdb check-histograms command, added in PR #19427.
   - 事实 2：It reports non-monotonic buckets, le="+Inf" disagreeing with _count, unparsable le values, conflicting duplicate le spellings and missing +Inf buckets, and exits non-zero on findings.
   - 事实 3：Native histograms are out of scope because they are validated at append.

**20. [换说法]** promtool series and label queries cannot be used with tenant headers
   - 事实 1：promtool query series and query labels did not pass custom headers, so header-based multi-tenancy such as X-Scope-OrgID did not work.
   - 事实 2：PR #19335 adds --header to both commands.

**21. [关键词]** Exemplars disappear after compaction followed by a restart
   - 事实 1：Exemplars are not stored in blocks and are recovered from WAL replay; compaction could drop a series identity while its exemplar record remained, so the exemplar was lost on restart.
   - 事实 2：PR #19940 keeps a series identity until its latest exemplar falls below the replay cutoff.

**22. [换说法]** Restoring from a memory snapshot forgets when series references expire
   - 事实 1：Snapshot recovery lost expiry timestamps for series references no longer in the head, so a later checkpoint could drop series records that retained samples need.
   - 事实 2：PR #19590 persists expiries in snapshot records and restores them before WAL replay; older snapshots fall back to full WAL replay.

**23. [换说法]** What breaking effects came with upgrading the Go client library to 1.25?
   - 事实 1：Query, QueryRange, Series, LabelNames and LabelValues now return Infos annotations in addition to Warnings.
   - 事实 2：client_golang now requires Go 1.26.
   - 事实 3：It also bumps prometheus/common to v0.72.0 and procfs to v0.22.0.

**24. [换说法]** Discovering Amazon managed RabbitMQ brokers as scrape targets
   - 事实 1：PR #19750 adds an MQ role to AWS service discovery for Amazon MQ (RabbitMQ and ActiveMQ).
   - 事实 2：RabbitMQ on Amazon MQ now exposes Prometheus metrics, so brokers can be discovered without a third-party discovery service or hard-coded targets.

**25. [关键词]** Printing inf ^ 2 gives a query that parses back with a different meaning
   - 事实 1：^ binds tighter than a unary sign, and Inf prints as +Inf, so inf ^ 2 printed as +Inf ^ 2, which parses back as +(Inf ^ 2).
   - 事实 2：PR #19821 puts a signed left operand of ^ in parentheses, printing (+Inf) ^ 2; the web UI fix is #19823.

**26. [关键词]** group_right swaps operands but the fill defaults stay on the wrong side
   - 事实 1：group_right swaps operands internally but did not swap the fill defaults, so fill_left/fill_right filled the wrong side (giving 1 and 25 instead of -1 and 23 in the example).
   - 事实 2：PR #19939 swaps the fill pointers together with the vectors.

**27. [换说法]** Handling queries during a migration where both classic and custom-bucket native histograms exist for one metric
   - 事实 1：PR #19881 groups stored classic and converted NHCB series by histogram identity, and at timestamps where a classic sample exists it shadows the converted NHCB samples to avoid duplicate buckets.
   - 事实 2：Disjoint time ranges across a migration cutover are merged into one continuous series, preferring live samples over staleness markers.
   - 事实 3：The PR is a draft chained on #18048.

**28. [换说法]** Moving the remote write receiver to the new appender interface
   - 事实 1：PR #19812 migrates the Remote Write 1.0 and 2.x receiver to AppenderV2 and requires ExemplarAppenderV2, returning HTTP 500 if it is unsupported.
   - 事实 2：It is an alternative to #19457.

**29. [换说法]** Does DNS service discovery resolve .local multicast names?
   - 事实 1：No, dns_sd_config does not resolve mDNS names; the planned change only documents this and adds a test, without implementing mDNS.

**30. [关键词]** Created timestamp is ignored when the _created line lists labels in a different order
   - 事实 1：The start timestamp lookup keyed on the source label order, so a _created line with labels in a different order returned 0.
   - 事实 2：PR #19942 sorts the label pairs before hashing.

**31. [换说法]** Exporters that sort histogram lines alphabetically break conversion to native histograms
   - 事实 1：NHCBParser expects all series of one classic histogram to be contiguous; alphabetically sorted output produces incomplete histograms and drops _sum lines.
   - 事实 2：That ordering is allowed by the Prometheus text format but forbidden by OpenMetrics.
   - 事实 3：It is a known issue left unfixed because a fix adds overhead for a niche case; PR #19050 only adds a test documenting it.

**32. [关键词]** Should delta-to-cumulative conversion for OTLP be deprecated or removed right away?
   - 事实 1：There are two competing PRs: #19950 deprecates the otlp-deltatocumulative flag with a warning, and #19951 removes it immediately.
   - 事实 2：Only one will be merged after maintainer discussion; it is not decided.
   - 事实 3：A maintainer's first reaction was that removal would probably have to wait for Prometheus 4.0.

**33. [关键词]** Rule unit tests cannot repeat NaN or stale values with the x notation
   - 事实 1：The lexer read NaNx3, Infx3 and stalex3 as a single identifier, so they failed to parse.
   - 事实 2：PR #19972 makes Inf, NaN and stale stop before a following x, so stalex3 and NaNx4 work in test series.

**34. [关键词]** histogram_quantile picks the wrong bucket when the histogram sum is NaN
   - 事实 1：When a native histogram's sum is NaN, the scan for NaN observations overwrote the selected bucket, so interpolation used the last bucket (about 3.03 instead of 1.52 in the example).
   - 事实 2：PR #19973 preserves the selected bucket.

**35. [换说法]** Plan to switch Prometheus's own internal summaries to native histograms
   - 事实 1：Issue #19976 proposes migrating all remaining internal Summary and Histogram metrics to native histograms.
   - 事实 2：The issue is a one-line proposal with no further detail or timeline.

## 二、答不了的问题（20 个）

**36. [答不了（明显无关）]** Why was the Thanos sidecar removed from the official Helm chart?

**37. [答不了（明显无关）]** Kubernetes endpoint slice discovery leaking memory after many pod restarts

**38. [答不了（明显无关）]** Did maintainers agree to publish Windows ARM64 release binaries?

**39. [答不了（明显无关）]** Alertmanager silences expiring early after a cluster failover

**40. [答不了（明显无关）]** Why was the default scrape interval changed from one minute to fifteen seconds?

**41. [答不了（贴近已有话题）]** What benchmark numbers showed the cost of the extra Docker Swarm container inspect call?
   - 为什么答不了：PR 19284 asks whether the cost is acceptable but gives no measurements

**42. [答不了（贴近已有话题）]** How much smaller did chunks get with the XOR2 window selection change?
   - 为什么答不了：PR 19962 mentions benchmarks but its indexed text has no results

**43. [答不了（贴近已有话题）]** Was the experimental fill_left / fill_right modifier promoted to stable?
   - 为什么答不了：PR 19939 fixes the modifier; nothing about promotion

**44. [答不了（贴近已有话题）]** Did the client_golang 1.25 upgrade change the default histogram buckets?
   - 为什么答不了：PR 19913 lists other effects only

**45. [答不了（贴近已有话题）]** Why does the remote write compliance test time out in CI?
   - 为什么答不了：PR 19911 only says it timed out and looks unrelated

**46. [答不了（贴近已有话题）]** How should alerting rules be migrated when a scrape job is renamed?
   - 为什么答不了：PR 19878 covers SD metrics on rename, not alert rules

**47. [答不了（贴近已有话题）]** Which Prometheus release made the OpenMetrics 2.0 parser the default for scrapes?
   - 为什么答不了：issue 19745 tracks parser follow-ups, not a default switch

**48. [答不了（贴近已有话题）]** What were the TotalSamples numbers on real production dashboards after the subquery change?
   - 为什么答不了：PR 19165/19873 discuss semantics, no production data

**49. [答不了（明显无关）]** Does Prometheus support scraping targets over HTTP/3?

**50. [答不了（明显无关）]** Why did remote write switch its compression to zstd?

**51. [答不了（明显无关）]** How do I configure TLS client certificates for Consul service discovery?

**52. [答不了（明显无关）]** What retention size is recommended for a 1 TB disk?

**53. [答不了（明显无关）]** How did the new UI implement dark mode?

**54. [答不了（明显无关）]** Why is PromQL query concurrency limited to 20 by default?

**55. [答不了（明显无关）]** What caused WAL corruption on ARM machines after a power loss?
