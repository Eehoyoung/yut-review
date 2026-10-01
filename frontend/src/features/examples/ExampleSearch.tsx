"use client";
import Link from "next/link";
import { useId, useState } from "react";
import { searchIndustries } from "./industries";

/**
 * 업종 검색. 공개 페이지에서는 업종 상세로, 매장 관리자 화면(storeId가 있을 때)에서는
 * 그 매장의 상품 설정으로 보내 추천 구성을 불러오게 한다.
 */
export function ExampleSearch({ storeId, label = "업종 검색" }: { storeId?: string; label?: string }) {
  const [query, setQuery] = useState("");
  const inputId = useId();
  const results = searchIndustries(query);
  const href = (slug: string) => (storeId ? `/admin/stores/${storeId}/prizes?example=${slug}` : `/examples/${slug}`);

  return (
    <div className="ex-search">
      <label htmlFor={inputId} className="visually-hidden">{label}</label>
      <input
        id={inputId}
        type="search"
        placeholder="예: 고깃집, 카페, 미용실"
        autoComplete="off"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />
      {query.trim() && (
        <ul className="ex-search-results" aria-live="polite">
          {results.length === 0 ? (
            <li className="ex-search-empty">아직 준비된 예시가 없어요. 가장 비슷한 업종을 골라 보세요.</li>
          ) : (
            results.map((industry) => (
              <li key={industry.slug}>
                <Link href={href(industry.slug)}>
                  <b>{industry.name}</b>
                  <span>{industry.headline}</span>
                </Link>
              </li>
            ))
          )}
        </ul>
      )}
    </div>
  );
}
