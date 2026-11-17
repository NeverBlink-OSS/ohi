// Plausible Analytics for linkml.ohi.dev, bundled into dist/analytics.js, which the landing page
// and the playground load. Page views, outbound link clicks and file downloads, under the same
// account as ohi.dev. Plausible skips visits on localhost.
import { init } from "@plausible-analytics/tracker";
import { reportedUrl } from "./lib/analytics.js";

// The playground's Docs view shows pages generated from the user's schema, with the user's links.
// Clicks on those are not reported. This runs in the capture phase, before the tracker's own
// listener sees the same click.
let inUserContent = false;
for (const type of ["click", "auxclick"]) {
  document.addEventListener(
    type,
    (e) => {
      inUserContent = e.target instanceof Element && e.target.closest("#pageView") !== null;
    },
    true,
  );
}

init({
  domain: "linkml.ohi.dev",
  endpoint: "https://ohi.dev/ev",
  outboundLinks: true,
  fileDownloads: true,
  transformRequest: (payload) =>
    payload.n !== "pageview" && inUserContent ? null : { ...payload, u: reportedUrl(payload.u) },
});
