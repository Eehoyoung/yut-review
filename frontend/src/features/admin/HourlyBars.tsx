"use client";

/**
 * 시간대별 참여 분포.
 *
 * 계열이 하나라 색도 하나다. 값이 클수록 진하게 칠하는 방식은 막대 길이가 이미 말하고 있는 것을
 * 색으로 한 번 더 말하는 것이라 쓰지 않는다. 대신 가장 붐빈 시간 하나만 강조한다. 사장이 이 화면에서
 * 실제로 가져가는 정보가 그 한 시간이기 때문이다.
 *
 * 숫자는 모든 막대에 붙이지 않는다. 최댓값에만 붙이고 나머지는 막대가 말한다. 값은 각 막대의
 * 접근성 라벨에 들어 있어 화면 낭독기로는 전부 읽을 수 있다.
 */
export function HourlyBars({ byHour }: { byHour: Record<string, number> }) {
  const hours = Object.keys(byHour).sort();
  const counts = hours.map((h) => byHour[h] ?? 0);
  const max = Math.max(...counts, 0);
  const total = counts.reduce((sum, n) => sum + n, 0);
  const peakIndex = max > 0 ? counts.indexOf(max) : -1;

  if (total === 0)
    return <p className="hint">이 기간에는 참여가 없어 시간대를 볼 수 없습니다.</p>;

  return (
    <figure className="chart">
      <figcaption className="chart-lede">
        가장 붐빈 시간 <b>{hours[peakIndex]}시</b> · {max}건
      </figcaption>
      <div className="bars" role="img" aria-label={`시간대별 참여 분포. 가장 붐빈 시간은 ${hours[peakIndex]}시 ${max}건.`}>
        {hours.map((hour, i) => (
          <div
            key={hour}
            className={i === peakIndex ? "bar is-peak" : "bar"}
            style={{ height: `${max ? Math.max(counts[i] / max, counts[i] > 0 ? 0.06 : 0) * 100 : 0}%` }}
            title={`${hour}시 ${counts[i]}건`}
          >
            <span className="visually-hidden">
              {hour}시 {counts[i]}건
            </span>
          </div>
        ))}
      </div>
      {/* 눈금은 읽는 데 필요한 만큼만. 24개를 다 적으면 막대보다 글자가 많아진다. */}
      <div className="bars-axis" aria-hidden="true">
        <span>0시</span>
        <span>6시</span>
        <span>12시</span>
        <span>18시</span>
        <span>23시</span>
      </div>
    </figure>
  );
}

/**
 * 요일별 참여. 일곱 개뿐이라 막대와 숫자를 나란히 둔다. 이 정도 개수에서는 그래프보다 목록이
 * 빠르게 읽힌다.
 */
export function WeekdayBars({ byWeekday }: { byWeekday: Record<string, number> }) {
  const days = Object.keys(byWeekday);
  const counts = days.map((d) => byWeekday[d] ?? 0);
  const max = Math.max(...counts, 0);
  const total = counts.reduce((sum, n) => sum + n, 0);
  const peak = max > 0 ? days[counts.indexOf(max)] : null;

  if (total === 0) return <p className="hint">이 기간에는 참여가 없어 요일을 볼 수 없습니다.</p>;

  return (
    <div className="weekday">
      {days.map((day, i) => (
        <div className="weekday-row" key={day}>
          <span className="weekday-name">{day}</span>
          <span className="weekday-track">
            <span
              className={day === peak ? "weekday-fill is-peak" : "weekday-fill"}
              style={{ width: `${max ? (counts[i] / max) * 100 : 0}%` }}
            />
          </span>
          <span className="weekday-count">{counts[i]}</span>
        </div>
      ))}
    </div>
  );
}
