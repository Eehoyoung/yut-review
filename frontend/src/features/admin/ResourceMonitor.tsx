"use client";
import { useQuery } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import { THROTTLE_LABEL } from "@/features/admin/labels";
import type { OperatorMonitoring } from "@/types/api";

/**
 * 운영자 자원 현황.
 *
 * 쿼터와 상한은 절반이고, 나머지 절반은 "지금 무엇을 막고 있는지 보이는 것"이다. 보이지 않으면
 * 한도가 정상 손님을 막고 있는지, 실제로 공격을 받고 있는지, 디스크가 차고 있는지를 구분할 수 없다.
 *
 * 값은 전부 집계와 매장 공개 라벨뿐이다. 이 화면에는 고객 개인정보가 한 칸도 없다.
 */

/** 60초. 사람이 보고 판단하는 화면이라 이보다 자주 당겨 봐야 읽을 시간이 없다. */
const REFRESH_MS = 60_000;

/** 사용률에 붙이는 톤. 숫자만 두면 80%가 위험한지 아닌지를 매번 다시 판단해야 한다. */
function tone(percent: number) {
  return percent >= 90 ? "bad" : percent >= 70 ? "wait" : "ok";
}

function Usage({ label, used, total }: { label: string; used: number; total: number }) {
  const percent = total > 0 ? Math.min(Math.round((used / total) * 1000) / 10, 100) : 0;
  return (
    <div className="weekday-row" data-wide>
      <span className="weekday-name">{label}</span>
      <span className="weekday-track">
        <span className="weekday-fill" data-tone={tone(percent)} style={{ width: `${percent}%` }} />
      </span>
      <span className="weekday-count">
        {used.toLocaleString()} / {total.toLocaleString()}
      </span>
    </div>
  );
}

export function ResourceMonitor() {
  const q = useQuery({
    queryKey: ["operator-monitoring"],
    queryFn: () => api<OperatorMonitoring>("/admin/operator/monitoring"),
    refetchInterval: REFRESH_MS,
  });

  if (q.isError)
    return (
      <section className="panel stack">
        <h2>자원 현황</h2>
        <p className="error" role="alert">
          {errorMessage(q.error)}
        </p>
      </section>
    );

  if (!q.data)
    return (
      <section className="panel stack" aria-busy="true">
        <h2>자원 현황</h2>
        <span className="visually-hidden">자원 현황을 불러오는 중</span>
        <div className="skeleton" style={{ height: 120 }} />
      </section>
    );

  const m = q.data;
  const blocked = Object.entries(m.throttled);
  const totalBlocked = blocked.reduce((sum, [, n]) => sum + n, 0);

  return (
    <section className="panel stack" aria-label="자원 현황">
      <header className="stack" style={{ gap: 2 }}>
        <h2>자원 현황</h2>
        <p className="hint">{m.date} 기준 · 1분마다 갱신</p>
      </header>

      {/* 차단 건수가 먼저다. 0이면 조용하다는 뜻이고, 늘고 있으면 그것이 첫 신호다. */}
      <div className="stack" style={{ gap: "var(--s2)" }}>
        <h3 className="hint">최근 24시간 차단</h3>
        {totalBlocked === 0 ? (
          <p className="hint">막은 요청이 없습니다.</p>
        ) : (
          <table className="mini-table">
            <tbody>
              {blocked
                .filter(([, count]) => count > 0)
                .sort((a, b) => b[1] - a[1])
                .map(([code, count]) => (
                  <tr key={code}>
                    <td>{THROTTLE_LABEL[code] ?? code}</td>
                    <td>{count.toLocaleString()}건</td>
                  </tr>
                ))}
            </tbody>
          </table>
        )}
        <p className="hint">
          게임 한도는 매장 분당 {m.limits.gamePerStorePerMinute}회 · IP 분당 {m.limits.gamePerIpPerMinute}회 ·
          매장 하루 {m.limits.gamePerStorePerDay.toLocaleString()}건입니다. 정상 손님이 막히고 있다면 한도를 올려야
          한다는 신호입니다.
        </p>
      </div>

      <hr className="hair" />

      <div className="stack" style={{ gap: "var(--s2)" }}>
        <h3 className="hint">저장량</h3>
        <Usage label="rate 카운터" used={m.counters.rows} total={m.counters.maxRows} />
        <table className="mini-table">
          <tbody>
            <tr>
              <td>참여 기록</td>
              <td>{m.storage.gamePlays.toLocaleString()}행</td>
            </tr>
            <tr>
              <td>쿠폰</td>
              <td>{m.storage.coupons.toLocaleString()}행</td>
            </tr>
            <tr>
              <td>매장</td>
              <td>{m.storage.stores.toLocaleString()}곳</td>
            </tr>
            <tr>
              <td>안내물(PNG)</td>
              <td>{Math.round(m.storage.posterBase64Chars / 1024 / 1024 * 10) / 10}MB</td>
            </tr>
            <tr>
              <td>회수 티켓 / AI 대화</td>
              <td>
                {m.storage.recoverySessions.toLocaleString()} / {m.storage.aiChatTurns.toLocaleString()}행
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <hr className="hair" />

      <div className="stack" style={{ gap: "var(--s2)" }}>
        <h3 className="hint">오늘 붐비는 매장</h3>
        {m.busiestStores.length === 0 ? (
          <p className="hint">오늘 참여가 아직 없습니다.</p>
        ) : (
          <>
            {m.busiestStores.map((s) => (
              <Usage key={s.storeId} label={s.name} used={s.playsToday} total={s.dailyLimit} />
            ))}
            <p className="hint">
              한 매장이 상한에 다가가면 둘 중 하나입니다. 정말 잘 되고 있거나, 누가 그 매장 QR로 밀어붙이고 있거나.
              참여 기록에서 시간대가 고르게 퍼져 있는지 확인하세요.
            </p>
          </>
        )}
      </div>
    </section>
  );
}
