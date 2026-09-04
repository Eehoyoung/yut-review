"use client";
import { ReactNode, useEffect, useRef } from "react";

/**
 * 네이티브 <dialog> + showModal() 기반 공용 모달/바텀시트.
 *
 * 포커스 트랩, Esc 닫기, 배경 콘텐츠 비활성화(inert)는 브라우저가 이미 해 준다 — 직접 구현하지 않는다.
 * 닫히는 경로는 하나뿐이다: Esc 든 배경 클릭이든 항상 네이티브 close 이벤트를 거쳐 onClose 를 부른다.
 * 그래서 호출하는 쪽은 "닫아라"라는 뜻으로 open 을 false 로만 바꾸면 되고, 실제 정리(cleanup)는
 * onClose 한 곳에서만 하면 된다.
 */
export function Dialog({
  open,
  onClose,
  labelledBy,
  children,
}: {
  open: boolean;
  onClose: () => void;
  labelledBy: string;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDialogElement>(null);

  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    // showModal/close 는 이미 그 상태면 예외를 던지므로 el.open 을 먼저 확인한다.
    if (open && !el.open) el.showModal();
    if (!open && el.open) el.close();
  }, [open]);

  return (
    <dialog
      ref={ref}
      className="dialog"
      aria-labelledby={labelledBy}
      onClose={onClose}
      onClick={(e) => {
        // dialog 자체가 클릭 타깃이면 배경(백드롭) 클릭이다. 내부 요소 클릭은 버블링돼도 타깃이 다르다.
        if (e.target === ref.current) ref.current?.close();
      }}
    >
      {children}
    </dialog>
  );
}
