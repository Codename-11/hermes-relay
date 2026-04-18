const SDK = window.__HERMES_PLUGIN_SDK__;
const { React } = SDK;
const C = SDK.components || {};

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

export const Switch = C.Switch || (({ checked, onCheckedChange, id }) => (
  <input
    id={id}
    type="checkbox"
    checked={!!checked}
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
