import test from "node:test";
import assert from "node:assert/strict";
import { copyText } from "../src/lib/copy-text.mjs";

function documentFixture(copied) {
  const events = [];
  const dialog = {appendChild:field=>events.push(["append",field.value])};
  const activeElement = {closest:()=>dialog, focus:()=>events.push(["restore-focus"])};
  const doc = {activeElement, body:{appendChild:()=>assert.fail("Dialog must own the temporary field")},
    createElement:()=>({style:{},focus:()=>events.push(["focus"]),select:()=>events.push(["select"]),remove:()=>events.push(["remove"])}),
    execCommand:command=>{events.push([command]);return copied;},
  };
  return {doc,events};
}

test("secure-context copying uses Clipboard API without a fallback field", async () => {
  const copied=[];
  await copyText("test-invite", {clipboard:{writeText:async text=>copied.push(text)}}, {});
  assert.deepEqual(copied,["test-invite"]);
});

test("HTTP dashboard copying uses a field inside the dialog and restores focus", async () => {
  const {doc,events}=documentFixture(true);
  await copyText("test-invite",{},doc);
  assert.deepEqual(events,[["append","test-invite"],["focus"],["select"],["copy"],["remove"],["restore-focus"]]);
});

test("denied Clipboard API falls back, and failed copying still cleans up", async () => {
  const denied={clipboard:{writeText:async()=>{throw new Error("Denied");}}};
  const success=documentFixture(true);
  await copyText("test-invite",denied,success.doc);
  const failure=documentFixture(false);
  await assert.rejects(copyText("test-invite",denied,failure.doc), /Clipboard unavailable/);
  assert.deepEqual(failure.events.slice(-2),[["remove"],["restore-focus"]]);
});
