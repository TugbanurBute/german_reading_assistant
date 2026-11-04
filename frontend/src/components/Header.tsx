import type { TargetLanguage, TranslationType } from "../api/types.ts";
import { SegmentedControl } from "./SegmentedControl.tsx";

export type Theme = "light" | "dark";

interface Props {
  theme: Theme;
  onThemeChange: (theme: Theme) => void;
  language: TargetLanguage;
  onLanguageChange: (language: TargetLanguage) => void;
  /** Mode switch and edit button only exist while reading. */
  reading: { mode: TranslationType; onModeChange: (mode: TranslationType) => void; onEdit: () => void } | null;
}

const LANGUAGES = [
  { value: "TR", label: "TR" },
  { value: "EN", label: "EN" },
] as const;

const MODES = [
  { value: "WORD", label: "Word" },
  { value: "SENTENCE", label: "Sentence" },
] as const;

const iconButton = "rounded-lg p-1.5 text-muted hover:bg-control hover:text-ink";

export function Header({ theme, onThemeChange, language, onLanguageChange, reading }: Props) {
  return (
    <header className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-line px-4 py-3 sm:px-6">
      <span className="mr-auto font-serif text-[17px] font-medium">Lesehilfe</span>
      {reading && (
        <SegmentedControl label="Translate" value={reading.mode} options={MODES} onChange={reading.onModeChange} />
      )}
      <div data-keep-popup>
        <SegmentedControl label="Translate into" value={language} options={LANGUAGES} onChange={onLanguageChange} />
      </div>
      <button
        type="button"
        className={iconButton}
        aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
        onClick={() => onThemeChange(theme === "dark" ? "light" : "dark")}
      >
        {theme === "dark" ? <SunIcon /> : <MoonIcon />}
      </button>
      {reading && (
        <button type="button" className={iconButton} aria-label="Edit text" onClick={reading.onEdit}>
          <PencilIcon />
        </button>
      )}
    </header>
  );
}

const iconProps = {
  width: 18,
  height: 18,
  viewBox: "0 0 24 24",
  fill: "none",
  stroke: "currentColor",
  strokeWidth: 1.75,
  strokeLinecap: "round",
  strokeLinejoin: "round",
  "aria-hidden": true,
} as const;

function MoonIcon() {
  return (
    <svg {...iconProps}>
      <path d="M20 14.5A8 8 0 0 1 9.5 4a8 8 0 1 0 10.5 10.5Z" />
    </svg>
  );
}

function SunIcon() {
  return (
    <svg {...iconProps}>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  );
}

function PencilIcon() {
  return (
    <svg {...iconProps}>
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L8 18l-4 1 1-4Z" />
    </svg>
  );
}
