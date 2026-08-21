const ZONED_DATE_TIME = /(?:Z|[+-]\d{2}:?\d{2})$/i;

const BEIJING_DATE_TIME = new Intl.DateTimeFormat("zh-CN", {
  timeZone: "Asia/Shanghai",
  year: "numeric",
  month: "2-digit",
  day: "2-digit",
  hour: "2-digit",
  minute: "2-digit",
  second: "2-digit",
  hour12: false
});

/**
 * Backend LocalDateTime values are persisted by UTC JVMs but serialized without
 * an offset. Interpret an offset-less value as UTC, then render Beijing time.
 */
export function formatBackendDateTime(value?: string | null): string {
  if (!value) return "—";
  const normalized = ZONED_DATE_TIME.test(value) ? value : `${value}Z`;
  const date = new Date(normalized);
  if (Number.isNaN(date.getTime())) return value;
  return BEIJING_DATE_TIME.format(date).replace(/\//g, "-");
}
