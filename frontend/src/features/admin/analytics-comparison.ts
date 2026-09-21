import type { DetailedAnalytics } from "@/types/api";

type Comparison = NonNullable<DetailedAnalytics["comparedToPrevious"]>;

function signed(value: number, unit: string) {
  const rounded = Number.isInteger(value) ? value : Number(value.toFixed(1));
  return `${rounded > 0 ? "+" : ""}${rounded}${unit}`;
}

export function comparisonCopy(comparison: Comparison) {
  if (!comparison.previous) {
    return { note: comparison.note ?? "비교할 직전 기간 데이터가 없습니다." };
  }

  const playsChange = comparison.playsChange ?? comparison.current.plays - comparison.previous.plays;
  const percent = comparison.playsChangePercent;
  return {
    plays: `${signed(playsChange, "건")}${
      percent === null ? " · 직전 기간 참여 0건" : percent === undefined ? "" : ` · ${signed(percent, "%")}`
    }`,
    redemptionRate: signed(
      comparison.current.redemptionRatePercent - comparison.previous.redemptionRatePercent,
      "%p",
    ),
  };
}
