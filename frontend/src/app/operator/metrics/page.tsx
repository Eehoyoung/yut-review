"use client";
import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { PLAN_LABEL, SERVICE_STATE_LABEL } from "@/features/admin/labels";
import { OperatorFrame } from "@/features/operator/OperatorFrame";
import { won } from "@/features/operator/labels";
import { api, downloadWithAuth, errorMessage } from "@/lib/api";
import type { OperatorMetrics, Plan, ServiceState } from "@/types/api";

/**
 * 소담랩스 사업 지표.
 *
 * 고객 수(DAU·WAU·MAU)는 전화번호 해시의 고유 개수라 여러 매장을 도는 손님은 한 명이다. 해시는 120일 뒤 비식별되므로
 * 지난 달 MAU는 매일 새벽에 찍어 둔 스냅숏에서 읽는다. 스냅숏이 없는 달은 "-"로 둔다(0이 아니다).
 */

const count = (n: number) => n.toLocaleString("ko-KR");
const PLANS: Plan[] = ["BASIC", "STANDARD", "PRO"];
const STATES: ServiceState[] = ["TRIAL", "ACTIVE", "GRACE", "RESTRICTED", "OPEN"];

function Tile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div>
      <dt>{label}</dt>
      <dd>
        {value}
        {hint && <small className="hint">{hint}</small>}
      </dd>
    </div>
  );
}

/** 하루 한 막대. 단일 계열이라 범례 대신 제목이 이름이다. 정확한 값은 막대에 올리면(title) 보이고 표로도 본다. */
function DailyBars({ title, rows, pick }: { title: string; rows: OperatorMetrics["daily"]; pick: (r: OperatorMetrics["daily"][number]) => number }) {
  const max = Math.max(1, ...rows.map(pick));
  const w = 100 / Math.max(rows.length, 1);
  const last = rows.at(-1);
  return (
    <figure className="op-chart">
      <figcaption>
        <b>{title}</b>
        {last && (
          <span className="hint">
            {last.date} {count(pick(last))}
          </span>
        )}
      </figcaption>
      {rows.length === 0 ? (
        <p className="hint">스냅숏이 아직 없습니다. 매일 새벽 3시 5분에 어제 값이 쌓입니다.</p>
      ) : (
        <svg viewBox="0 0 100 40" preserveAspectRatio="none" role="img" aria-label={`${title} 최근 ${rows.length}일`}>
          {rows.map((r, i) => {
            const h = (pick(r) / max) * 38;
            return (
              <rect key={r.date} x={i * w + w * 0.15} width={w * 0.7} y={40 - h} height={Math.max(h, 0.3)} rx={0.4}>
                <title>
                  {r.date} · {count(pick(r))}
                </title>
              </rect>
            );
          })}
        </svg>
      )}
      <div className="op-chart-axis hint">
        <span>{rows[0]?.date ?? ""}</span>
        <span>최대 {count(max)}</span>
        <span>{last?.date ?? ""}</span>
      </div>
    </figure>
  );
}

function Share({ label, value, total }: { label: string; value: number; total: number }) {
  const width = total > 0 ? Math.round((value / total) * 100) : 0;
  return (
    <div className="weekday-row" data-wide>
      <span className="weekday-name">{label}</span>
      <span className="weekday-track">
        <span className="weekday-fill" style={{ width: `${width}%` }} />
      </span>
      <span className="weekday-count">
        {count(value)} <small className="hint">{width}%</small>
      </span>
    </div>
  );
}

export default function OperatorMetricsPage() {
  const q = useQuery({ queryKey: ["operator-metrics"], queryFn: () => api<OperatorMetrics>("/operator/metrics") });
  const [csvError, setCsvError] = useState("");
  const m = q.data;
  const planTotal = m ? PLANS.reduce((a, p) => a + m.plans[p], 0) : 0;
  const stateTotal = m ? STATES.reduce((a, s) => a + m.serviceStates[s], 0) : 0;

  const csv = async () => {
    setCsvError("");
    try {
      await downloadWithAuth("/operator/metrics/monthly.csv", "sodam-hanpan_monthly_metrics.csv");
    } catch (err) {
      setCsvError(errorMessage(err));
    }
  };

  return (
    <OperatorFrame title="사업 지표">
      {q.isError && (
        <p className="error" role="alert">
          {errorMessage(q.error)}
        </p>
      )}
      {q.isPending && (
        <div className="stack" aria-busy="true">
          <span className="visually-hidden">지표를 계산하는 중</span>
          <div className="skeleton" style={{ height: 120 }} />
          <div className="skeleton" style={{ height: 240 }} />
        </div>
      )}

      {m && (
        <>
          <div className="op-grid">
            <section className="panel stack" aria-labelledby="m-customers">
              <header className="stack" style={{ gap: 2 }}>
                <h2 id="m-customers">고객</h2>
                <p className="hint">{m.date} 기준 · 전 매장 합산 고유 고객(전화번호 기준)</p>
              </header>
              <dl className="stats">
                <Tile label="DAU (오늘)" value={count(m.customers.dau)} />
                <Tile label="WAU (7일)" value={count(m.customers.wau)} />
                <Tile label="MAU (30일)" value={count(m.customers.mau)} />
              </dl>
            </section>
            <section className="panel stack" aria-labelledby="m-stores">
              <header className="stack" style={{ gap: 2 }}>
                <h2 id="m-stores">매장</h2>
                <p className="hint">활성 = 최근 30일 안에 게임이 1건 이상</p>
              </header>
              <dl className="stats">
                <Tile label="전체" value={count(m.stores.total)} hint={m.stores.pending ? `대기 ${m.stores.pending}` : undefined} />
                <Tile label="운영 중" value={count(m.stores.active)} />
                <Tile label="30일 활성" value={count(m.stores.engaged30)} />
                <Tile label="이번 달 신규" value={count(m.stores.newThisMonth)} />
              </dl>
            </section>
          </div>

          <section className="panel stack" aria-labelledby="m-revenue">
            <header className="stack" style={{ gap: 2 }}>
              <h2 id="m-revenue">매출</h2>
              <p className="hint">MRR = 자동결제가 켜진 결제 중 매장의 월 요금 합(청구 등급 기준, 보상 등급 제외)</p>
            </header>
            <dl className="stats">
              <Tile label="MRR" value={won(m.revenue.mrr)} />
              <Tile label="ARR" value={won(m.revenue.arr)} />
              <Tile label="유료 매장" value={count(m.revenue.payingStores)} hint={`ARPU ${won(m.revenue.arpu)}`} />
              <Tile label="이번 달 결제" value={won(m.revenue.paidThisMonth)} hint={`지난달 ${won(m.revenue.paidLastMonth)}`} />
              <Tile label="이번 달 실패" value={count(m.revenue.failedThisMonth)} />
              <Tile label="대조 필요" value={count(m.revenue.pendingToReconcile)} hint="10분+ 결과 미확인" />
            </dl>
          </section>

          <div className="op-grid">
            <section className="panel stack" aria-labelledby="m-plans">
              <h2 id="m-plans">요금제 분포 (기능 등급)</h2>
              {PLANS.map((p) => (
                <Share key={p} label={PLAN_LABEL[p]} value={m.plans[p]} total={planTotal} />
              ))}
              <h2>이용 상태</h2>
              {STATES.map((s) => (
                <Share key={s} label={SERVICE_STATE_LABEL[s]} value={m.serviceStates[s]} total={stateTotal} />
              ))}
            </section>
            <section className="panel stack" aria-labelledby="m-health">
              <h2 id="m-health">건강 지표</h2>
              <dl className="stats">
                <Tile
                  label="체험 → 유료 전환"
                  value={m.health.trialConversionPercent === null ? "-" : `${m.health.trialConversionPercent}%`}
                  hint={`${count(m.health.trialConverted)}/${count(m.health.trialEnded)}`}
                />
                <Tile label="자동결제 해지" value={count(m.health.autoRenewOff)} />
                <Tile label="갱신 실패 중" value={count(m.health.renewalFailing)} />
                <Tile label="보상 등급 제공 중" value={count(m.health.complimentary)} />
              </dl>
              <h2>이번 달 참여</h2>
              <dl className="stats">
                <Tile label="게임" value={count(m.engagementThisMonth.games)} hint={`매장당 ${m.engagementThisMonth.gamesPerEngagedStore}`} />
                <Tile label="쿠폰 발급" value={count(m.engagementThisMonth.couponsIssued)} />
                <Tile
                  label="쿠폰 사용"
                  value={count(m.engagementThisMonth.couponsRedeemed)}
                  hint={`${m.engagementThisMonth.redemptionPercent}%`}
                />
              </dl>
            </section>
          </div>

          <section className="panel stack" aria-labelledby="m-daily">
            <h2 id="m-daily">일별 추이 (최근 90일, 새벽 스냅숏)</h2>
            <div className="op-grid">
              <DailyBars title="DAU" rows={m.daily} pick={(r) => r.dau} />
              <DailyBars title="MAU (30일 창)" rows={m.daily} pick={(r) => r.mau} />
              <DailyBars title="게임" rows={m.daily} pick={(r) => r.games} />
              <DailyBars title="30일 활성 매장" rows={m.daily} pick={(r) => r.engagedStores} />
            </div>
            <details>
              <summary>일별 표 보기</summary>
              <div className="table-wrap">
                <table className="table">
                  <caption className="visually-hidden">일별 지표</caption>
                  <thead>
                    <tr>
                      <th scope="col">날짜</th>
                      <th scope="col">DAU</th>
                      <th scope="col">WAU</th>
                      <th scope="col">MAU</th>
                      <th scope="col">게임</th>
                      <th scope="col">쿠폰 발급</th>
                      <th scope="col">쿠폰 사용</th>
                      <th scope="col">가입</th>
                      <th scope="col">활성 매장</th>
                      <th scope="col">MRR</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[...m.daily].reverse().map((r) => (
                      <tr key={r.date}>
                        <td>{r.date}</td>
                        <td>{count(r.dau)}</td>
                        <td>{count(r.wau)}</td>
                        <td>{count(r.mau)}</td>
                        <td>{count(r.games)}</td>
                        <td>{count(r.couponsIssued)}</td>
                        <td>{count(r.couponsRedeemed)}</td>
                        <td>{count(r.signups)}</td>
                        <td>{count(r.engagedStores)}</td>
                        <td>{r.mrr === null ? "-" : won(r.mrr)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </details>
          </section>

          <section className="panel stack" aria-labelledby="m-monthly">
            <div className="row">
              <h2 id="m-monthly">월별 (최근 12개월)</h2>
              <button type="button" className="btn secondary btn-inline" onClick={csv}>
                CSV 저장
              </button>
            </div>
            {csvError && (
              <p className="error" role="alert">
                {csvError}
              </p>
            )}
            <div className="table-wrap">
              <table className="table">
                <caption className="visually-hidden">월별 사업 지표</caption>
                <thead>
                  <tr>
                    <th scope="col">월</th>
                    <th scope="col">MAU</th>
                    <th scope="col">신규 매장</th>
                    <th scope="col">게임</th>
                    <th scope="col">쿠폰 발급</th>
                    <th scope="col">쿠폰 사용</th>
                    <th scope="col">결제 매출</th>
                    <th scope="col">결제 건수</th>
                  </tr>
                </thead>
                <tbody>
                  {[...m.monthly].reverse().map((r) => (
                    <tr key={r.month}>
                      <td>{r.month}</td>
                      <td>{r.mau === null ? "-" : count(r.mau)}</td>
                      <td>{count(r.signups)}</td>
                      <td>{count(r.games)}</td>
                      <td>{count(r.couponsIssued)}</td>
                      <td>{count(r.couponsRedeemed)}</td>
                      <td>{won(r.revenue)}</td>
                      <td>{count(r.payments)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="hint">MAU는 그 달 말일 기준 최근 30일 고유 고객입니다. 이번 달은 오늘 기준입니다.</p>
          </section>
        </>
      )}
    </OperatorFrame>
  );
}
