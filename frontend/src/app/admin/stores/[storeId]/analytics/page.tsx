"use client";
import Link from "next/link";
import { useState } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { HourlyBars, WeekdayBars } from "@/features/admin/HourlyBars";
import { api, downloadWithAuth, errorMessage } from "@/lib/api";
import { YUT_LABEL, labelOf, rankLabel } from "@/features/labels";
import { comparisonCopy } from "@/features/admin/analytics-comparison";
import type { DetailedAnalytics, Summary } from "@/types/api";

/** 사장이 실제로 고르는 기간. 임의 날짜 선택은 이 화면에서 아직 필요하지 않다. */
const RANGES = [
  { days: 7, label: "7일" },
  { days: 30, label: "30일" },
  { days: 90, label: "90일" },
];

function isoDaysAgo(days: number) {
  const d = new Date();
  d.setDate(d.getDate() - (days - 1));
  return d.toISOString().slice(0, 10);
}

export default function AnalyticsPage() {
  const id = String(useParams().storeId);
  const [days, setDays] = useState(30);
  const [exporting, setExporting] = useState("");
  const [exportError, setExportError] = useState("");

  const summary = useQuery({
    queryKey: ["analytics", id],
    queryFn: () => api<Summary>(`/admin/stores/${id}/analytics/summary`),
  });
  const advanced = summary.data?.advancedAvailable === true;
  const detailed = useQuery({
    queryKey: ["analytics-detailed", id, days],
    queryFn: () => api<DetailedAnalytics>(`/admin/stores/${id}/analytics/detailed?from=${isoDaysAgo(days)}`),
    enabled: advanced,
  });

  if (summary.isPending)
    return (
      <AdminFrame title="통계">
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">통계를 불러오는 중</span>
          <div className="skeleton" style={{ height: 110 }} />
          <div className="skeleton" style={{ height: 220 }} />
        </div>
      </AdminFrame>
    );

  if (summary.isError)
    return (
      <AdminFrame title="통계">
        <p className="error" role="alert">
          {errorMessage(summary.error)}
        </p>
      </AdminFrame>
    );

  const issued = summary.data.issuedCoupons + summary.data.redeemedCoupons;
  const rate = issued ? Math.round((summary.data.redeemedCoupons / issued) * 100) : 0;
  const comparison = detailed.data?.comparedToPrevious
    ? comparisonCopy(detailed.data.comparedToPrevious)
    : undefined;

  return (
    <AdminFrame title="통계">
      <header className="admin-page-intro analytics-intro">
        <div>
          <p className="eyebrow">운영 흐름</p>
          <h2>참여가 혜택 사용으로 이어지는지 보세요</h2>
        </div>
        <p className="lead">숫자는 선택한 기간과 보관 범위 안에서 집계됩니다. 고객 개인 정보는 분석에 포함하지 않습니다.</p>
      </header>

      <dl className="stats analytics-summary" aria-label="전체 운영 요약">
        <div>
          <dt>전체 참여</dt>
          <dd>
            {summary.data.totalPlays}
            <small>건</small>
          </dd>
        </div>
        <div>
          <dt>쿠폰 사용률</dt>
          <dd>{rate}%</dd>
        </div>
      </dl>

      <section className="panel stack analytics-result-panel" aria-labelledby="result-count-title">
        <div className="panel-heading">
          <p className="eyebrow">전체 누적</p>
          <h2 id="result-count-title">결과별 참여</h2>
        </div>
        <div className="list">
          {Object.entries(summary.data.results ?? {}).map(([name, count]) => (
            <div className="list-item" key={name}>
              <span className="lead">{labelOf(YUT_LABEL, name)}</span>
              <span className="name">{count}</span>
            </div>
          ))}
        </div>
      </section>

      {!advanced && (
        <section className="panel stack analytics-locked" aria-labelledby="advanced-title">
          <div className="panel-heading">
            <p className="eyebrow">더 깊게 보기</p>
            <h2 id="advanced-title">상세 분석</h2>
          </div>
          <p className="lead">
            시간대·요일·상품별 사용률과 재참여율은 스탠다드부터 볼 수 있습니다.
          </p>
          <Link className="btn secondary" href={`/admin/stores/${id}/plan`}>
            요금제 보기
          </Link>
        </section>
      )}

      {advanced && (
        <>
          <div className="analytics-toolbar">
            <div>
              <p className="eyebrow">조회 기간</p>
              <p className="hint">기간을 바꾸면 아래 분석이 함께 갱신됩니다.</p>
            </div>
            <div className="preset-row" role="group" aria-label="조회 기간">
              {RANGES.map((r) => (
                <button
                  key={r.days}
                  type="button"
                  className={days === r.days ? "btn secondary is-on" : "btn secondary"}
                  aria-pressed={days === r.days}
                  onClick={() => setDays(r.days)}
                >
                  최근 {r.label}
                </button>
              ))}
            </div>
          </div>

          {detailed.isPending && <div className="skeleton" style={{ height: 220 }} />}
          {detailed.isError && (
            <p className="error" role="alert">
              {errorMessage(detailed.error)}
            </p>
          )}

          {detailed.data && (
            <>
              {comparison && (
                <section className="panel stack analytics-comparison" aria-labelledby="comparison-title">
                  <div className="panel-heading">
                    <p className="eyebrow">변화 확인</p>
                    <h2 id="comparison-title">직전 같은 기간과 비교</h2>
                    <p className="lead">선택한 {days}일과 바로 앞 {days}일을 비교합니다.</p>
                  </div>
                  {comparison.note ? (
                    <p className="notice">{comparison.note}</p>
                  ) : (
                    <div className="list">
                      <div className="list-item">
                        <span className="lead">참여 변화</span>
                        <span className="name">{comparison.plays}</span>
                      </div>
                      <div className="list-item">
                        <span className="lead">쿠폰 사용률 변화</span>
                        <span className="name">{comparison.redemptionRate}</span>
                      </div>
                    </div>
                  )}
                </section>
              )}

              <section className="panel stack analytics-timing" aria-labelledby="timing-title">
                <div className="row panel-heading">
                  <div>
                    <p className="eyebrow">방문 흐름</p>
                    <h2 id="timing-title">언제 많이 참여했나요?</h2>
                  </div>
                  {detailed.data.window.clampedByPlanRetention && (
                    <span className="pill" data-tone="off">보관기간까지</span>
                  )}
                </div>
                <HourlyBars byHour={detailed.data.hourly.playsByHour} />
                <hr className="hair" />
                <WeekdayBars byWeekday={detailed.data.weekday.playsByWeekday} />
              </section>

              <section className="panel stack analytics-prizes" aria-labelledby="prize-performance-title">
                <div className="panel-heading">
                  <p className="eyebrow">혜택 성과</p>
                  <h2 id="prize-performance-title">상품별 사용률</h2>
                </div>
                <div className="list">
                  {detailed.data.prizePerformance.prizes.map((p) => (
                    <div className="list-item" key={p.prizeRank}>
                      <span className="name">
                        {rankLabel(p.prizeRank)} {p.prizeName}
                      </span>
                      <span className="lead">
                        {p.redeemed}/{p.issued} · {p.redemptionRatePercent}%
                      </span>
                    </div>
                  ))}
                </div>
              </section>

              <section className="panel stack analytics-repeat" aria-labelledby="repeat-title">
                <div className="panel-heading">
                  <p className="eyebrow">다시 찾은 고객</p>
                  <h2 id="repeat-title">재참여</h2>
                </div>
                <div className="list">
                  <div className="list-item">
                    <span className="lead">참여자</span>
                    <span className="name">{detailed.data.repeat.uniqueParticipants}명</span>
                  </div>
                  <div className="list-item">
                    <span className="lead">2회 이상</span>
                    <span className="name">
                      {detailed.data.repeat.repeatParticipants}명 · {detailed.data.repeat.repeatRatePercent}%
                    </span>
                  </div>
                </div>
                <p className="hint">
                  전화번호 대신 익명 식별값으로 집계합니다. 보관 기간이 지난 참여는 제외됩니다.
                </p>
              </section>

              {summary.data.csvExports?.length ? (
                <section className="panel stack analytics-export" aria-labelledby="export-title">
                  <div className="panel-heading">
                    <p className="eyebrow">자료 보관</p>
                    <h2 id="export-title">엑셀로 내려받기</h2>
                  </div>
                  <p className="lead">집계만 담깁니다. 손님 이름과 전화번호는 들어가지 않습니다.</p>
                  <div className="preset-row">
                    {[
                      { kind: "daily", label: "일자별" },
                      { kind: "prize", label: "상품별" },
                    ].map((e) => (
                      <button
                        key={e.kind}
                        type="button"
                        className="btn secondary"
                        disabled={exporting !== ""}
                        onClick={() => {
                          setExporting(e.kind);
                          setExportError("");
                          downloadWithAuth(
                            `/admin/stores/${id}/analytics/export/${e.kind}?from=${isoDaysAgo(days)}`,
                            `${e.kind}.csv`,
                          )
                            .catch((err) => setExportError(errorMessage(err)))
                            .finally(() => setExporting(""));
                        }}
                      >
                        {exporting === e.kind ? "내려받는 중" : e.label}
                      </button>
                    ))}
                  </div>
                  {exportError && (
                    <p className="error" role="alert">
                      {exportError}
                    </p>
                  )}
                </section>
              ) : null}
            </>
          )}
        </>
      )}
    </AdminFrame>
  );
}
