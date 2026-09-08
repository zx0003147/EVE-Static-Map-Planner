export async function registerPwa({ onUpdateReady = () => {}, onError = () => {} } = {}) {
  if (!("serviceWorker" in navigator)) {
    onError(new Error("Service workers are unavailable in this browser."));
    return null;
  }

  let reloadOnControllerChange = false;
  navigator.serviceWorker.addEventListener("controllerchange", () => {
    if (reloadOnControllerChange) location.reload();
  });

  try {
    const registration = await navigator.serviceWorker.register("./service-worker.js", { scope: "./" });
    const offerUpdate = (worker) => onUpdateReady({
      registration,
      applyUpdate() {
        reloadOnControllerChange = true;
        worker.postMessage({ type: "SKIP_WAITING" });
      },
    });

    if (registration.waiting && navigator.serviceWorker.controller) offerUpdate(registration.waiting);
    registration.addEventListener("updatefound", () => {
      const installing = registration.installing;
      if (!installing) return;
      installing.addEventListener("statechange", () => {
        if (installing.state === "installed" && navigator.serviceWorker.controller) offerUpdate(installing);
      });
    });
    return registration;
  } catch (error) {
    onError(error instanceof Error ? error : new Error(String(error)));
    return null;
  }
}

export async function cacheWebPackForOffline(manifest) {
  if (!("serviceWorker" in navigator) || !manifest?.fileName) return false;
  const registration = await navigator.serviceWorker.ready;
  const worker = registration.active;
  if (!worker) return false;
  worker.postMessage({
    type: "CACHE_WEB_PACK",
    manifest,
    manifestUrl: new URL("./data/manifest.json", location.href).href,
    packUrl: new URL(`./data/${manifest.fileName}`, location.href).href,
  });
  return true;
}
