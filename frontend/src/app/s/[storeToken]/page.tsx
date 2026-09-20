import type { Envelope, StoreSummary } from "@/types/api";
import StoreIntro from "./StoreIntro";

/**
 * 매장 요약을 서버에서 먼저 가져와 클라이언트 쿼리의 initialData로 넘긴다. 하이드레이션을
 * 기다렸다가 fetch를 시작하는 왕복 하나를 없애는 게 전부다.
 *
 * INTERNAL_API_BASE가 없거나(로컬 npm run dev 등) fetch/응답 형식이 어긋나면 조용히
 * undefined를 넘겨서, 지금과 완전히 동일하게 클라이언트가 직접 가져오게 둔다.
 */
async function fetchStoreSummary(token: string): Promise<StoreSummary | undefined> {
  const base = process.env.INTERNAL_API_BASE;
  if (!base) return undefined;
  try {
    const res = await fetch(`${base}/api/public/stores/by-token/${encodeURIComponent(token)}`, {
      cache: "no-store",
    });
    if (!res.ok) return undefined;
    const body = (await res.json()) as Envelope<StoreSummary>;
    if (!body?.success || !body.data || typeof body.data.name !== "string") return undefined;
    return body.data;
  } catch {
    return undefined;
  }
}

export default async function Page({ params }: { params: Promise<{ storeToken: string }> }) {
  const { storeToken } = await params;
  const initialData = await fetchStoreSummary(storeToken);
  return <StoreIntro initialData={initialData} />;
}
