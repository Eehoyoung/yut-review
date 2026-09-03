"use client";
import { FormEvent, useEffect, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { api, errorMessage } from "@/lib/api";
import type { GameConfig, GameConfigOutcome, Prize, YutResult } from "@/types/api";
import { YUT_LABEL, rankLabel } from "@/features/labels";

const YUT_ORDER: YutResult[] = ["DO", "GAE", "GEOL", "YUT", "MO"];
const MAX_RANK = 5;
const MAX_WEIGHT = 1000;

/** Rank ladders offered as one-tap presets. Weights are left alone; only the mapping changes. */
const PRESETS: Record<number, Record<YutResult, number>> = {
  3: { DO: 3, GAE: 3, GEOL: 2, YUT: 2, MO: 1 },
  4: { DO: 4, GAE: 4, GEOL: 3, YUT: 2, MO: 1 },
  5: { DO: 5, GAE: 4, GEOL: 3, YUT: 2, MO: 1 },
};

type Draft = { weight: number; prizeRank: number };

function percent(draft: Record<YutResult, Draft>, keep: (d: Draft) => boolean) {
  const all = YUT_ORDER.map((y) => draft[y]);
  const total = all.reduce((sum, d) => sum + d.weight, 0);
  if (total === 0) return 0;
  const part = all.filter(keep).reduce((sum, d) => sum + d.weight, 0);
  return Math.round((part * 1000) / total) / 10;
}

/** Mirrors the server rules so the owner sees the problem before saving, not after. */
function problem(draft: Record<YutResult, Draft>) {
  const all = YUT_ORDER.map((y) => draft[y]);
  if (all.some((d) => !Number.isFinite(d.weight) || d.weight < 0 || d.weight > MAX_WEIGHT))
    return `가중치는 0에서 ${MAX_WEIGHT} 사이여야 합니다.`;
  if (all.reduce((sum, d) => sum + d.weight, 0) < 1) return "가중치 합이 0이면 결과를 뽑을 수 없습니다.";
  const ranks = [...new Set(all.map((d) => d.prizeRank))].sort((a, b) => a - b);
  if (ranks.some((rank, i) => rank !== i + 1)) return "등급은 1등부터 빠짐없이 이어져야 합니다.";
  return "";
}

function GameConfigForm({ storeId, config }: { storeId: string; config: GameConfig }) {
  const qc = useQueryClient();
  const [draft, setDraft] = useState<Record<YutResult, Draft>>(() => toDraft(config.outcomes));
  useEffect(() => setDraft(toDraft(config.outcomes)), [config.outcomes]);

  const save = useMutation({
    mutationFn: () =>
      api<GameConfig>(`/admin/stores/${storeId}/game-config`, {
        method: "PUT",
        body: JSON.stringify({ outcomes: YUT_ORDER.map((y) => ({ yutResult: y, ...draft[y] })) }),
      }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["game-config", storeId] });
      qc.invalidateQueries({ queryKey: ["prizes", storeId] });
    },
  });

  const ranks = [...new Set(YUT_ORDER.map((y) => draft[y].prizeRank))].sort((a, b) => a - b);
  const blocked = problem(draft);

  return (
    <form
      className="panel stack"
      onSubmit={(e: FormEvent) => {
        e.preventDefault();
        save.mutate();
      }}
    >
      <div className="row">
        <h2>등급 수와 확률</h2>
        <span className="pill" data-tone="wood">
          현재 {ranks.length}등급
        </span>
      </div>
      <p className="lead">
        확률은 등급이 아니라 윷 결과에 붙습니다. 화면에 실제로 떨어지는 것이 도·개·걸·윷·모 다섯 가지라서, 결과별로
        가중치를 두어야 던진 모양과 드리는 상품이 어긋나지 않습니다.
      </p>

      <div className="preset-row" role="group" aria-label="등급 수 프리셋">
        {[3, 4, 5].map((count) => (
          <button
            key={count}
            type="button"
            className={ranks.length === count ? "btn secondary is-on" : "btn secondary"}
            onClick={() =>
              setDraft((prev) => {
                const next = { ...prev };
                YUT_ORDER.forEach((y) => (next[y] = { ...prev[y], prizeRank: PRESETS[count][y] }));
                return next;
              })
            }
          >
            {count}등급
          </button>
        ))}
      </div>

      <div className="config-table">
        {YUT_ORDER.map((y) => {
          const odds = percent(draft, (d) => d === draft[y]);
          return (
            <div className="config-row" key={y}>
              <div className="config-head">
                <span className="config-yut">{YUT_LABEL[y]}</span>
                <span className="config-odds" aria-label={`${YUT_LABEL[y]} 확률`}>
                  {odds}%
                </span>
              </div>
              <label className="field config-weight">
                <span>가중치</span>
                <input
                  type="number"
                  min={0}
                  max={MAX_WEIGHT}
                  inputMode="numeric"
                  value={draft[y].weight}
                  onChange={(e) =>
                    setDraft({ ...draft, [y]: { ...draft[y], weight: Number(e.target.value) } })
                  }
                />
              </label>
              <label className="field config-rank">
                <span>상품 등급</span>
                <select
                  value={draft[y].prizeRank}
                  onChange={(e) =>
                    setDraft({ ...draft, [y]: { ...draft[y], prizeRank: Number(e.target.value) } })
                  }
                >
                  {Array.from({ length: MAX_RANK }, (_, i) => i + 1).map((rank) => (
                    <option key={rank} value={rank}>
                      {rankLabel(rank)}
                    </option>
                  ))}
                </select>
              </label>
            </div>
          );
        })}
      </div>

      <div className="list">
        {ranks.map((rank) => (
          <div className="list-item" key={rank}>
            <span className="lead">{rankLabel(rank)} 당첨 확률</span>
            <span className="name">{percent(draft, (d) => d.prizeRank === rank)}%</span>
          </div>
        ))}
      </div>

      {ranks.some((rank) => percent(draft, (d) => d.prizeRank === rank) === 0) && (
        <p className="notice" role="status">
          확률이 0%인 등급은 고객 화면의 상품 목록에 표시되지 않습니다.
        </p>
      )}
      {blocked && (
        <p className="error" role="alert">
          {blocked}
        </p>
      )}
      {save.isError && (
        <p className="error" role="alert">
          {errorMessage(save.error)}
        </p>
      )}
      <button className="btn" disabled={save.isPending || blocked !== ""}>
        {save.isPending ? "저장 중..." : "확률 설정 저장"}
      </button>
    </form>
  );
}

function toDraft(outcomes: GameConfigOutcome[]): Record<YutResult, Draft> {
  const draft = {} as Record<YutResult, Draft>;
  YUT_ORDER.forEach((y) => {
    const found = outcomes.find((o) => o.yutResult === y);
    draft[y] = { weight: found?.weight ?? 0, prizeRank: found?.prizeRank ?? 1 };
  });
  return draft;
}

function PrizeCard({ storeId, prize }: { storeId: string; prize: Prize }) {
  const qc = useQueryClient();
  const [form, setForm] = useState(prize);
  useEffect(() => setForm(prize), [prize]);
  const m = useMutation({
    mutationFn: () =>
      api<Prize>(`/admin/stores/${storeId}/prizes/${prize.rank}`, {
        method: "PUT",
        body: JSON.stringify({
          name: form.name,
          description: form.description,
          redeemPolicy: form.redeemPolicy,
          active: form.active,
        }),
      }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["prizes", storeId] }),
  });
  return (
    <form
      className="panel stack"
      onSubmit={(e: FormEvent) => {
        e.preventDefault();
        m.mutate();
      }}
    >
      <div className="row">
        <h3>{rankLabel(prize.rank)} 상품</h3>
        {!prize.active && (
          <span className="pill" data-tone="off">
            사용 안 함
          </span>
        )}
      </div>
      <div className="field">
        <label htmlFor={`name-${prize.rank}`}>상품명</label>
        <input
          id={`name-${prize.rank}`}
          value={form.name}
          onChange={(e) => setForm({ ...form, name: e.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor={`desc-${prize.rank}`}>설명</label>
        <textarea
          id={`desc-${prize.rank}`}
          value={form.description}
          onChange={(e) => setForm({ ...form, description: e.target.value })}
        />
      </div>
      <div className="field">
        <label htmlFor={`policy-${prize.rank}`}>사용 시점</label>
        <select
          id={`policy-${prize.rank}`}
          value={form.redeemPolicy}
          onChange={(e) => setForm({ ...form, redeemPolicy: e.target.value as Prize["redeemPolicy"] })}
        >
          <option value="ANYTIME">즉시</option>
          <option value="SAME_DAY">당일</option>
          <option value="NEXT_DAY">다음 날</option>
        </select>
      </div>
      <label className="check">
        <input
          type="checkbox"
          checked={form.active}
          onChange={(e) => setForm({ ...form, active: e.target.checked })}
        />
        <span>상품 활성화</span>
      </label>
      {m.isError && (
        <p className="error" role="alert">
          {errorMessage(m.error)}
        </p>
      )}
      <button className="btn" disabled={m.isPending}>
        저장
      </button>
    </form>
  );
}

export default function Prizes() {
  const id = String(useParams().storeId);
  const config = useQuery({
    queryKey: ["game-config", id],
    queryFn: () => api<GameConfig>(`/admin/stores/${id}/game-config`),
  });
  const q = useQuery({ queryKey: ["prizes", id], queryFn: () => api<Prize[]>(`/admin/stores/${id}/prizes`) });
  return (
    <AdminFrame title="상품 설정">
      {config.isError && (
        <p className="error" role="alert">
          {errorMessage(config.error)}
        </p>
      )}
      {config.data && <GameConfigForm storeId={id} config={config.data} />}

      <h2 className="section-head">등급별 상품</h2>
      {q.isError && (
        <p className="error" role="alert">
          {errorMessage(q.error)}
        </p>
      )}
      <div className="stack">
        {q.data?.map((p) => (
          <PrizeCard key={p.rank} storeId={id} prize={p} />
        ))}
      </div>
    </AdminFrame>
  );
}
