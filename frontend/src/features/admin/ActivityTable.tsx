import { COUPON_STATUS_LABEL, PLAY_STATUS_LABEL, YUT_LABEL, labelOf, rankLabel } from "@/features/labels";

export type ActivityRow = {
  id?: string | number;
  playId?: string;
  token?: string;
  customerName?: string;
  phoneLast4?: string;
  result?: string;
  prizeRank?: number;
  status?: string;
  prizeName?: string;
  playedAt?: string;
  issuedAt?: string;
  redeemedAt?: string | null;
};

// 빈 표에 머리글만 남겨두면 고장난 화면처럼 보인다. 대신 뭘 하면 채워지는지 알려준다.
const EMPTY = {
  play: { title: "아직 참여한 손님이 없어요", body: "테이블의 QR을 손님이 스캔해 윷을 던지면 여기에 쌓입니다." },
  coupon: { title: "발급된 쿠폰이 없어요", body: "손님이 윷을 던지면 그 자리에서 쿠폰이 발급되고 여기에 기록됩니다." },
};

export function ActivityTable({ rows, kind }: { rows: ActivityRow[]; kind: "play" | "coupon" }) {
  if (rows.length === 0)
    return (
      <section className="panel stack">
        <h2>{EMPTY[kind].title}</h2>
        <p className="lead">{EMPTY[kind].body}</p>
      </section>
    );

  return (
    <div className="panel table-wrap" tabIndex={0} role="region" aria-label={kind === "play" ? "참여 내역 표" : "쿠폰 내역 표"}>
      <table className="table">
        <thead>
          <tr>
            <th>고객</th>
            <th>{kind === "play" ? "결과" : "상품"}</th>
            <th>상태</th>
            <th>일시</th>
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r.id ?? r.playId ?? r.token ?? i}>
              <td>
                {r.customerName ?? "-"} {r.phoneLast4 && `(**${r.phoneLast4})`}
              </td>
              <td>
                {kind === "play"
                  ? labelOf(YUT_LABEL, r.result) + (r.prizeRank ? ` · ${rankLabel(r.prizeRank)}` : "")
                  : r.prizeName ?? "-"}
              </td>
              <td>{labelOf(kind === "play" ? PLAY_STATUS_LABEL : COUPON_STATUS_LABEL, r.status)}</td>
              <td>{new Date(r.playedAt ?? r.issuedAt ?? "").toLocaleString("ko-KR")}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export function ActivityPager({ page, totalPages, onChange }: { page: number; totalPages: number; onChange: (page: number) => void }) {
  if (totalPages <= 1) return null;
  return (
    <nav className="actions" aria-label="목록 페이지">
      <button type="button" className="btn secondary" disabled={page === 0} onClick={() => onChange(page - 1)}>이전</button>
      <span aria-label={`${page + 1}쪽 / 총 ${totalPages}쪽`}>{page + 1} / {totalPages}</span>
      <button type="button" className="btn secondary" disabled={page + 1 >= totalPages} onClick={() => onChange(page + 1)}>다음</button>
    </nav>
  );
}
