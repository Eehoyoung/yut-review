import { describe, expect, it } from "vitest";
import type { AnalyticsPeriod } from "@/types/api";
import { comparisonCopy } from "./analytics-comparison";

const period = (plays: number, redemptionRatePercent = 0): AnalyticsPeriod => ({
  from: "2026-08-01",
  to: "2026-08-30",
  days: 30,
  plays,
  couponsIssued: plays,
  couponsRedeemed: 0,
  redemptionRatePercent,
  couponsByStatus: {},
});

describe("comparisonCopy", () => {
  it("does not invent a growth percentage when the previous period had no participants", () => {
    expect(
      comparisonCopy({ current: period(4, 50), previous: period(0), playsChange: 4, playsChangePercent: null }),
    ).toEqual({ plays: "+4건 · 직전 기간 참여 0건", redemptionRate: "+50%p" });
  });

  it("labels redemption-rate change as percentage points", () => {
    expect(
      comparisonCopy({ current: period(12, 45.5), previous: period(10, 40), playsChange: 2, playsChangePercent: 20 }),
    ).toEqual({ plays: "+2건 · +20%", redemptionRate: "+5.5%p" });
  });

  it("uses the server note when there is no comparable period", () => {
    expect(comparisonCopy({ current: period(0), previous: null, note: "아직 비교 기간이 없습니다." })).toEqual({
      note: "아직 비교 기간이 없습니다.",
    });
  });
});
