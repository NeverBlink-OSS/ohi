// Plausible Analytics for linkml.ohi.dev, bundled into dist/analytics.js, which the landing page
// and the playground load. Page views, outbound link clicks and file downloads, under the same
// account as ohi.dev. Plausible skips visits on localhost.
import { init } from "@plausible-analytics/tracker";
import { reportedUrl } from "./lib/analytics.js";

init({
  domain: "linkml.ohi.dev",
  endpoint: "https://ohi.dev/ev",
  outboundLinks: true,
  fileDownloads: true,
  transformRequest: (payload) => ({ ...payload, u: reportedUrl(payload.u) }),
});
