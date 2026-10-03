"use client";
import Link from "next/link";
import { useParams } from "next/navigation";
import { type FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  PLAN_LABEL,
  SERVICE_STATE_LABEL,
  SERVICE_STATE_TONE,
  STORE_STATUS_LABEL,
  STORE_STATUS_TONE,
} from "@/features/admin/labels";
import { formatBusinessNumber, formatPhone, formatTel } from "@/features/normalize";
import { OperatorFrame } from "@/features/operator/OperatorFrame";
import { CARE_ACTION_LABEL, PAYMENT_STATUS_LABEL, PAYMENT_STATUS_TONE, won } from "@/features/operator/labels";
import { Dialog } from "@/features/ui/Dialog";
import { api, downloadWithAuth, errorMessage } from "@/lib/api";
import type { OperatorStoreCare, Plan } from "@/types/api";

/**
 * 매장 한 곳의 상담 화면. 사장님 전화를 받으며 여는 곳이다.
 *
 * 여기서 바꾸는 것은 연락처성 정보(매장명·전화·주소·지도 링크)와 소담랩스가 지는 계약(결제일·보상 등급)뿐이다.
 * 상품·확률·쿠폰 같은 매장 운영 설정은 보여 주지도 않는다. 모든 변경은 사유를 받아 기록에 남긴다.
 * 예외: 직원 PIN 재발급은 사장이 못 하고 여기서만 한다. PIN 자체는 운영자에게도 보이지 않는다(사장 화면에만).
 */

type Sheet = "profile" | "postpone" | "comp" | "compEnd" | "pin";
const SHEET_TITLE: Record<Sheet, string> = {
  profile: "매장 정보 수정",
  postpone: "결제일 연기",
  comp: "보상 등급 제공",
  compEnd: "보상 등급 종료",
  pin: "직원 PIN 재발급",
};

const PLANS: Plan[] = ["BASIC", "STANDARD", "PRO"];
const count = (n: number) => n.toLocaleString("ko-KR");
const day = (s?: string | null) => (s ? new Date(s).toLocaleDateString("ko-KR") : "-");
const time = (s?: string | null) => (s ? new Date(s).toLocaleString("ko-KR") : "-");
const isoDay = (d: Date) => d.toLocaleDateString("sv-SE", { timeZone: "Asia/Seoul" });

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="list-item">
      <span className="lead">{label}</span>
      <span className="name">{children}</span>
    </div>
  );
}

export default function OperatorStoreCarePage() {
  const storeId = String(useParams().storeId);
  const qc = useQueryClient();
  const [sheet, setSheet] = useState<Sheet>();
  const [form, setForm] = useState({ name: "", phone: "", address: "", naverPlaceUrl: "", days: "7", plan: "PRO", reason: "", confirmName: "" });
  const [flash, setFlash] = useState("");
  const [range, setRange] = useState(() => {
    const to = new Date();
    return { from: isoDay(new Date(to.getTime() - 29 * 86_400_000)), to: isoDay(to), reason: "" };
  });
  const [exportError, setExportError] = useState("");

  const q = useQuery({
    queryKey: ["operator-care", storeId],
    queryFn: () => api<OperatorStoreCare>(`/operator/stores/${storeId}/care`),
  });
  const c = q.data;

  const open = (kind: Sheet) => {
    act.reset();
    setFlash("");
    if (c && kind === "profile")
      setForm((f) => ({ ...f, name: c.store.name, phone: c.store.phone, address: c.store.address, naverPlaceUrl: c.store.naverPlaceUrl, reason: "" }));
    else setForm((f) => ({ ...f, days: kind === "comp" ? "30" : "7", plan: "PRO", reason: "", confirmName: "" }));
    setSheet(kind);
  };

  const act = useMutation({
    mutationFn: async (kind: Sheet) => {
      const reason = form.reason.trim();
      if (kind === "profile")
        return api(`/operator/stores/${storeId}/profile`, {
          method: "PUT",
          body: JSON.stringify({ name: form.name, phone: form.phone, address: form.address, naverPlaceUrl: form.naverPlaceUrl, reason }),
        });
      if (kind === "postpone")
        return api(`/operator/stores/${storeId}/billing/postpone`, {
          method: "POST",
          body: JSON.stringify({ days: Number(form.days), reason }),
        });
      if (kind === "comp")
        return api(`/operator/stores/${storeId}/complimentary`, {
          method: "POST",
          body: JSON.stringify({ plan: form.plan, days: Number(form.days), reason }),
        });
      if (kind === "pin")
        return api(`/operator/stores/${storeId}/staff-pin/reset`, {
          method: "POST",
          body: JSON.stringify({ confirmName: form.confirmName, reason }),
        });
      return api(`/operator/stores/${storeId}/complimentary/end`, { method: "POST", body: JSON.stringify({ reason }) });
    },
    onSuccess: (_, kind) => {
      setFlash(`${SHEET_TITLE[kind]}을(를) 마쳤습니다. 지원 기록에 남았습니다.`);
      setSheet(undefined);
      qc.invalidateQueries({ queryKey: ["operator-care", storeId] });
      qc.invalidateQueries({ queryKey: ["operator-stores"] });
    },
  });

  const submit = (e: FormEvent) => {
    e.preventDefault();
    if (sheet === "pin" && form.confirmName.trim() !== c?.store.name.trim()) return;
    if (sheet) act.mutate(sheet);
  };

  const exportCsv = async (kind: "daily" | "prize") => {
    setExportError("");
    const params = new URLSearchParams({ from: range.from, to: range.to });
    if (range.reason.trim()) params.set("reason", range.reason.trim());
    try {
      await downloadWithAuth(`/operator/stores/${storeId}/report/export/${kind}?${params}`, `${c?.store.name ?? "store"}_${kind}.csv`);
      qc.invalidateQueries({ queryKey: ["operator-care", storeId] });
    } catch (err) {
      setExportError(errorMessage(err));
    }
  };

  const sub = c?.subscription;
  const hasComp = Boolean(sub?.complimentaryPlan);

  return (
    <OperatorFrame title={c ? c.store.name : "매장 상담"}>
      <p className="hint">
        <Link href="/operator/stores">← 매장 목록</Link> · 상품·확률·쿠폰 같은 매장 운영 설정은 이 화면에서 보지도 바꾸지도 않습니다.
      </p>

      {q.isError && (
        <p className="error" role="alert">
          {errorMessage(q.error)}
        </p>
      )}
      {q.isPending && (
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">매장 정보를 불러오는 중</span>
          <div className="skeleton" style={{ height: 160 }} />
          <div className="skeleton" style={{ height: 160 }} />
        </div>
      )}
      {flash && (
        <p className="success" role="status">
          {flash}
        </p>
      )}

      {c && sub && (
        <>
          <div className="op-grid">
            <section className="panel stack" aria-labelledby="care-store">
              <div className="row">
                <h2 id="care-store">매장 정보</h2>
                <span className="pill" data-tone={STORE_STATUS_TONE[c.store.status]}>
                  {STORE_STATUS_LABEL[c.store.status]}
                </span>
              </div>
              <div className="list">
                <Row label="매장명">{c.store.name}</Row>
                <Row label="매장 전화">{c.store.phone ? formatTel(c.store.phone) : "-"}</Row>
                <Row label="주소">{c.store.address || <span className="error">주소 미입력</span>}</Row>
                <Row label="지도 링크">
                  {c.store.naverPlaceUrl ? (
                    <a href={c.store.naverPlaceUrl} target="_blank" rel="noreferrer">
                      열기
                    </a>
                  ) : (
                    "-"
                  )}
                </Row>
                <Row label="사업자등록번호">{formatBusinessNumber(c.store.businessNumber)}</Row>
                <Row label="대표자 · 개업일">
                  {c.store.representativeName || "-"} · {c.store.openingDate || "-"}
                </Row>
                <Row label="사업자 확인">{c.store.businessVerifiedAt ? `국세청 확인 ${day(c.store.businessVerifiedAt)}` : "확인 기록 없음"}</Row>
                <Row label="가입">{time(c.store.createdAt)}</Row>
              </div>
              <div className="sheet-actions">
                <button type="button" className="btn secondary btn-inline" onClick={() => open("profile")}>
                  정보 수정
                </button>
                <button type="button" className="btn ghost btn-inline" onClick={() => open("pin")}>
                  직원 PIN 재발급
                </button>
              </div>
              <p className="hint">사업자등록번호·대표자·개업일은 국세청 확인 값이라 운영자도 바꾸지 않습니다.</p>
            </section>

            <section className="panel stack" aria-labelledby="care-billing">
              <div className="row">
                <h2 id="care-billing">요금제 · 결제</h2>
                <span className="pill" data-tone={SERVICE_STATE_TONE[sub.serviceState]}>
                  {SERVICE_STATE_LABEL[sub.serviceState]}
                </span>
              </div>
              <div className="list">
                <Row label="기능 등급">
                  {PLAN_LABEL[sub.effectivePlan]}
                  {hasComp && <small className="hint"> (보상 · {day(sub.complimentaryUntil)} 0시까지)</small>}
                </Row>
                <Row label="청구 등급">
                  {PLAN_LABEL[sub.billingPlan]}
                  {sub.monthlyPriceKrw ? ` · 월 ${won(sub.monthlyPriceKrw)}` : ""}
                  {sub.nextPlan && <small className="hint"> → 다음 결제부터 {PLAN_LABEL[sub.nextPlan]}</small>}
                </Row>
                {sub.trialEndsAt && <Row label="무료체험 종료">{time(sub.trialEndsAt)}</Row>}
                <Row label="다음 결제예정일">{sub.billingTarget ? time(sub.nextBillingAt) : "결제 대상 아님"}</Row>
                {sub.restrictedFrom && <Row label="미결제 시 이용 제한">{time(sub.restrictedFrom)}부터</Row>}
                <Row label="최근 결제">{time(sub.lastPaidAt)}</Row>
                <Row label="자동결제 · 카드">
                  {sub.autoRenew ? "켜짐" : "꺼짐"} · {sub.hasCard ? `등록됨${sub.pg ? ` (${sub.pg})` : ""}` : "없음"}
                </Row>
                {(sub.renewalFailures ?? 0) > 0 && (
                  <Row label="연속 결제 실패">
                    <span className="error">{sub.renewalFailures}회</span>
                  </Row>
                )}
              </div>
              <div className="sheet-actions">
                <button type="button" className="btn secondary btn-inline" disabled={!sub.billingTarget} onClick={() => open("postpone")}>
                  결제일 연기
                </button>
                <button type="button" className="btn secondary btn-inline" disabled={sub.billingPlan === "PRO"} onClick={() => open("comp")}>
                  보상 등급 제공
                </button>
                {hasComp && (
                  <button type="button" className="btn ghost btn-inline" onClick={() => open("compEnd")}>
                    보상 등급 종료
                  </button>
                )}
              </div>
              <p className="hint">보상 등급은 기능만 열고 청구는 청구 등급 그대로입니다. 결제일 연기는 무료체험 종료도 같이 밉니다.</p>
            </section>

            <section className="panel stack" aria-labelledby="care-members">
              <h2 id="care-members">매장 계정</h2>
              <div className="list">
                {c.members.map((m) => (
                  <div className="list-item" key={m.email}>
                    <span className="stack" style={{ gap: 2 }}>
                      <span className="name">
                        {m.name} <small className="hint">{m.role === "OWNER" ? "대표" : "매니저"}</small>
                      </span>
                      <small className="hint wrap-anywhere">{m.email}</small>
                    </span>
                    <span className="name">{m.phone ? formatPhone(m.phone) : "-"}</span>
                  </div>
                ))}
              </div>
            </section>

            <section className="panel stack" aria-labelledby="care-usage">
              <h2 id="care-usage">이용 현황 · 통계 추출</h2>
              <dl className="stats">
                <div>
                  <dt>최근 30일 참여</dt>
                  <dd>{count(c.usage.last30Days.plays)}</dd>
                </div>
                <div>
                  <dt>30일 쿠폰 사용</dt>
                  <dd>
                    {count(c.usage.last30Days.couponsRedeemed)}
                    <small className="hint">{c.usage.last30Days.redemptionRatePercent}%</small>
                  </dd>
                </div>
                <div>
                  <dt>누적 게임</dt>
                  <dd>{count(c.usage.gamesTotal)}</dd>
                </div>
              </dl>
              <p className="hint">마지막 참여: {time(c.usage.lastPlayedAt)}</p>
              <div className="op-toolbar">
                <div className="field">
                  <label htmlFor="range-from">시작일</label>
                  <input id="range-from" type="date" value={range.from} onChange={(e) => setRange({ ...range, from: e.target.value })} />
                </div>
                <div className="field">
                  <label htmlFor="range-to">종료일</label>
                  <input id="range-to" type="date" value={range.to} onChange={(e) => setRange({ ...range, to: e.target.value })} />
                </div>
                <div className="field">
                  <label htmlFor="range-reason">용도 (선택)</label>
                  <input
                    id="range-reason"
                    maxLength={200}
                    placeholder="사장님 세무 자료 요청"
                    value={range.reason}
                    onChange={(e) => setRange({ ...range, reason: e.target.value })}
                  />
                </div>
              </div>
              <div className="sheet-actions">
                <button type="button" className="btn secondary btn-inline" onClick={() => exportCsv("daily")}>
                  일자별 CSV
                </button>
                <button type="button" className="btn secondary btn-inline" onClick={() => exportCsv("prize")}>
                  상품별 CSV
                </button>
              </div>
              {exportError && (
                <p className="error" role="alert">
                  {exportError}
                </p>
              )}
              <p className="hint">요금제와 관계없이 뽑을 수 있고, 집계 숫자만 담깁니다(고객 이름·번호 없음). 추출 기록이 남습니다.</p>
            </section>
          </div>

          <section className="panel stack" aria-labelledby="care-payments">
            <h2 id="care-payments">결제 내역</h2>
            {c.payments.length === 0 ? (
              <p className="hint">결제 시도가 아직 없습니다.</p>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <caption className="visually-hidden">최근 결제 50건</caption>
                  <thead>
                    <tr>
                      <th scope="col">시도</th>
                      <th scope="col">등급</th>
                      <th scope="col">금액</th>
                      <th scope="col">상태</th>
                      <th scope="col">결제 완료</th>
                      <th scope="col">결제 번호 · 실패 사유</th>
                    </tr>
                  </thead>
                  <tbody>
                    {c.payments.map((p) => (
                      <tr key={p.id}>
                        <td>{time(p.createdAt)}</td>
                        <td>{PLAN_LABEL[p.plan]}</td>
                        <td>{won(p.amount)}</td>
                        <td>
                          <span className="pill" data-tone={PAYMENT_STATUS_TONE[p.status]}>
                            {PAYMENT_STATUS_LABEL[p.status]}
                          </span>
                        </td>
                        <td>{time(p.paidAt)}</td>
                        <td className="wrap-anywhere">
                          <small className="hint">{p.paymentId}</small>
                          {p.failureReason && (
                            <>
                              <br />
                              {p.failureReason}
                            </>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>

          <section className="panel stack" aria-labelledby="care-log">
            <h2 id="care-log">지원 기록</h2>
            {c.careLog.length === 0 ? (
              <p className="hint">운영자가 이 매장에 해 준 일이 아직 없습니다.</p>
            ) : (
              <div className="table-wrap">
                <table className="table">
                  <caption className="visually-hidden">운영자 지원 기록 최근 100건</caption>
                  <thead>
                    <tr>
                      <th scope="col">시각</th>
                      <th scope="col">동작</th>
                      <th scope="col">사유</th>
                      <th scope="col">바뀐 내용</th>
                      <th scope="col">운영자</th>
                    </tr>
                  </thead>
                  <tbody>
                    {c.careLog.map((e, i) => (
                      <tr key={`${e.createdAt}-${i}`}>
                        <td>{time(e.createdAt)}</td>
                        <td>{CARE_ACTION_LABEL[e.action]}</td>
                        <td className="wrap-anywhere">{e.reason}</td>
                        <td className="wrap-anywhere">{e.detail || "-"}</td>
                        <td className="wrap-anywhere">{e.actor}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </section>
        </>
      )}

      <Dialog open={sheet !== undefined} onClose={() => setSheet(undefined)} labelledBy="care-sheet-title">
        <form className="stack" onSubmit={submit}>
          <h2 id="care-sheet-title">{sheet ? SHEET_TITLE[sheet] : ""}</h2>
          {c && <p className="lead">{c.store.name}</p>}

          {sheet === "profile" && (
            <>
              <div className="field">
                <label htmlFor="care-name">매장명</label>
                <input id="care-name" maxLength={100} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} />
                <small className="hint">인쇄해 보낸 안내물·스티커의 상호가 바뀝니다. 필요하면 입점 키트를 다시 보내세요.</small>
              </div>
              <div className="field">
                <label htmlFor="care-phone">매장 전화</label>
                <input id="care-phone" inputMode="tel" maxLength={30} value={form.phone} onChange={(e) => setForm({ ...form, phone: e.target.value })} />
              </div>
              <div className="field">
                <label htmlFor="care-address">주소</label>
                <input id="care-address" maxLength={255} value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} />
              </div>
              <div className="field">
                <label htmlFor="care-url">지도 링크</label>
                <input
                  id="care-url"
                  type="url"
                  maxLength={500}
                  placeholder="https://"
                  value={form.naverPlaceUrl}
                  onChange={(e) => setForm({ ...form, naverPlaceUrl: e.target.value })}
                />
              </div>
            </>
          )}

          {sheet === "postpone" && sub && (
            <div className="field">
              <label htmlFor="care-days">연기할 일수 (1~90일)</label>
              <input id="care-days" type="number" min={1} max={90} value={form.days} onChange={(e) => setForm({ ...form, days: e.target.value })} />
              <small className="hint">
                지금 결제예정일: {day(sub.nextBillingAt)}
                {sub.trialEndsAt ? " · 무료체험 종료도 같은 날수만큼 밀립니다" : ""} · 이용 제한 중이면 연기한 날짜가 미래일 때 바로
                풀립니다.
              </small>
            </div>
          )}

          {sheet === "comp" && sub && (
            <>
              <div className="field">
                <label htmlFor="care-plan">제공 등급</label>
                <select id="care-plan" value={form.plan} onChange={(e) => setForm({ ...form, plan: e.target.value })}>
                  {PLANS.filter((p) => PLANS.indexOf(p) > PLANS.indexOf(sub.billingPlan)).map((p) => (
                      <option key={p} value={p}>
                        {PLAN_LABEL[p]}
                      </option>
                    ))}
                </select>
              </div>
              <div className="field">
                <label htmlFor="care-comp-days">제공 기간 (1~365일, 오늘 포함)</label>
                <input
                  id="care-comp-days"
                  type="number"
                  min={1}
                  max={365}
                  value={form.days}
                  onChange={(e) => setForm({ ...form, days: e.target.value })}
                />
                <small className="hint">청구는 {PLAN_LABEL[sub.billingPlan]} 그대로입니다. 기간이 지나면 저절로 원래 등급으로 돌아갑니다.</small>
              </div>
            </>
          )}

          {sheet === "compEnd" && sub && (
            <p className="notice">
              {sub.complimentaryPlan && PLAN_LABEL[sub.complimentaryPlan]} 보상을 지금 끝냅니다. 기능 등급이 {PLAN_LABEL[sub.billingPlan]}(으)로
              돌아갑니다.
            </p>
          )}

          {sheet === "pin" && c && (
            <>
              <p className="error" role="note">
                기존 PIN은 즉시 쓸 수 없게 됩니다. 매장 직원 모두 새 PIN을 받아야 쿠폰을 사용 처리할 수 있습니다. 되돌릴 수 없습니다.
              </p>
              <p className="hint">새 PIN은 운영자에게 보이지 않습니다. 사장님이 매장 관리자 화면 &quot;직원 PIN&quot;에서 확인합니다.</p>
              <div className="field">
                <label htmlFor="care-pin-confirm">
                  확인을 위해 매장명 <b>{c.store.name}</b>을(를) 그대로 입력해 주세요
                </label>
                <input
                  id="care-pin-confirm"
                  autoComplete="off"
                  required
                  value={form.confirmName}
                  onChange={(e) => setForm({ ...form, confirmName: e.target.value })}
                />
                {form.confirmName && form.confirmName.trim() !== c.store.name.trim() && (
                  <small className="hint">매장명이 일치하지 않습니다.</small>
                )}
              </div>
            </>
          )}

          <div className="field">
            <label htmlFor="care-reason">사유 (필수)</label>
            <input
              id="care-reason"
              required
              maxLength={200}
              placeholder="예: 9/30 결제창 장애 보상, 사장님 전화 요청"
              value={form.reason}
              onChange={(e) => setForm({ ...form, reason: e.target.value })}
            />
            <small className="hint">지원 기록과 활동 기록에 그대로 남습니다.</small>
          </div>

          {act.isError && (
            <p className="error" role="alert">
              {errorMessage(act.error)}
            </p>
          )}

          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={() => setSheet(undefined)}>
              취소
            </button>
            <button className="btn" disabled={act.isPending || (sheet === "pin" && form.confirmName.trim() !== c?.store.name.trim())}>
              {act.isPending ? "처리 중" : "실행"}
            </button>
          </div>
        </form>
      </Dialog>
    </OperatorFrame>
  );
}
