import "@testing-library/jest-dom/vitest";

// jsdom does not implement pointer capture or programmatic scrolling; Radix Select
// relies on both when a pointer interaction opens the listbox in tests.
const noop = () => undefined;
if (typeof Element !== "undefined" && !Element.prototype.hasPointerCapture) {
  Element.prototype.hasPointerCapture = () => false;
  Element.prototype.setPointerCapture = noop;
  Element.prototype.releasePointerCapture = noop;
}
if (typeof Element !== "undefined" && !Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = noop;
}
