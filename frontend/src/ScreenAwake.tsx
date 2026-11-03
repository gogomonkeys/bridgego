import { useEffect, useRef, useState } from "react";
import { ScreenAwakeController, type AwakeStatus } from "./screen-awake";

const labels: Record<AwakeStatus, string> = {
  off: "未開啟",
  waiting: "已選擇，遊戲在前景時啟用",
  requesting: "正在請求保持亮屏…",
  active: "已取得亮屏請求",
  unavailable: "此裝置目前無法保持亮屏",
  released: "系統已停止保持亮屏，可關閉後重新開啟",
};
export default function ScreenAwake({
  activeGame,
  show,
}: {
  activeGame: boolean;
  show: boolean;
}) {
  const [enabled, setEnabled] = useState(false);
  const [status, setStatus] = useState<AwakeStatus>("off");
  const controller = useRef<ScreenAwakeController | null>(null);
  useEffect(() => {
    const wake = navigator.wakeLock;
    const current = new ScreenAwakeController(
      wake ? () => wake.request("screen") : undefined,
      setStatus,
    );
    controller.current = current;
    return () => {
      current.dispose();
      controller.current = null;
    };
  }, []);
  useEffect(() => {
    const update = () =>
      controller.current?.configure(
        enabled,
        activeGame,
        document.visibilityState === "visible",
      );
    update();
    document.addEventListener("visibilitychange", update);
    window.addEventListener("pageshow", update);
    return () => {
      document.removeEventListener("visibilitychange", update);
      window.removeEventListener("pageshow", update);
    };
  }, [enabled, activeGame]);
  if (!show) return null;
  return (
    <section className="panel screen-awake-settings" aria-label="遊戲螢幕設定">
      <label>
        <input
          type="checkbox"
          checked={enabled}
          onChange={(e) => setEnabled(e.target.checked)}
        />{" "}
        保持螢幕開啟
      </label>
      <p role="status">{labels[status]}</p>
      <small>
        只在本次開啟的遊戲中使用，可能增加耗電。手動鎖屏、切換 App
        或省電限制仍可能中斷連線；舊版 iOS 的主畫面 App 也可能無法生效。
      </small>
    </section>
  );
}
