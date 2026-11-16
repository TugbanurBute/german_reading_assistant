import type { TargetLanguage, TranslationType } from "../api/types.ts";
import { SegmentedControl } from "./SegmentedControl.tsx";

export type Theme = "light" | "dark";

interface Props {
  theme: Theme;
  onThemeChange: (theme: Theme) => void;
  language: TargetLanguage;
  onLanguageChange: (language: TargetLanguage) => void;
  reading: { mode: TranslationType; onModeChange: (mode: TranslationType) => void } | null;
  onHome?: () => void;
}

const LANGUAGES = [
  { value: "TR", label: "TR" },
  { value: "EN", label: "EN" },
] as const;

const MODES = [
  { value: "WORD", label: "Word" },
  { value: "SENTENCE", label: "Sentence" },
] as const;

const iconButton = "rounded-lg p-1 text-muted hover:bg-control hover:text-ink sm:p-1.5";

export function Header({ theme, onThemeChange, language, onLanguageChange, reading, onHome }: Props) {
  const brand = (
    <>
      <BookIcon />
      {/* Below 400px the reading toolbar needs the space: the name is hidden visually, the icon stays. */}
      <span className={reading ? "max-[399px]:sr-only" : ""}>Wortweise</span>
    </>
  );
  const brandClass = "mr-auto flex items-center gap-2 font-serif text-[17px] font-medium";
  return (
    <header className="flex items-center gap-1.5 border-b border-line px-3 py-2.5 sm:gap-3 sm:px-6 sm:py-3">
      {onHome ? (
        <button type="button" onClick={onHome} title="Go to your library" className={`${brandClass} -ml-1.5 rounded-lg px-1.5 py-0.5 hover:bg-control`}>
          {brand}
        </button>
      ) : (
        <span className={brandClass}>{brand}</span>
      )}
      {reading && (
        <div>
          <SegmentedControl label="Translate" value={reading.mode} options={MODES} onChange={reading.onModeChange} />
        </div>
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

/** An open book: the brand mark. */
function BookIcon() {
  return (
    <svg {...iconProps} className="text-accent">
      <path d="M12 6.5C10.3 5.2 8 4.5 4 4.5v13c4 0 6.3.7 8 2 1.7-1.3 4-2 8-2v-13c-4 0-6.3.7-8 2Zm0 0v13" />
    </svg>
  );
}
