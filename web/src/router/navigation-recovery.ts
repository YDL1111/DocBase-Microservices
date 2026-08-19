import type { RouteLocationNormalized } from "vue-router";

/**
 * Re-enter a route after dynamic routes have been registered.
 * Never reuse the old route name or matched records: before registration they
 * may point at the static NotFound route.
 */
export function freshRouteLocation(
  route: Pick<RouteLocationNormalized, "path" | "query" | "hash">
) {
  return {
    path: route.path,
    query: route.query,
    hash: route.hash,
    replace: true
  } as const;
}

/** Merge concurrent callers onto one bootstrap request. */
export function singleFlight<T>(task: () => Promise<T>): () => Promise<T> {
  let active: Promise<T> | null = null;

  return () => {
    if (active) return active;
    const current = task();
    active = current;
    const release = () => {
      if (active === current) active = null;
    };
    void current.then(release, release);
    return current;
  };
}

export function isDynamicImportError(error: unknown): boolean {
  const message = error instanceof Error ? error.message : String(error ?? "");
  return /(?:failed to fetch dynamically imported module|importing a module script failed|loading chunk \d+ failed|chunkloaderror)/i.test(
    message
  );
}
