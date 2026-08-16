import test from "node:test";
import assert from "node:assert/strict";

import {
  activateModal,
  containTabKey,
  isolateModalBackground,
} from "../src/lib/modal-focus.mjs";

class FakeElement {
  constructor(name, documentRef) {
    this.name = name;
    this.ownerDocument = documentRef;
    this.parentElement = null;
    this.children = [];
    this.attributes = new Map();
    this.inert = false;
    this.hidden = false;
    this.isConnected = true;
    this.focusables = [];
    this.initialFocus = null;
  }

  append(...children) {
    for (const child of children) {
      child.parentElement = this;
      this.children.push(child);
    }
  }

  getAttribute(name) {
    return this.attributes.has(name) ? this.attributes.get(name) : null;
  }

  setAttribute(name, value) {
    this.attributes.set(name, String(value));
  }

  removeAttribute(name) {
    this.attributes.delete(name);
  }

  querySelector(selector) {
    return selector === "[data-modal-initial-focus]" ? this.initialFocus : null;
  }

  querySelectorAll() {
    return this.focusables;
  }

  focus() {
    this.ownerDocument.activeElement = this;
  }
}

class FakeDocument {
  constructor() {
    this.activeElement = null;
    this.listeners = new Map();
    this.documentElement = new FakeElement("html", this);
  }

  addEventListener(type, listener) {
    this.listeners.set(type, listener);
  }

  removeEventListener(type, listener) {
    if (this.listeners.get(type) === listener) this.listeners.delete(type);
  }
}

function keyEvent(key, shiftKey = false) {
  return {
    key,
    shiftKey,
    prevented: false,
    propagationStopped: false,
    preventDefault() { this.prevented = true; },
    stopPropagation() { this.propagationStopped = true; },
  };
}

test("Tab and Shift+Tab remain inside the dialog", () => {
  const documentRef = new FakeDocument();
  const dialog = new FakeElement("dialog", documentRef);
  const first = new FakeElement("first", documentRef);
  const last = new FakeElement("last", documentRef);
  dialog.focusables = [first, last];

  documentRef.activeElement = last;
  const forward = keyEvent("Tab");
  containTabKey(forward, dialog, documentRef);
  assert.equal(forward.prevented, true);
  assert.equal(documentRef.activeElement, first);

  const backward = keyEvent("Tab", true);
  containTabKey(backward, dialog, documentRef);
  assert.equal(backward.prevented, true);
  assert.equal(documentRef.activeElement, last);
});

test("background isolation is restored exactly", () => {
  const documentRef = new FakeDocument();
  const body = new FakeElement("body", documentRef);
  const shell = new FakeElement("shell", documentRef);
  const sidebar = new FakeElement("sidebar", documentRef);
  const plugin = new FakeElement("plugin", documentRef);
  const content = new FakeElement("content", documentRef);
  const dialog = new FakeElement("dialog", documentRef);
  documentRef.documentElement.append(body);
  body.append(shell, sidebar);
  shell.append(plugin);
  plugin.append(content, dialog);
  sidebar.setAttribute("aria-hidden", "false");

  const restore = isolateModalBackground(dialog, documentRef);
  assert.equal(content.inert, true);
  assert.equal(sidebar.inert, true);
  assert.equal(sidebar.getAttribute("aria-hidden"), "true");

  restore();
  assert.equal(content.inert, false);
  assert.equal(content.getAttribute("aria-hidden"), null);
  assert.equal(sidebar.inert, false);
  assert.equal(sidebar.getAttribute("aria-hidden"), "false");
});

test("activation focuses the close action, Escape closes, and cleanup restores the opener", () => {
  const documentRef = new FakeDocument();
  const body = new FakeElement("body", documentRef);
  const opener = new FakeElement("opener", documentRef);
  const dialog = new FakeElement("dialog", documentRef);
  const close = new FakeElement("close", documentRef);
  documentRef.documentElement.append(body);
  body.append(opener, dialog);
  dialog.focusables = [close];
  dialog.initialFocus = close;
  documentRef.activeElement = opener;
  let closeCalls = 0;

  const cleanup = activateModal({
    dialog,
    documentRef,
    onClose: () => { closeCalls += 1; },
  });
  assert.equal(documentRef.activeElement, close);
  assert.equal(opener.inert, true);

  const escape = keyEvent("Escape");
  documentRef.listeners.get("keydown")(escape);
  assert.equal(escape.prevented, true);
  assert.equal(escape.propagationStopped, true);
  assert.equal(closeCalls, 1);

  cleanup();
  assert.equal(documentRef.activeElement, opener);
  assert.equal(opener.inert, false);
  assert.equal(documentRef.listeners.has("keydown"), false);
});
