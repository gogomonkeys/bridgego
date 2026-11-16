/** Real App rendering with a local-only session/API fixture. Never production data. */
import React from "react";
import {createRoot} from "react-dom/client";
import App from "../../frontend/src/App";
import "../../frontend/src/style.css";
createRoot(document.getElementById("root")!).render(<App/>);
