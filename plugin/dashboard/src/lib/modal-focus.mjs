const FOCUSABLE_SELECTOR = [
  "a[href]",
  "button:not([disabled])",
  "input:not([disabled])",
  "select:not([disabled])",
  "textarea:not([disabled])",
  "[contenteditable=\"true\"]",
  "[tabindex]:not([tabindex=\"-1\"])",
].join(",");

function isAvailable(element) {
  return !element.hidden && element.getAttribute("aria-hidden") !== "true";
}

export function focusableElements(dialog) {
  return Array.from(dialog.querySelectorAll(FOCUSABLE_SELECTOR)).filter(isAvailable);
}

export function containTabKey(event, dialog, documentRef) {
  if (event.key !== "Tab") return;

  const focusable = focusableElements(dialog);
  if (focusable.length === 0) {
    event.preventDefault();
    dialog.focus();
    return;
  }

  const activeElement = documentRef.activeElement;
  const activeIndex = focusable.indexOf(activeElement);
  const movingBeforeStart = event.shiftKey && activeIndex <= 0;
  const movingPastEnd = !event.shiftKey && (
    activeIndex === -1 || activeIndex === focusable.length - 1
  );

  if (movingBeforeStart || movingPastEnd) {
    event.preventDefault();
    (movingBeforeStart ? focusable[focusable.length - 1] : focusable[0]).focus();
  }
}

/**
 * Make every sibling outside the modal's ancestor branch unavailable to
 * pointer, keyboard, and assistive-technology navigation.
 */
export function isolateModalBackground(dialog, documentRef) {
  const changed = [];
  let branch = dialog;

  while (branch && branch.parentElement && branch.parentElement !== documentRef.documentElement) {
    const parent = branch.parentElement;
    for (const sibling of parent.children) {
      if (sibling === branch) continue;
      changed.push({
        element: sibling,
        inert: sibling.inert,
        inertAttribute: sibling.getAttribute("inert"),
        ariaHidden: sibling.getAttribute("aria-hidden"),
      });
      sibling.inert = true;
      sibling.setAttribute("inert", "");
      sibling.setAttribute("aria-hidden", "true");
    }
    branch = parent;
  }

  return () => {
    for (const previous of changed.reverse()) {
      const { element } = previous;
      if (previous.inertAttribute === null) element.removeAttribute("inert");
      else element.setAttribute("inert", previous.inertAttribute);
      element.inert = previous.inert;
      if (previous.ariaHidden === null) element.removeAttribute("aria-hidden");
      else element.setAttribute("aria-hidden", previous.ariaHidden);
    }
  };
}

/** Activate a modal and return the complete focus/background cleanup. */
export function activateModal({ dialog, documentRef, onClose }) {
  const opener = documentRef.activeElement;
  const restoreBackground = isolateModalBackground(dialog, documentRef);
  const initialFocus = dialog.querySelector("[data-modal-initial-focus]")
    || focusableElements(dialog)[0]
    || dialog;

  const onKeyDown = (event) => {
    if (event.key === "Escape") {
      event.preventDefault();
      event.stopPropagation();
      onClose();
      return;
    }
    containTabKey(event, dialog, documentRef);
  };

  documentRef.addEventListener("keydown", onKeyDown, true);
  initialFocus.focus();

  return () => {
    documentRef.removeEventListener("keydown", onKeyDown, true);
    restoreBackground();
    if (opener && opener.isConnected !== false && typeof opener.focus === "function") {
      opener.focus();
    }
  };
}
