import { createApp } from "vue";
import App from "./App.vue";
import router from "./router";
import ElementPlus from "element-plus";
import { setupStore } from "./store";
import { setupPermissionDirective } from "./directive/permission";
import "element-plus/dist/index.css";
import "./styles/index.css";

const app = createApp(App);

setupStore(app);
setupPermissionDirective(app);
app.use(ElementPlus);
app.use(router);

app.mount("#app");
