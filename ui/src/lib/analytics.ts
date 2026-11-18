/** Query parameters Plausible reads for its sources and campaigns. */
const CAMPAIGN_PARAMS = /^(utm_[a-z]+|ref|source)$/;

/**
 * The address of a page as it is reported to Plausible: without the fragment, and with only the
 * campaign parameters left in the query. Playground share links keep the whole schema in the
 * fragment and schema URLs in the query, and those are nobody's business but the user's.
 */
export function reportedUrl(href: string): string {
  const url = new URL(href);
  url.hash = "";
  for (const key of [...url.searchParams.keys()]) {
    if (!CAMPAIGN_PARAMS.test(key)) url.searchParams.delete(key);
  }
  return url.href;
}
