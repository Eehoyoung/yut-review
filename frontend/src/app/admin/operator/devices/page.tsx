"use client";
import { FormEvent, useState } from "react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { OperatorFrame } from "@/features/admin/OperatorFrame";
import { Dialog } from "@/features/ui/Dialog";
import { api, errorMessage } from "@/lib/api";
import { authenticateDevice, registerDevice, webauthnAvailable } from "@/lib/webauthn";
import type { OperatorAccessStatus, OperatorDevice } from "@/types/api";

/**
 * 운영자 기기 관리.
 *
 * 이 화면은 접근 통제의 **상태를 보여 주는 유일한 자리**다. 통제가 켜져 있는지, 지금 IP가
 * 허용 목록에 있는지, 기기가 몇 개 등록됐는지를 여기서 보지 못하면 운영자는 자기가 언제
 * 잠기는지 모른 채 쓰게 된다.
 *
 * 등록 자체는 문지기를 지나야 한다. 그래서 **첫 기기는 허용 IP에서만 등록된다.** 아무 데서나
 * 등록할 수 있으면 기기 인증이 아무것도 막지 않는다.
 */
export default function OperatorDevicesPage() {
  const qc = useQueryClient();
  const [name, setName] = useState("");
  const [adding, setAdding] = useState(false);
  const [removing, setRemoving] = useState<OperatorDevice | undefined>();
  const [flash, setFlash] = useState("");
  const [failure, setFailure] = useState("");

  const status = useQuery({
    queryKey: ["operator-access"],
    queryFn: () => api<OperatorAccessStatus>("/admin/operator/access/status"),
  });

  const refresh = () => qc.invalidateQueries({ queryKey: ["operator-access"] });

  // WebAuthn은 fetch가 아니라 브라우저 대화상자라서 실패 문구가 ApiClientError가 아닐 수 있다.
  // 그래서 문자열로 따로 받는다.
  const register = useMutation({
    mutationFn: () => registerDevice(name.trim()),
    onSuccess: (d) => {
      setFlash(`기기 "${d.name}"을(를) 등록했습니다. 등록된 기기 ${d.deviceCount}개.`);
      setAdding(false);
      setName("");
      setFailure("");
      refresh();
    },
    onError: (e) => setFailure(e instanceof Error ? e.message : errorMessage(e)),
  });

  const verify = useMutation({
    mutationFn: () => authenticateDevice(),
    onSuccess: (d) => {
      setFlash(`"${d.deviceName}"으로 인증했습니다. 이 브라우저는 4시간 동안 열립니다.`);
      setFailure("");
      refresh();
    },
    onError: (e) => setFailure(e instanceof Error ? e.message : errorMessage(e)),
  });

  const remove = useMutation({
    mutationFn: (id: number) => api<{ removed: boolean }>(`/admin/operator/access/devices/${id}`, { method: "DELETE" }),
    onSuccess: () => {
      setFlash("기기를 지웠습니다. 그 기기로 받은 통행증도 함께 끊겼습니다.");
      setRemoving(undefined);
      refresh();
    },
  });

  const submitAdd = (event: FormEvent) => {
    event.preventDefault();
    if (!name.trim()) {
      document.getElementById("device-name")?.focus();
      return;
    }
    register.mutate();
  };

  const data = status.data;
  const supported = webauthnAvailable();

  return (
    <OperatorFrame title="기기">
      {data && (
        <section className="panel stack">
          <div className="row">
            <h2>접근 통제</h2>
            <span className="pill" data-tone={data.enabled ? "brand" : "muted"}>
              {data.enabled ? "켜짐" : "꺼짐"}
            </span>
          </div>
          {data.enabled ? (
            <div className="list">
              <div className="list-item">
                <span className="lead">지금 이 위치</span>
                <span className="name">{data.ipAllowed ? "허용 IP — 기기 인증 없이 열립니다" : "허용 목록 밖 — 기기 인증이 필요합니다"}</span>
              </div>
              <div className="list-item">
                <span className="lead">등록된 기기</span>
                <span className="name">{data.deviceCount}개</span>
              </div>
            </div>
          ) : (
            <p className="lead">
              지금은 누구나 운영자 콘솔을 쓸 수 있습니다. 서버에서 <code>OPERATOR_ACCESS_ENABLED=true</code>로
              켭니다.
            </p>
          )}

          {data.enabled && data.ipAllowed && data.deviceCount === 0 && (
            <p className="notice">
              등록된 기기가 없습니다. <strong>지금 이 자리에서 등록해 두세요.</strong> 허용 IP는 인터넷 회선 사정으로
              바뀔 수 있고, 바뀐 뒤에는 등록할 방법이 없습니다.
            </p>
          )}

          {data.enabled && data.ipAllowed && data.deviceCount === 1 && (
            <p className="notice">
              기기가 하나뿐입니다. 그 기기를 잃으면 허용 IP 밖에서는 들어올 수 없습니다. 휴대폰을 하나 더
              등록해 두는 편이 안전합니다.
            </p>
          )}

          {data.enabled && !data.ipAllowed && (
            <p className="notice">
              허용 목록 밖입니다. 지금 화면이 보이는 것은 통행증이 살아 있기 때문입니다. 끊기면 아래
              &ldquo;이 기기로 인증&rdquo;을 누르세요.
            </p>
          )}

          <div className="sheet-actions">
            <button type="button" className="btn btn-inline" disabled={!supported} onClick={() => setAdding(true)}>
              기기 등록
            </button>
            {data.deviceCount > 0 && (
              <button
                type="button"
                className="btn secondary btn-inline"
                disabled={!supported || verify.isPending}
                onClick={() => verify.mutate()}
              >
                {verify.isPending ? "인증 중" : "이 기기로 인증"}
              </button>
            )}
          </div>

          {!supported && (
            <p className="error">
              이 브라우저는 기기 인증을 지원하지 않습니다. Chrome, Safari, Firefox 최신 버전을 사용해 주세요.
            </p>
          )}
        </section>
      )}

      {flash && (
        <p className="success" role="status">
          {flash}
        </p>
      )}
      {failure && !adding && (
        <p className="error" role="alert">
          {failure}
        </p>
      )}
      {status.isError && (
        <p className="error" role="alert">
          {errorMessage(status.error)}
        </p>
      )}

      {status.isPending && (
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">기기 목록을 불러오는 중</span>
          <div className="skeleton" style={{ height: 96 }} />
        </div>
      )}

      {data?.devices.length === 0 && (
        <section className="panel stack">
          <h2>등록된 기기가 없어요</h2>
          <p className="lead">노트북과 휴대폰을 각각 등록해 두면 하나를 잃어도 들어올 수 있습니다.</p>
        </section>
      )}

      {data?.devices.map((d) => (
        <section className="panel stack" key={d.id}>
          <h2>{d.name}</h2>
          <div className="list">
            <div className="list-item">
              <span className="lead">등록</span>
              <span className="name">{new Date(d.createdAt).toLocaleString("ko-KR")}</span>
            </div>
            <div className="list-item">
              <span className="lead">마지막 사용</span>
              <span className="name">{d.lastUsedAt ? new Date(d.lastUsedAt).toLocaleString("ko-KR") : "없음"}</span>
            </div>
          </div>
          <div className="sheet-actions">
            <button type="button" className="btn secondary btn-inline" onClick={() => setRemoving(d)}>
              지우기
            </button>
          </div>
        </section>
      ))}

      <Dialog open={adding} onClose={() => setAdding(false)} labelledBy="device-add-title">
        <form className="stack" onSubmit={submitAdd}>
          <h2 id="device-add-title">기기 등록</h2>
          <p className="lead">
            등록을 누르면 브라우저가 지문·얼굴 또는 화면 잠금을 물어봅니다. 개인키는 이 기기를 벗어나지 않습니다.
          </p>
          <div className="field">
            <label htmlFor="device-name">기기 이름</label>
            <input
              id="device-name"
              autoFocus
              maxLength={60}
              placeholder="업무용 노트북"
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
            <small className="hint">나중에 어느 기기인지 알아볼 수 있는 이름으로.</small>
          </div>
          {failure && (
            <p className="error" role="alert">
              {failure}
            </p>
          )}
          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={() => setAdding(false)}>
              취소
            </button>
            <button className="btn" disabled={register.isPending}>
              {register.isPending ? "등록 중" : "등록"}
            </button>
          </div>
        </form>
      </Dialog>

      <Dialog open={removing !== undefined} onClose={() => setRemoving(undefined)} labelledBy="device-remove-title">
        <div className="stack">
          <h2 id="device-remove-title">기기를 지울까요?</h2>
          <p className="lead">{removing?.name}</p>
          {data?.deviceCount === 1 && (
            <p className="error">
              마지막 기기입니다. 지우면 허용 IP 밖에서는 들어올 수 없습니다.
            </p>
          )}
          {remove.isError && (
            <p className="error" role="alert">
              {errorMessage(remove.error)}
            </p>
          )}
          <div className="sheet-actions">
            <button type="button" className="btn ghost" onClick={() => setRemoving(undefined)}>
              취소
            </button>
            <button
              type="button"
              className="btn"
              disabled={remove.isPending}
              onClick={() => removing && remove.mutate(removing.id)}
            >
              {remove.isPending ? "처리 중" : "지우기"}
            </button>
          </div>
        </div>
      </Dialog>
    </OperatorFrame>
  );
}
