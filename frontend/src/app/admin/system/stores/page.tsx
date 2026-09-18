"use client";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { SystemFrame } from "@/features/system/SystemFrame";
import { Dialog } from "@/features/ui/Dialog";
import { opsApi } from "@/features/system/api";
import { PLAN_NAME, consoleError, count, dateTime } from "@/features/system/labels";
import type { ConsoleStoreDetail, ConsoleStoreRow, PageData, Plan } from "@/types/api";

const SIZE = 20;
const PLANS: Plan[] = ["BASIC", "STANDARD", "PRO"];

/**
 * 매장 목록과 매장 하나에 대한 운영 조치.
 *
 * 여기서 할 수 있는 일은 둘뿐이다: 요금제 변경과 매장 중지/재개. 매장 안의 설정(상품, 확률, PIN,
 * 참여 내역)은 그 매장 관리자의 것이고, 운영자가 대신 만지는 문을 여기에 만들지 않는다.
 */
export default function ConsoleStores() {
  const qc = useQueryClient();
  const [page, setPage] = useState(0);
  const [query, setQuery] = useState("");
  const [keyword, setKeyword] = useState("");
  const [openId, setOpenId] = useState<number>();

  const list = useQuery({
    queryKey: ["ops-stores", page, keyword],
    queryFn: () =>
      opsApi<PageData<ConsoleStoreRow>>(
        `/stores?page=${page}&size=${SIZE}${keyword ? `&query=${encodeURIComponent(keyword)}` : ""}`,
      ),
    retry: false,
  });

  const detail = useQuery({
    queryKey: ["ops-store", openId],
    queryFn: () => opsApi<ConsoleStoreDetail>(`/stores/${openId}`),
    enabled: openId !== undefined,
    retry: false,
  });

  const refresh = () => {
    qc.invalidateQueries({ queryKey: ["ops-stores"] });
    qc.invalidateQueries({ queryKey: ["ops-store", openId] });
    qc.invalidateQueries({ queryKey: ["ops-overview"] });
  };

  const changePlan = useMutation({
    mutationFn: (plan: Plan) =>
      opsApi<{ plan: Plan }>(`/stores/${openId}/plan`, { method: "PUT", body: JSON.stringify({ plan }) }),
    onSuccess: refresh,
  });

  const changeStatus = useMutation({
    mutationFn: (status: "ACTIVE" | "INACTIVE") =>
      opsApi<{ status: string }>(`/stores/${openId}/status`, { method: "PUT", body: JSON.stringify({ status }) }),
    onSuccess: refresh,
  });

  return (
    <SystemFrame title="매장">
      <form
        className="panel stack"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          setPage(0);
          setKeyword(query.trim());
        }}
      >
        <div className="field">
          <label htmlFor="store-query">매장 검색</label>
          <input
            id="store-query"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="상호명 일부"
            maxLength={100}
          />
        </div>
        <button className="btn secondary">검색</button>
      </form>

      {list.isError && (
        <p className="error" role="alert">
          {consoleError(list.error)}
        </p>
      )}
      {list.isPending && <div className="skeleton" style={{ height: 200 }} />}
      {list.data && list.data.content.length === 0 && <p className="lead">해당하는 매장이 없습니다.</p>}

      {list.data && list.data.content.length > 0 && (
        <div className="list">
          {list.data.content.map((store) => (
            <button className="list-item store-link" key={store.id} onClick={() => setOpenId(store.id)}>
              <span className="stack" style={{ gap: 2 }}>
                <span className="name">{store.name}</span>
                <small className="hint wrap-anywhere">
                  {PLAN_NAME[store.plan]} · 참여 {count(store.plays)} · 쿠폰 {count(store.couponsRedeemed)}/
                  {count(store.couponsIssued + store.couponsRedeemed)}
                </small>
              </span>
              <span className="pill" data-tone={store.status === "ACTIVE" ? "ok" : "off"}>
                {store.status === "ACTIVE" ? "운영 중" : "중지"}
              </span>
            </button>
          ))}
        </div>
      )}

      {list.data && list.data.totalPages > 1 && (
        <div className="actions">
          <button className="btn secondary" disabled={page === 0} onClick={() => setPage((p) => p - 1)}>
            이전
          </button>
          <button
            className="btn secondary"
            disabled={page + 1 >= list.data.totalPages}
            onClick={() => setPage((p) => p + 1)}
          >
            다음
          </button>
        </div>
      )}

      <Dialog open={openId !== undefined} onClose={() => setOpenId(undefined)} labelledBy="store-detail-title">
        <div className="stack">
          <h2 id="store-detail-title">{detail.data?.name ?? "매장"}</h2>
          {detail.isPending && <div className="skeleton" style={{ height: 160 }} />}
          {detail.isError && (
            <p className="error" role="alert">
              {consoleError(detail.error)}
            </p>
          )}
          {detail.data && (
            <>
              <div className="list">
                <div className="list-item">
                  <span className="lead">대표 계정</span>
                  <span className="name wrap-anywhere">{detail.data.ownerEmail || "-"}</span>
                </div>
                <div className="list-item">
                  <span className="lead">사업자등록번호</span>
                  <span className="name">{detail.data.businessNumber || "-"}</span>
                </div>
                <div className="list-item">
                  <span className="lead">등록일</span>
                  <span className="name">{dateTime(detail.data.createdAt)}</span>
                </div>
                <div className="list-item">
                  <span className="lead">상품 등급</span>
                  <span className="name">{detail.data.rankCount}단계</span>
                </div>
                <div className="list-item">
                  <span className="lead">참여 / 쿠폰</span>
                  <span className="name">
                    {count(detail.data.plays)} / {count(detail.data.couponsIssued + detail.data.couponsRedeemed)}
                  </span>
                </div>
              </div>

              <h3>요금제</h3>
              <div className="preset-row">
                {PLANS.map((plan) => (
                  <button
                    key={plan}
                    className={`btn secondary${detail.data.subscription.plan === plan ? " is-on" : ""}`}
                    disabled={changePlan.isPending || detail.data.subscription.plan === plan}
                    onClick={() => changePlan.mutate(plan)}
                  >
                    {PLAN_NAME[plan]}
                  </button>
                ))}
              </div>
              {changePlan.isError && (
                <p className="error" role="alert">
                  {consoleError(changePlan.error)}
                </p>
              )}

              <h3>운영 상태</h3>
              <p className="lead">
                중지하면 손님이 QR로 들어오지 못합니다. 이미 발급된 쿠폰은 그대로 사용할 수 있습니다.
              </p>
              <button
                className="btn secondary"
                disabled={changeStatus.isPending}
                onClick={() => changeStatus.mutate(detail.data.status === "ACTIVE" ? "INACTIVE" : "ACTIVE")}
              >
                {detail.data.status === "ACTIVE" ? "매장 중지" : "매장 재개"}
              </button>
              {changeStatus.isError && (
                <p className="error" role="alert">
                  {consoleError(changeStatus.error)}
                </p>
              )}
            </>
          )}
          <div className="sheet-actions">
            <button className="btn ghost" onClick={() => setOpenId(undefined)}>
              닫기
            </button>
          </div>
        </div>
      </Dialog>
    </SystemFrame>
  );
}
