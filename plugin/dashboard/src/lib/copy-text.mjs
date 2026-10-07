/** Copy on an explicit user action, including HTTP dashboards without Clipboard API. */
export async function copyText(text, navigatorRef = window.navigator, documentRef = window.document) {
  if (navigatorRef.clipboard && typeof navigatorRef.clipboard.writeText === "function") {
    try {
      await navigatorRef.clipboard.writeText(text);
      return;
    } catch (_error) { /* Try the user-gesture clipboard path before manual copying. */ }
  }
  const focused = documentRef.activeElement;
  const field = documentRef.createElement("textarea");
  field.value = text;
  field.readOnly = true;
  field.tabIndex = -1;
  field.style.position = "fixed";
  field.style.opacity = "0";
  field.style.pointerEvents = "none";
  (focused?.closest?.('[role="dialog"]') || documentRef.body).appendChild(field);
  try {
    field.focus({ preventScroll: true });
    field.select();
    if (!documentRef.execCommand("copy")) throw new Error("Clipboard unavailable");
  } finally {
    field.remove();
    focused?.focus?.({ preventScroll: true });
  }
}
