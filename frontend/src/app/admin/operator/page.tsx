"use client";
import Link from "next/link";
import { useQuery } from "@tanstack/react-query";
import { STORE_STATUS_LABEL, STORE_STATUS_TONE } from "@/features/admin/labels";
import { OperatorFrame } from "@/features/admin/OperatorFrame";
import { api, errorMessage } from "@/lib/api";
import type { OperatorOverview } from "@/types/api";

/**
 * 운영자 콘솔 첫 화면. 오늘 무슨 일이 있었는지를 먼저 보여 준다.
 *
 * 매장 심사가 첫 화면이던 것은 승인제가 기본이던 때의 배치다. 가입 방식은 서버 설정이고,
 * 매일 보는 숫자는 가입·게임·쿠폰이라 그것을 앞에 둔다. 전부 집계와 매장·사장 정보뿐이다.
 */

/** 1분. 사람이 읽고 판단하는 화면이라 이보다 자주 당길 이유가 없다. */
const REFRESH_MS = 60_000;

const count = (n: number) => n.toLocaleString("ko-KR");
const percent = (used: number, issued: number) => (issued > 0 ? `${Math.round((used / issued) * 100)}%` : "-");

function TrendBar({ label, value, max }: { label: string; value: number; max: number }) {
  const width = max > 0 ? Math.round((value / max) * 100) : 0;
  return (
    <div className="weekday-row" data-wide>
      <span className="weekday-name">{label}</span>
      <span className="weekday-track">
        <span className="weekday-fill" data-tone="ok" style={{ width: `${width}%` }} />
      </span>
      <span className="weekday-count">{count(value)}</span>
    </div>
  );
}

export default function OperatorOverviewPage() {
  const q = useQuery({
    queryKey: ["operator-overview"],
    queryFn: () => api<OperatorOverview>("/admin/operator/overview"),
    refetchInterval: REFRESH_MS,
  });
  const o = q.data;
  const maxGames = Math.max(0, ...(o?.trend.map((d) => d.games) ?? []));

  return (
    <OperatorFrame title="현황">
      {q.isError && (
        <p className="error" role="alert">
          {errorMessage(q.error)}
        </p>
      )}
      {q.isPending && (
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">현황을 불러오는 중</span>
          <div className="skeleton" style={{ height: 96 }} />
          <div className="skeleton" style={{ height: 240 }} />
        </div>
      )}

      {o && (
        <>
          <section className="panel stack" aria-label="오늘">
            <header className="stack" style={{ gap: 2 }}>
              <h2>오늘</h2>
              <p className="hint">{o.date} 기준 · 1분마다 갱신</p>
            </header>
            <dl className="stats">
              <div>
                <dt>매장 가입</dt>
                <dd>{count(o.today.signups)}</dd>
              </div>
              <div>
                <dt>게임</dt>
                <dd>{count(o.today.games)}</dd>
              </div>
              <div>
                <dt>쿠폰 발급</dt>
                <dd>{count(o.today.couponsIssued)}</dd>
              </div>
              <div>
                <dt>쿠폰 사용</dt>
                <dd>{count(o.today.couponsRedeemed)}</dd>
              </div>
            </dl>
          </section>

          <section className="panel stack" aria-label="누적">
            <h2>누적</h2>
            <dl className="stats">
              <div>
                <dt>매장</dt>
                <dd>{count(o.totals.stores)}</dd>
              </div>
              <div>
                <dt>운영 중</dt>
                <dd>{count(o.totals.activeStores)}</dd>
              </div>
              <div>
                <dt>게임</dt>
                <dd>{count(o.totals.games)}</dd>
              </div>
              <div>
                <dt>쿠폰 사용률</dt>
                <dd>{percent(o.totals.couponsRedeemed, o.totals.couponsIssued)}</dd>
              </div>
            </dl>
            <p className="hint">
              쿠폰 {count(o.totals.couponsIssued)}장 발급 · {count(o.totals.couponsRedeemed)}장 사용 · 가입 방식:{" "}
              {o.approvalRequired ? "운영자 승인 후 운영" : "가입 즉시 운영"}
              {o.totals.pendingStores > 0 && (
                <>
                  {" · "}
                  <Link href="/admin/operator/stores">승인 대기 {count(o.totals.pendingStores)}곳</Link>
                </>
              )}
            </p>
          </section>

          <section className="panel stack" aria-label="오늘 매장별">
            <h2>오늘 매장별 게임</h2>
            {o.topStoresToday.length === 0 ? (
              <p className="hint">오늘 참여가 아직 없습니다.</p>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <caption className="visually-hidden">오늘 게임이 많은 매장 상위 10곳</caption>
                  <thead>
                    <tr>
                      <th scope="col">매장</th>
                      <th scope="col">게임</th>
                      <th scope="col">쿠폰 사용</th>
                    </tr>
                  </thead>
                  <tbody>
                    {o.topStoresToday.map((s) => (
                      <tr key={s.storeId}>
                        <td className="wrap-anywhere">{s.name}</td>
                        <td>{count(s.games)}</td>
                        <td>{count(s.couponsRedeemed)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
            <p className="hint">
              상위 10곳입니다. 매장 전체의 누적 숫자는 <Link href="/admin/operator/stores">매장</Link>에서 봅니다.
            </p>
          </section>

          <section className="panel stack" aria-label="최근 14일">
            <h2>최근 14일 게임</h2>
            {o.trend.map((d) => (
              <TrendBar key={d.date} label={d.date.slice(5)} value={d.games} max={maxGames} />
            ))}
            <div className="table-wrap">
              <table className="table">
                <caption className="visually-hidden">최근 14일 일별 가입·게임·쿠폰 사용</caption>
                <thead>
                  <tr>
                    <th scope="col">날짜</th>
                    <th scope="col">가입</th>
                    <th scope="col">게임</th>
                    <th scope="col">쿠폰 사용</th>
                  </tr>
                </thead>
                <tbody>
                  {[...o.trend].reverse().map((d) => (
                    <tr key={d.date}>
                      <td>{d.date}</td>
                      <td>{count(d.signups)}</td>
                      <td>{count(d.games)}</td>
                      <td>{count(d.couponsRedeemed)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </section>

          <section className="panel stack" aria-label="최근 가입">
            <h2>최근 가입한 매장</h2>
            {o.recentSignups.length === 0 ? (
              <p className="hint">가입한 매장이 아직 없습니다.</p>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <caption className="visually-hidden">최근 가입한 매장 10곳</caption>
                  <thead>
                    <tr>
                      <th scope="col">가입</th>
                      <th scope="col">매장</th>
                      <th scope="col">대표</th>
                      <th scope="col">사업자 확인</th>
                      <th scope="col">상태</th>
                    </tr>
                  </thead>
                  <tbody>
                    {o.recentSignups.map((s) => (
                      <tr key={s.id}>
                        <td>{new Date(s.createdAt).toLocaleString("ko-KR")}</td>
                        <td className="wrap-anywhere">{s.name}</td>
                        <td className="wrap-anywhere">
                          {s.ownerName}
                          <br />
                          <small className="hint">{s.ownerEmail}</small>
                        </td>
                        <td>{s.businessVerified ? "국세청 확인" : "기록 없음"}</td>
                        <td>
                          <span className="pill" data-tone={STORE_STATUS_TONE[s.status]}>
                            {STORE_STATUS_LABEL[s.status]}
                          </span>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}
    </OperatorFrame>
  );
}
