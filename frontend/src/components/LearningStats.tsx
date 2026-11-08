import { useEffect, useState } from "react";
import { type VocabularyStats, vocabularyStats } from "../api/analytics.ts";

type State = { status: "loading" } | { status: "error" } | { status: "ready"; stats: VocabularyStats };

const weekday = new Intl.DateTimeFormat("en", { weekday: "short", timeZone: "UTC" });
const fullDate = new Intl.DateTimeFormat("en", { weekday: "long", day: "numeric", month: "long", timeZone: "UTC" });
/** "2026-11-09" as a UTC date, so the label is that calendar day wherever the browser is. */
const dayOf = (date: string) => new Date(`${date}T00:00:00Z`);
const words = (count: number) => `${count} ${count === 1 ? "word" : "words"}`;

/** Saved words today and over the last 7 days, with a small bar chart (plain elements, no chart library). */
export function LearningStats() {
  const [state, setState] = useState<State>({ status: "loading" });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let current = true;
    setState({ status: "loading" });
    vocabularyStats().then(
      (stats) => current && setState({ status: "ready", stats }),
      () => current && setState({ status: "error" }),
    );
    return () => {
      current = false;
    };
  }, [attempt]);

  return (
    <section aria-labelledby="learning-stats-heading" className="mt-5 rounded-lg border border-line px-4 py-3.5">
      <h2 id="learning-stats-heading" className="text-[13px] font-medium text-muted">
        Your week
      </h2>

      {state.status === "loading" && <p className="mt-2 text-[13px] text-muted">Loading your statistics…</p>}

      {state.status === "error" && (
        <p className="mt-2 text-[13px] text-muted">
          Couldn't load your statistics.{" "}
          <button type="button" onClick={() => setAttempt((n) => n + 1)} className="text-accent underline-offset-2 hover:underline">
            Try again
          </button>
        </p>
      )}

      {state.status === "ready" && state.stats.lastSevenDays === 0 && (
        <p className="mt-2 text-[13px] text-muted">No words saved in the last 7 days yet. Save words while reading to see your progress.</p>
      )}

      {state.status === "ready" && state.stats.lastSevenDays > 0 && <Chart stats={state.stats} />}
    </section>
  );
}

function Chart({ stats }: { stats: VocabularyStats }) {
  const max = Math.max(...stats.days.map((day) => day.count));
  return (
    <div className="mt-2 flex flex-wrap items-end gap-x-6 gap-y-3">
      <p>
        <span className="font-serif text-3xl font-medium">{stats.today}</span>
        <span className="ml-1.5 text-[13px] text-muted">{stats.today === 1 ? "word" : "words"} today</span>
        <span className="block text-[13px] text-muted">{words(stats.lastSevenDays)} in the last 7 days</span>
      </p>
      <ol aria-label="Words saved per day" className="ml-auto flex h-20 items-end gap-1.5">
        {stats.days.map((day, index) => {
          const isToday = index === stats.days.length - 1;
          return (
            <li key={day.date} className="flex w-7 flex-col items-center gap-1" title={`${fullDate.format(dayOf(day.date))}: ${words(day.count)}`}>
              <span className="sr-only">{`${fullDate.format(dayOf(day.date))}: ${words(day.count)}`}</span>
              <span
                aria-hidden="true"
                className={`w-full rounded-t ${isToday ? "bg-accent" : "bg-accent/45"}`}
                style={{ height: day.count === 0 ? 2 : `${Math.max(8, (day.count / max) * 56)}px` }}
              />
              <span aria-hidden="true" className={`text-[10px] ${isToday ? "font-medium text-ink" : "text-muted"}`}>
                {weekday.format(dayOf(day.date))}
              </span>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
