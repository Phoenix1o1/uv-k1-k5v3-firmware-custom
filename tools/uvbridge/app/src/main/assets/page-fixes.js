/* UV Studio on phone-width screens.
 * Its top bar actions (counter, buttons, language) and the Live Viewer screen
 * have fixed widths wider than a phone (424 and 524 CSS px), so the page
 * overflows and gets clipped. Below 600 px, let the bar wrap and the screen
 * scale down. Styling only; UV Studio's behaviour is unchanged. */
(function () {
  "use strict";
  const css = `
@media (max-width: 600px) {
  .topbar-actions { flex-wrap: wrap; row-gap: 8px; max-width: 100%; }
  .topbar-actions select { max-width: 100%; }
  .viewer-layout, .display-container { max-width: 100%; min-width: 0; }
  canvas#display { max-width: 100%; height: auto; }
}`;
  document.addEventListener("DOMContentLoaded", () => {
    const style = document.createElement("style");
    style.id = "uvbridge-page-fixes";
    style.textContent = css;
    document.head.appendChild(style);
  });
})();
