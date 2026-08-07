import NProgress from "nprogress";
import "nprogress/nprogress.css";

NProgress.configure({
  easing: "ease",
  speed: 500,
  showSpinner: false,
  trickleSpeed: 200
});

export function start(): void {
  NProgress.start();
}

export function done(): void {
  NProgress.done();
}

export default { start, done };
