"use client";
import Link from "next/link";
import { useState } from "react";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { HourlyBars, WeekdayBars } from "@/features/admin/HourlyBars";
import { api, downloadWithAuth, errorMessage } from "@/lib/api";
import { YUT_LABEL, labelOf, rankLabel } from "@/features/labels";
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

  return (
    <AdminFrame title="통계">
      <dl className="stats">
        <div>
          <dt>전체 참여</dt>
          <dd>{summary.data.totalPlays}</dd>
        </div>
        <div>
          <dt>쿠폰 사용률</dt>
          <dd>{rate}%</dd>
        </div>
      </dl>

      <section className="panel stack">
        <h2>결과별 참여</h2>
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
        <section className="panel stack">
          <h2>언제 붐비는지 보려면</h2>
          <p className="lead">
            시간대와 요일, 상품별 사용률, 재참여율은 스탠다드 요금제부터 볼 수 있습니다. 알바 배치나
            이벤트 시간을 정할 때 쓰는 숫자입니다.
          </p>
          <Link className="btn secondary" href={`/admin/stores/${id}/plan`}>
            요금제 보기
          </Link>
        </section>
      )}

      {advanced && (
        <>
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

          {detailed.isPending && <div className="skeleton" style={{ height: 220 }} />}
          {detailed.isError && (
            <p className="error" role="alert">
              {errorMessage(detailed.error)}
            </p>
          )}

          {detailed.data && (
            <>
              <section className="panel stack">
                <div className="row">
                  <h2>언제 붐비나</h2>
                  {detailed.data.window.clampedByPlanRetention && (
                    <span className="pill" data-tone="off">보관기간까지</span>
                  )}
                </div>
                <HourlyBars byHour={detailed.data.hourly.playsByHour} />
                <hr className="hair" />
                <WeekdayBars byWeekday={detailed.data.weekday.playsByWeekday} />
              </section>

              <section className="panel stack">
                <h2>상품별 사용률</h2>
                <div className="list">
                  {detailed.data.prizePerformance.prizes.map((p) => (
                    <div className="list-item" key={p.rank}>
                      <span className="name">
                        {rankLabel(p.rank)} {p.prizeName}
                      </span>
                      <span className="lead">
                        {p.redeemed}/{p.issued} · {p.redemptionRatePercent}%
                      </span>
                    </div>
                  ))}
                </div>
              </section>

              <section className="panel stack">
                <h2>다시 오는 손님</h2>
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
                  전화번호를 저장하지 않고 익명 기준으로만 셉니다. 보관기간이 지난 참여는 제외됩니다.
                </p>
              </section>

              {summary.data.csvExports?.length ? (
                <section className="panel stack">
                  <h2>엑셀로 내려받기</h2>
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
                        {exporting === e.kind ? "내려받는 중..." : e.label}
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
