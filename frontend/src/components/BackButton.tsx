interface Props {
  label: string;
  onClick: () => void;
}

export function BackButton({ label, onClick }: Props) {
  return (
    <button
      type="button"
      onClick={onClick}
      className="-ml-2 mb-3 inline-flex items-center gap-1 rounded-lg px-2 py-1 text-[13px] text-muted hover:bg-control hover:text-ink"
    >
      <span aria-hidden="true">←</span> {label}
    </button>
  );
}
