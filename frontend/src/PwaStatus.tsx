import { useEffect, useState } from "react";
import { PwaUpdateController, updateMessage } from "./pwa-update";

interface InstallEvent extends Event {
  prompt(): Promise<void>;
  userChoice: Promise<{ outcome: string }>;
}

/** Updates activate naturally only when every old BridgeGo window is closed. */
export default function PwaStatus({
  help = false,
  activeGame = false,
}: {
  help?: boolean;
  activeGame?: boolean;
}) {
  const [offline, setOffline] = useState(
    () => typeof navigator !== "undefined" && navigator.onLine === false,
  );
  const [install, setInstall] = useState<InstallEvent | null>(null);
  const [waiting, setWaiting] = useState(false);
  const [updater, setUpdater] = useState<PwaUpdateController | null>(null);
  const [message, setMessage] = useState("");
  useEffect(() => {
    const onOffline = () => setOffline(true);
    const onOnline = () => setOffline(false);
    const onInstall = (event: Event) => {
      event.preventDefault();
      setInstall(event as InstallEvent);
    };
    const installed = () => setInstall(null);
    window.addEventListener("offline", onOffline);
    window.addEventListener("online", onOnline);
    window.addEventListener("beforeinstallprompt", onInstall);
    window.addEventListener("appinstalled", installed);
    let disposed = false;
    let controller: PwaUpdateController | null = null;
    if (import.meta.env.PROD && "serviceWorker" in navigator) {
      void navigator.serviceWorker
        .register("/sw.js", { updateViaCache: "none" })
        .then((reg) => {
          if (disposed) return;
          controller = new PwaUpdateController(reg, (status) => {
            if (disposed) return;
            setWaiting(!!reg.waiting);
            setMessage(updateMessage[status]);
          });
          setUpdater(controller);
        })
        .catch(() => {
          if (!disposed)
            setMessage("離線啟動畫面尚未準備好；連線時仍可正常遊戲。");
        });
    }
    return () => {
      disposed = true;
      controller?.dispose();
      window.removeEventListener("offline", onOffline);
      window.removeEventListener("online", onOnline);
      window.removeEventListener("beforeinstallprompt", onInstall);
      window.removeEventListener("appinstalled", installed);
    };
  }, []);
  return (
    <>
      {offline && !activeGame && (
        <p className="notice" role="status">
          目前離線。多人遊戲需要網路；畫面不是即時牌局，恢復連線後將確認席位。
        </p>
      )}
      {waiting && !activeGame && (
        <p className="notice" role="status">
          新版本已準備好。請先完成遊戲，再關閉所有 BridgeGo
          分頁與應用程式後重新開啟；不會在牌局中自動更新。
        </p>
      )}
      {help && (
        <section className="pwa-help">
          <h3>加入主畫面</h3>
          <p>
            安裝後可從主畫面開啟，遊戲仍需要網路。免費測試服務可能休眠或重啟；重啟後原牌局無法延續，請重新加入。
          </p>
          {install ? (
            <button
              onClick={() => {
                void install
                  .prompt()
                  .then(() => install.userChoice)
                  .catch(() =>
                    setMessage(
                      "目前無法開啟安裝，請使用瀏覽器選單或繼續網頁遊玩。",
                    ),
                  )
                  .finally(() => setInstall(null));
              }}
            >
              安裝 BridgeGo
            </button>
          ) : (
            <p>
              iPhone／iPad：在 Safari
              分享選單選擇「加入主畫面」。Android、Chrome 或
              Edge：若瀏覽器選單提供「安裝應用程式」或「加入主畫面」即可使用；未提供時可直接用網頁遊玩。
            </p>
          )}
          {updater && (
            <button
              onClick={() => {
                void updater.check();
              }}
            >
              檢查更新
            </button>
          )}
          {message && <p role="status">{message}</p>}
        </section>
      )}
    </>
  );
}
