"use client";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { PRINT_KIT_STATUS_LABEL } from "@/features/operator/labels";
import { Dialog } from "@/features/ui/Dialog";
import { api, errorMessage } from "@/lib/api";
import type { PrintKitStatus } from "@/types/api";

type QrInfo = {
  storeId: number;
  name: string;
  issuedAt: string | null;
  printKitStatus: PrintKitStatus;
  history: { issuedAt: string; revokedAt: string; revokedBy: string; reason: string }[];
};
type Result = { previousPrintKitStatus: PrintKitStatus; printKitReset: boolean };

/**
 * 운영자 전용 QR 재발급. 사장 화면에는 이 기능이 없다.
 *
 * 되돌릴 수 없는 동작이라 안전장치를 겹으로 둔다: 무엇이 죽는지(인쇄·발송한 키트 포함)를 먼저 보여 주고,
 * 사유를 받고, 매장명을 그대로 다시 쳐야 버튼이 동작한다. 서버도 같은 매장명·사유와 10분 재발급 간격을 다시 검사한다.
 */
export function QrRegenerateDialog({ store, onClose, onDone }: {
  store?: { id: number; name: string };
  onClose: () => void;
  onDone: (message: string) => void;
}) {
  const qc = useQueryClient();
  const [reason, setReason] = useState("");
  const [confirmName, setConfirmName] = useState("");
  const [tried, setTried] = useState(false);

  const info = useQuery({
    queryKey: ["operator-qr", store?.id],
    queryFn: () => api<QrInfo>(`/operator/stores/${store!.id}/qr`),
    enabled: store !== undefined,
  });

  const regenerate = useMutation({
    mutationFn: () =>
      api<Result>(`/operator/stores/${store!.id}/qr/regenerate`, {
        method: "POST",
        body: JSON.stringify({ confirmName, reason: reason.trim() }),
      }),
    onSuccess: (data) => {
      qc.invalidateQueries({ queryKey: ["operator-qr", store?.id] });
      qc.invalidateQueries({ queryKey: ["print-kits"] });
      onDone(
        data.printKitReset
          ? `${store?.name}: 새 QR을 발급했습니다. 입점 키트가 '${PRINT_KIT_STATUS_LABEL[data.previousPrintKitStatus]}'에서 '인쇄 대기'로 돌아갔으니 새 PDF로 다시 인쇄해 주세요.`
          : `${store?.name}: 새 QR을 발급했습니다.`,
      );
      close();
    },
  });

  const close = () => {
    setReason("");
    setConfirmName("");
    setTried(false);
    regenerate.reset();
    onClose();
  };

  const nameMatches = store !== undefined && confirmName.trim() === store.name.trim();
  const blocked = !reason.trim() ? "재발급 사유를 입력해 주세요." : !nameMatches ? "매장명을 정확히 입력해 주세요." : "";
  const kit = info.data?.printKitStatus;
  const kitAlreadyOut = kit !== undefined && kit !== "WAITING";

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setTried(true);
    if (blocked) return;
    regenerate.mutate();
  };

  return (
    <Dialog open={store !== undefined} onClose={close} labelledBy="qr-regen-title">
      <form className="stack" onSubmit={submit}>
        <h2 id="qr-regen-title">QR을 다시 발급할까요?</h2>
        <p className="lead">{store?.name}</p>

        <p className="error" role="note">
          기존 QR은 즉시 쓸 수 없게 됩니다. 매장에 붙어 있는 안내물과 스티커를 찍으면 더 이상 게임으로 들어가지 않습니다.
          되돌릴 수 없습니다.
        </p>
        {info.data && (
          <ul className="list">
            <li className="list-item">
              <span className="lead">지금 QR 발급</span>
              <span className="name">{info.data.issuedAt ? new Date(info.data.issuedAt).toLocaleString("ko-KR") : "없음"}</span>
            </li>
            <li className="list-item">
              <span className="lead">입점 키트</span>
              <span className={kitAlreadyOut ? "error" : "name"}>
                {PRINT_KIT_STATUS_LABEL[info.data.printKitStatus]}
                {kitAlreadyOut && " — 인쇄·발송한 키트가 쓸모없어집니다. 재발급하면 인쇄 대기로 돌아갑니다."}
              </span>
            </li>
            {info.data.history.length > 0 && (
              <li className="list-item">
                <span className="lead">지난 재발급</span>
                <span className="name">
                  {info.data.history.length}회 · 마지막 {new Date(info.data.history[0].revokedAt).toLocaleDateString("ko-KR")} ({info.data.history[0].reason || "사유 없음"})
                </span>
              </li>
            )}
          </ul>
        )}
        {info.isError && (
          <p className="error" role="alert">
            {errorMessage(info.error)}
          </p>
        )}

        <div className="field">
          <label htmlFor="qr-regen-reason">재발급 사유</label>
          <textarea
            id="qr-regen-reason"
            maxLength={200}
            value={reason}
            placeholder="예: QR 사진이 외부 커뮤니티에 올라감"
            onChange={(e) => setReason(e.target.value)}
          />
          <small className="hint">QR 이력에 남습니다. 200자까지.</small>
        </div>
        <div className="field">
          <label htmlFor="qr-regen-confirm">확인을 위해 매장명 <b>{store?.name}</b>을(를) 그대로 입력해 주세요</label>
          <input
            id="qr-regen-confirm"
            autoComplete="off"
            value={confirmName}
            onChange={(e) => setConfirmName(e.target.value)}
          />
        </div>

        {tried && blocked && (
          <p className="notice" role="status">
            {blocked}
          </p>
        )}
        {regenerate.isError && (
          <p className="error" role="alert">
            {errorMessage(regenerate.error)}
          </p>
        )}

        <div className="sheet-actions">
          <button type="button" className="btn ghost" onClick={close} autoFocus>
            취소
          </button>
          <button className="btn" disabled={regenerate.isPending}>
            {regenerate.isPending ? "발급 중" : "QR 재발급"}
          </button>
        </div>
      </form>
    </Dialog>
  );
}
