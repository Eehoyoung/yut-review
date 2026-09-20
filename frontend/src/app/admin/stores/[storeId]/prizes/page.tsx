"use client";
import { FormEvent, useEffect, useId, useState } from "react";
import { useParams } from "next/navigation";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { AdminFrame } from "@/features/admin/AdminFrame";
import { ApiClientError, api, errorMessage } from "@/lib/api";
import type { GameConfig, Prize, RedeemPolicy, YutResult } from "@/types/api";
import { YUT_LABEL, rankLabel } from "@/features/labels";
import { onlyDecimal } from "@/features/normalize";

const YUT_ORDER: YutResult[] = ["DO", "GAE", "GEOL", "YUT", "MO"];
const LADDERS = [3, 4, 5];

/** Rank ladders offered as one-tap presets. Percentages are left alone; only the mapping changes. */
const PRESETS: Record<number, Record<YutResult, number>> = {
  3: { DO: 3, GAE: 3, GEOL: 2, YUT: 2, MO: 1 },
  4: { DO: 4, GAE: 4, GEOL: 3, YUT: 2, MO: 1 },
  5: { DO: 5, GAE: 4, GEOL: 3, YUT: 2, MO: 1 },
};

/**
 * Owners think in percentages, the server stores integer weights out of 1000. One decimal place of
 * percent is exactly one unit of weight, so the two are the same number with the point moved.
 */
const toTenths = (percent: string) => Math.round(Number(percent) * 10);
const toPercent = (weight: number) => String(weight / 10);

const POLICY_HELP: { value: RedeemPolicy; label: string; help: string }[] = [
  { value: "ANYTIME", label: "기간 내", help: "발급 즉시 사용할 수 있습니다." },
  { value: "SAME_DAY", label: "당일", help: "발급 당일부터 사용할 수 있습니다." },
  { value: "NEXT_DAY", label: "다음 날", help: "발급 다음 날 0시부터 사용할 수 있습니다." },
];

type OutcomeDraft = { percent: string; prizeRank: number };
type PrizeDraft = { name: string; description: string; redeemPolicy: RedeemPolicy };
type Draft = { ladder: number; outcomes: Record<YutResult, OutcomeDraft>; prizes: Record<number, PrizeDraft> };

function buildDraft(config: GameConfig, prizes: Prize[]): Draft {
  const outcomes = {} as Record<YutResult, OutcomeDraft>;
  YUT_ORDER.forEach((y) => {
    const found = config.outcomes.find((o) => o.yutResult === y);
    outcomes[y] = { percent: toPercent(found?.weight ?? 0), prizeRank: found?.prizeRank ?? 1 };
  });
  const ladder = Math.max(...YUT_ORDER.map((y) => outcomes[y].prizeRank), 1);
  const drafts: Record<number, PrizeDraft> = {};
  for (let rank = 1; rank <= 5; rank++) {
    const found = prizes.find((p) => p.rank === rank);
    drafts[rank] = {
      name: found?.name ?? `${rank}등 상품`,
      description: found?.description ?? "",
      redeemPolicy: found?.redeemPolicy ?? "ANYTIME",
    };
  }
  return { ladder, outcomes, prizes: drafts };
}

const tenthsOf = (draft: Draft) => YUT_ORDER.reduce((sum, y) => sum + toTenths(draft.outcomes[y].percent), 0);

/** Percentage of all throws that land on a given rank. */
const rankShare = (draft: Draft, rank: number) =>
  YUT_ORDER.filter((y) => draft.outcomes[y].prizeRank === rank).reduce(
    (sum, y) => sum + toTenths(draft.outcomes[y].percent),
    0,
  ) / 10;

/** Blocks the save on anything a store could get wrong by accident, before the server sees it. */
function problem(draft: Draft) {
  const values = YUT_ORDER.map((y) => Number(draft.outcomes[y].percent));
  if (values.some((v) => !Number.isFinite(v) || v < 0 || v > 100)) return "확률은 0에서 100 사이로 입력해 주세요.";
  const total = tenthsOf(draft);
  if (total !== 1000) {
    const diff = (1000 - total) / 10;
    return diff > 0 ? `확률 합이 ${100 - diff}%입니다. ${diff}% 더 배분해 주세요.` : `확률 합이 ${-diff}% 초과했습니다.`;
  }
  for (let rank = 1; rank <= draft.ladder; rank++)
    if (!YUT_ORDER.some((y) => draft.outcomes[y].prizeRank === rank))
      return `${rankLabel(rank)}에 배정된 윷 결과가 없습니다.`;
  for (let rank = 1; rank <= draft.ladder; rank++)
    if (!draft.prizes[rank].name.trim()) return `${rankLabel(rank)} 상품명을 입력해 주세요.`;
  return "";
}

function PolicyHelp() {
  const [open, setOpen] = useState(false);
  const helpId = useId();
  return (
    <>
      <button
        type="button"
        className="hint-toggle"
        aria-expanded={open}
        aria-controls={helpId}
        onClick={() => setOpen(!open)}
      >
        <span aria-hidden="true">?</span>
        <span className="visually-hidden">사용 시점 설명 {open ? "닫기" : "보기"}</span>
      </button>
      {open && (
        <dl className="hint-popover" id={helpId}>
          {POLICY_HELP.map((p) => (
            <div key={p.value}>
              <dt>{p.label}</dt>
              <dd>{p.help}</dd>
            </div>
          ))}
        </dl>
      )}
    </>
  );
}

export default function Prizes() {
  const id = String(useParams().storeId);
  const qc = useQueryClient();
  const config = useQuery({
    queryKey: ["game-config", id],
    queryFn: () => api<GameConfig>(`/admin/stores/${id}/game-config`),
  });
  const prizes = useQuery({ queryKey: ["prizes", id], queryFn: () => api<Prize[]>(`/admin/stores/${id}/prizes`) });
  const [draft, setDraft] = useState<Draft>();

  useEffect(() => {
    if (config.data && prizes.data) setDraft(buildDraft(config.data, prizes.data));
  }, [config.data, prizes.data]);

  const save = useMutation({
    mutationFn: async () => {
      const d = draft!;
      // The ladder has to exist before its prizes can be written, so the config goes first.
      await api<GameConfig>(`/admin/stores/${id}/game-config`, {
        method: "PUT",
        body: JSON.stringify({
          outcomes: YUT_ORDER.map((y) => ({
            yutResult: y,
            weight: toTenths(d.outcomes[y].percent),
            prizeRank: d.outcomes[y].prizeRank,
          })),
        }),
      });
      let successRank = 0;
      for (let rank = 1; rank <= d.ladder; rank++) {
        try {
          await api<Prize>(`/admin/stores/${id}/prizes/${rank}`, {
            method: "PUT",
            body: JSON.stringify({ ...d.prizes[rank], active: true }),
          });
          successRank = rank;
        } catch {
          // errorMessage()는 ApiClientError가 아니면 문구를 버리고 "잠시 후 다시 시도해주세요."로 덮는다.
          // 어디까지 저장됐는지가 이 화면에서 사장이 알아야 할 전부라, 그 문구가 살아남는 형태로 던진다.
          throw new ApiClientError(
            "PRIZE_PARTIAL_SAVE",
            successRank > 0
              ? `확률 설정은 저장됐지만 ${rankLabel(successRank + 1)}부터는 저장하지 못했습니다. 다시 저장해 주세요.`
              : "확률 설정은 저장됐지만 상품은 저장하지 못했습니다. 다시 저장해 주세요.",
          );
        }
      }
    },
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ["game-config", id] });
      qc.invalidateQueries({ queryKey: ["prizes", id] });
    },
  });

  const failed = config.isError || prizes.isError;
  if (failed)
    return (
      <AdminFrame title="상품 설정">
        <p className="error" role="alert">{errorMessage(config.error ?? prizes.error)}</p>
      </AdminFrame>
    );
  if (!draft)
    return (
      <AdminFrame title="상품 설정">
        <div className="stack" aria-live="polite" aria-busy="true">
          <span className="visually-hidden">설정을 불러오는 중</span>
          <div className="skeleton" style={{ height: 220 }} />
          <div className="skeleton" style={{ height: 180 }} />
        </div>
      </AdminFrame>
    );

  const ranks = Array.from({ length: draft.ladder }, (_, i) => i + 1);
  const total = tenthsOf(draft) / 10;
  const blocked = problem(draft);

  const setLadder = (ladder: number) =>
    setDraft({
      ...draft,
      ladder,
      outcomes: Object.fromEntries(
        YUT_ORDER.map((y) => [y, { ...draft.outcomes[y], prizeRank: PRESETS[ladder][y] }]),
      ) as Record<YutResult, OutcomeDraft>,
    });

  return (
    <AdminFrame title="상품 설정">
      <form
        className="stack"
        onSubmit={(e: FormEvent) => {
          e.preventDefault();
          if (!blocked) save.mutate();
        }}
      >
        <section className="panel stack">
          <h2>등급 수와 확률</h2>
          <p className="lead">
            확률은 도·개·걸·윷·모 결과별로 설정합니다. 각 결과에 상품 등급을 연결하세요.
          </p>

          <div className="preset-row" role="group" aria-label="등급 수">
            {LADDERS.map((count) => (
              <button
                key={count}
                type="button"
                className={draft.ladder === count ? "btn secondary is-on" : "btn secondary"}
                aria-pressed={draft.ladder === count}
                onClick={() => setLadder(count)}
              >
                {count}등급
              </button>
            ))}
          </div>

          <div>
            {YUT_ORDER.map((y) => (
              <div className="config-row" key={y}>
                <span className="config-yut">{YUT_LABEL[y]}</span>
                <label className="field config-weight">
                  <span>확률 (%)</span>
                  <input
                    type="text"
                    inputMode="decimal"
                    autoComplete="off"
                    value={draft.outcomes[y].percent}
                    onChange={(e) =>
                      setDraft({
                        ...draft,
                        outcomes: {
                          ...draft.outcomes,
                          [y]: { ...draft.outcomes[y], percent: onlyDecimal(e.target.value, 3, 1) },
                        },
                      })
                    }
                  />
                </label>
                <label className="field config-rank">
                  <span>상품 등급</span>
                  <select
                    value={draft.outcomes[y].prizeRank}
                    onChange={(e) =>
                      setDraft({
                        ...draft,
                        outcomes: {
                          ...draft.outcomes,
                          [y]: { ...draft.outcomes[y], prizeRank: Number(e.target.value) },
                        },
                      })
                    }
                  >
                    {ranks.map((rank) => (
                      <option key={rank} value={rank}>
                        {rankLabel(rank)}
                      </option>
                    ))}
                  </select>
                </label>
              </div>
            ))}
          </div>

          <div className={total === 100 ? "config-sum" : "config-sum is-off"} role="status">
            <span>확률 합계</span>
            <b>{Number.isFinite(total) ? total : 0}%</b>
          </div>

          <div className="list">
            {ranks.map((rank) => (
              <div className="list-item" key={rank}>
                <span className="lead">{rankLabel(rank)} 당첨 확률</span>
                <span className="name">{rankShare(draft, rank)}%</span>
              </div>
            ))}
          </div>
        </section>

        <section className="panel stack">
          <div className="row">
            <h2>등급별 상품</h2>
            <span className="pill" data-tone="wood">{draft.ladder}개</span>
          </div>

          {ranks.map((rank) => (
            <div className="prize-block" key={rank}>
              <div className="row">
                <h3>{rankLabel(rank)} 상품</h3>
                <span className="config-odds">당첨 {rankShare(draft, rank)}%</span>
              </div>
              <div className="field">
                <label htmlFor={`name-${rank}`}>상품명</label>
                <input
                  id={`name-${rank}`}
                  value={draft.prizes[rank].name}
                  maxLength={100}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      prizes: { ...draft.prizes, [rank]: { ...draft.prizes[rank], name: e.target.value } },
                    })
                  }
                />
              </div>
              <div className="field">
                <label htmlFor={`desc-${rank}`}>설명</label>
                <textarea
                  id={`desc-${rank}`}
                  value={draft.prizes[rank].description}
                  maxLength={500}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      prizes: { ...draft.prizes, [rank]: { ...draft.prizes[rank], description: e.target.value } },
                    })
                  }
                />
              </div>
              <div className="field">
                <div className="label-row">
                  <label htmlFor={`policy-${rank}`}>사용 시점</label>
                  <PolicyHelp />
                </div>
                <select
                  id={`policy-${rank}`}
                  value={draft.prizes[rank].redeemPolicy}
                  onChange={(e) =>
                    setDraft({
                      ...draft,
                      prizes: {
                        ...draft.prizes,
                        [rank]: { ...draft.prizes[rank], redeemPolicy: e.target.value as RedeemPolicy },
                      },
                    })
                  }
                >
                  {POLICY_HELP.map((p) => (
                    <option key={p.value} value={p.value}>
                      {p.label}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          ))}
        </section>

        {blocked && (
          <p className="error" role="status">
            {blocked}
          </p>
        )}
        {save.isError && (
          <p className="error" role="alert">
            {errorMessage(save.error)}
          </p>
        )}
        {save.isSuccess && !save.isPending && (
          <p className="success" role="status">
            저장했어요.
          </p>
        )}
        {/* 막혀 있어도 버튼은 살려 둔다. 이유는 바로 위에 늘 떠 있고, disabled 버튼은
            탭 순서에서 빠져 화면낭독기가 그 존재조차 못 찾는다. */}
        <button className="btn" disabled={save.isPending}>
          {save.isPending ? "저장 중" : "저장"}
        </button>
      </form>
    </AdminFrame>
  );
}
