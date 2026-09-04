"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { api, errorMessage } from "@/lib/api";
import type { StoreSummary } from "@/types/api";
import { YutFan } from "@/features/intro/YutFan";
import { oddsLabel, rankLabel } from "@/features/labels";

// 손님이 실제로 하는 일 세 가지. 진행 표시줄(.steps)과 달리 여기서는 아직 아무것도 시작하지 않았다.
const HOW_TO = ["이름과 전화번호 입력", "윷 던지기!", "상품 확인하기"];

export default function StoreIntro({ initialData }: { initialData?: StoreSummary }) {
  const token = String(useParams().storeToken);
  const store = useQuery({
    queryKey: ["store", token],
    queryFn: () => api<StoreSummary>(`/public/stores/by-token/${encodeURIComponent(token)}`),
    initialData,
  });

  if (store.isPending)
    return (
      <main className="screen">
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">매장 정보를 불러오는 중</span>
          <div className="skeleton" style={{ height: 34, width: "70%" }} />
          <div className="skeleton" style={{ height: 190 }} />
          <div className="skeleton" style={{ height: 120 }} />
        </div>
      </main>
    );

  if (store.isError)
    return (
      <main className="screen">
        <h1>매장을 열 수 없어요</h1>
        <p className="error" role="alert">
          {errorMessage(store.error)}
        </p>
        <p className="lead">테이블의 QR을 다시 스캔하거나 직원에게 알려주세요.</p>
      </main>
    );

  const prizes = store.data.prizes ?? [];

  return (
    <main className="screen has-bar intro">
      <header className="stack">
        <p className="brand">{store.data.name}</p>
        <h1>
          윷 한 판 던지고
          <br />
          상품 받아가세요
        </h1>
      </header>

      <YutFan />

      <ol className="howto">
        {HOW_TO.map((step, i) => (
          <li key={step}>
            <span className="howto-n" aria-hidden="true">
              {i + 1}
            </span>
            <span>{step}</span>
          </li>
        ))}
      </ol>

      <section className="panel" aria-labelledby="prize-heading">
        <div className="row" style={{ marginBottom: "var(--s3)" }}>
          <h2 id="prize-heading">오늘의 상품</h2>
          {prizes.length > 0 && (
            <span className="pill" data-tone="wood">
              {prizes.length}가지
            </span>
          )}
        </div>
        <div className="list">
          {prizes.length ? (
            prizes.map((p) => (
              <div className="list-item" key={p.rank}>
                <span className="name">
                  {rankLabel(p.rank)} {p.name}
                </span>
                <span className="pill">{oddsLabel(p.odds)}</span>
              </div>
            ))
          ) : (
            <p className="lead">윷을 던져 나온 결과에 따라 매장 상품을 드려요.</p>
          )}
        </div>
      </section>

      <p className="lead">
        네이버 플레이스에 솔직한 방문 경험을 남긴 뒤 직원에게 작성 여부를 확인받아 주세요.
      </p>

      <div className="actionbar">
        <div className="inner">
          {store.data.naverPlaceUrl && (
            <a className="btn ghost" target="_blank" rel="noreferrer" href={store.data.naverPlaceUrl}>
              네이버 플레이스에서 리뷰 쓰기
            </a>
          )}
          <Link className="btn wood" href={`/s/${token}/identify`}>
            윷 던지러 가기
          </Link>
        </div>
      </div>
    </main>
  );
}
