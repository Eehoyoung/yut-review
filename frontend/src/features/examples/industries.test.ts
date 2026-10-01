import { describe, expect, it } from "vitest";
import { EXAMPLE_GROUPS, INDUSTRIES, POPULAR_SLUGS, PRESET_ORDER, searchIndustries } from "./industries";

const textOf = (value: unknown): string[] =>
  typeof value === "string" ? [value] : value && typeof value === "object" ? Object.values(value).flatMap(textOf) : [];

describe("업종별 활용 예시 데이터", () => {
  it("slug가 겹치지 않고 주소로 쓸 수 있다", () => {
    const slugs = INDUSTRIES.map((i) => i.slug);
    expect(new Set(slugs).size).toBe(slugs.length);
    for (const slug of slugs) expect(slug).toMatch(/^[a-z0-9-]+$/);
  });

  it("인기 업종과 대분류가 실제 업종을 가리킨다", () => {
    for (const slug of POPULAR_SLUGS) expect(INDUSTRIES.some((i) => i.slug === slug)).toBe(true);
    for (const group of EXAMPLE_GROUPS) expect(INDUSTRIES.some((i) => i.group === group.slug)).toBe(true);
  });

  it("업종마다 경품 후보 3~5개, 프리셋 3종 × 3등급이 있다", () => {
    for (const i of INDUSTRIES) {
      expect(i.rewards.length, i.slug).toBeGreaterThanOrEqual(3);
      expect(i.rewards.length, i.slug).toBeLessThanOrEqual(5);
      expect(i.useCases.length, i.slug).toBeGreaterThanOrEqual(1);
      expect(i.tips.length, i.slug).toBeGreaterThanOrEqual(1);
      for (const key of PRESET_ORDER)
        for (const rank of [1, 2, 3] as const) {
          const prize = i.presets[key][rank];
          // 상품 설정 화면의 입력 한도와 같다. 넘으면 불러온 뒤 저장이 막힌다.
          expect(prize.name.trim().length, `${i.slug} ${key} ${rank}`).toBeGreaterThan(0);
          expect(prize.name.length).toBeLessThanOrEqual(100);
          expect(prize.description.length).toBeLessThanOrEqual(500);
        }
    }
  });

  it("리뷰·별점을 참여나 보상 조건으로 쓰지 않는다", () => {
    for (const i of INDUSTRIES)
      for (const text of textOf(i)) expect(text, i.slug).not.toMatch(/리뷰|별점|후기|평점/);
  });

  it("주류를 경품으로 두지 않는다", () => {
    for (const i of INDUSTRIES) {
      const prizes = [...i.rewards.map((r) => r.name), ...PRESET_ORDER.flatMap((k) => textOf(i.presets[k]))];
      for (const text of prizes) expect(text, i.slug).not.toMatch(/소주|맥주|와인|막걸리|하이볼|주류|사케|생맥/);
    }
  });

  it("검색은 이름과 별칭을 공백 없이 찾고 8개까지 돌려준다", () => {
    expect(searchIndustries("고깃집").map((i) => i.slug)).toContain("samgyeopsal");
    expect(searchIndustries("셀프 세차").map((i) => i.slug)).toContain("car-wash");
    expect(searchIndustries("카페")[0].slug).toBe("cafe");
    expect(searchIndustries("").length).toBe(0);
    expect(searchIndustries("ㅇ").length).toBeLessThanOrEqual(8);
  });
});
