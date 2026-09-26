"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { useQuery } from "@tanstack/react-query";
import { ApiClientError, api, errorMessage } from "@/lib/api";
import type { StoreSummary } from "@/types/api";
import { YutFan } from "@/features/intro/YutFan";
import { oddsLabel, rankLabel } from "@/features/labels";

// 손님이 실제로 하는 일 세 가지. 진행 표시줄(.steps)과 달리 여기서는 아직 아무것도 시작하지 않았다.
const HOW_TO = ["이름과 전화번호 입력", "윷 던지기", "상품 확인"];
const PAYMENT_RESTRICTION_CODES = new Set(["SUBSCRIPTION_PAYMENT_REQUIRED", "STORE_PAYMENT_REQUIRED"]);

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

  if (store.error instanceof ApiClientError && PAYMENT_RESTRICTION_CODES.has(store.error.code))
    return (
      <main className="screen service-unavailable" aria-labelledby="store-unavailable-title">
        <div className="service-unavailable-mark" aria-hidden="true">
          <span />
          <span />
          <span />
          <span />
        </div>
        <p className="brand">소담한판</p>
        <h1 id="store-unavailable-title">매장 정보에 오류가 있어요!</h1>
        <p className="lead">잠시 후 다시 이용해 주세요. 계속 보이면 매장 직원에게 알려 주세요.</p>
        <button className="btn secondary" type="button" onClick={() => store.refetch()} disabled={store.isFetching}>
          {store.isFetching ? "다시 확인하는 중" : "다시 확인하기"}
        </button>
      </main>
    );

  if (store.isError)
    return (
      <main className="screen">
        <h1>매장을 열 수 없어요</h1>
        <p className="error" role="alert">
          {errorMessage(store.error)}
        </p>
        <p className="lead">테이블 QR을 다시 스캔해 주세요. 계속 안 되면 직원에게 알려 주세요.</p>
      </main>
    );

  const prizes = store.data.prizes ?? [];
  const headline = store.data.posterTagline?.trim() || "윷 한 판 던지고 오늘의 상품을 받아가세요";

  return (
    <main className="screen has-bar intro">
      <header className="stack">
        <p className="brand">{store.data.name}</p>
        <h1>{headline}</h1>
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

      <p className="lead">이름과 전화번호만 입력하면 바로 참여할 수 있어요.</p>

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
            <p className="lead">윷 결과에 따라 상품이 정해져요.</p>
          )}
        </div>
      </section>

      <div className="actionbar">
        <div className="inner">
          {store.data.naverPlaceUrl && (
            <a className="btn ghost" target="_blank" rel="noreferrer" href={store.data.naverPlaceUrl}>
              네이버에서 매장 보기 · 선택
            </a>
          )}
          <Link className="btn wood" href={`/s/${token}/identify`}>
            이벤트 참여하기
          </Link>
        </div>
      </div>
    </main>
  );
}
