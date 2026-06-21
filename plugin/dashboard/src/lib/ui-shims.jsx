const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const C = SDK.components || {};

// ── Button / Badge variant adapters ─────────────────────────────────────────
//
// The host's Nous DS components do NOT use the shadcn ``variant="default|
// outline|ghost|secondary|destructive"`` / ``size`` string API this plugin was
// written against. The real ``Button`` takes boolean flags
// (``outlined`` / ``ghost`` / ``invert`` / ``destructive``) plus a ``size``
// of ``default|sm|icon|xs``; the real ``Badge`` takes ``tone="default|outline|
// secondary|destructive|success|warning"``. A bare ``variant`` prop is
// silently dropped, so every call site collapsed to the solid default —
// which on the DS theme is a light ``bg-midground`` fill (the "white boxes").
//
// These adapters wrap the real components and translate our props into the DS
// contract, so the existing call sites keep working and render with authentic
// DS styling (shadows, hover, theme tokens) instead of unstyled fallbacks.

const _NousButton = C.Button;
const _NousBadge = C.Badge;

function buttonFlagsForVariant(variant) {
  switch (variant) {
    case "outline":
      return { outlined: true };
    case "ghost":
      return { ghost: true };
    case "secondary":
      return { invert: true };
    case "destructive":
      return { destructive: true };
    case "default":
    case undefined:
    case null:
    default:
      return {};
  }
}

export const Button = _NousButton
  ? (({ variant, size, className = "", children, ...rest }) => {
      const flags = buttonFlagsForVariant(variant);
      const dsSize = size === "icon" || size === "xs" || size === "sm" ? size : "default";
      return (
        <_NousButton size={dsSize} className={className} {...flags} {...rest}>
          {children}
        </_NousButton>
      );
    })
  : (({ variant, size, className = "", children, ...rest }) => {
      // Theme-matching fallback for hosts that don't expose a Button. Uses the
      // plugin's own scoped utility classes (see styles.css) so it tracks the
      // dashboard tokens rather than a raw white default.
      const base =
        "hr-btn inline-flex items-center justify-center gap-1 rounded-md px-3 py-1.5 text-xs font-medium transition-colors";
      const tone =
        variant === "ghost"
          ? "hr-btn-ghost"
          : variant === "outline"
          ? "hr-btn-outline"
          : variant === "destructive"
          ? "hr-btn-destructive"
          : "hr-btn-solid";
      return (
        <button type="button" className={`${base} ${tone} ${className}`} {...rest}>
          {children}
        </button>
      );
    });

const BADGE_TONE = {
  default: "secondary",
  secondary: "secondary",
  outline: "outline",
  destructive: "destructive",
  success: "success",
  warning: "warning",
};

export const Badge = _NousBadge
  ? (({ variant, className = "", children, ...rest }) => (
      <_NousBadge tone={BADGE_TONE[variant] || "secondary"} className={className} {...rest}>
        {children}
      </_NousBadge>
    ))
  : (({ variant, className = "", children, ...rest }) => {
      const tone =
        variant === "destructive"
          ? "hr-badge-destructive"
          : variant === "outline"
          ? "hr-badge-outline"
          : "hr-badge-secondary";
      return (
        <span className={`hr-badge ${tone} ${className}`} {...rest}>
          {children}
        </span>
      );
    });

export const Alert = C.Alert || (({ children, variant, className = "" }) => (
  <div
    className={`rounded-md border p-3 ${
      variant === "destructive"
        ? "border-destructive/50 text-destructive bg-destructive/10"
        : "border-border bg-muted/30"
    } ${className}`}
  >
    {children}
  </div>
));

export const AlertTitle = C.AlertTitle || (({ children, className = "" }) => (
  <div className={`font-semibold mb-1 ${className}`}>{children}</div>
));

export const AlertDescription = C.AlertDescription || (({ children, className = "" }) => (
  <div className={`text-sm ${className}`}>{children}</div>
));

export const CardDescription = C.CardDescription || (({ children, className = "" }) => (
  <p className={`text-sm text-muted-foreground ${className}`}>{children}</p>
));

export const Switch = C.Switch || (({ checked, onCheckedChange, id, disabled }) => (
  <input
    id={id}
    type="checkbox"
    checked={!!checked}
    disabled={!!disabled}
    onChange={(e) => onCheckedChange && onCheckedChange(e.target.checked)}
    className="h-4 w-4"
  />
));

export const Table = C.Table || (({ children, className = "" }) => (
  <div className="w-full overflow-auto">
    <table className={`w-full text-sm caption-bottom ${className}`}>{children}</table>
  </div>
));

export const TableHeader = C.TableHeader || (({ children }) => (
  <thead className="border-b bg-muted/50">{children}</thead>
));

export const TableBody = C.TableBody || (({ children }) => (
  <tbody className="[&_tr:last-child]:border-0">{children}</tbody>
));

export const TableRow = C.TableRow || (({ children, className = "", ...rest }) => (
  <tr className={`border-b hover:bg-muted/30 transition-colors ${className}`} {...rest}>
    {children}
  </tr>
));

export const TableHead = C.TableHead || (({ children, className = "" }) => (
  <th className={`h-10 px-2 text-left align-middle font-medium text-muted-foreground ${className}`}>
    {children}
  </th>
));

export const TableCell = C.TableCell || (({ children, className = "", ...rest }) => (
  <td className={`p-2 align-middle ${className}`} {...rest}>
    {children}
  </td>
));
