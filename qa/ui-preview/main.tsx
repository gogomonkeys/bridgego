import React from "react";
import {createRoot} from "react-dom/client";
import App from "../../frontend/src/App";
import "../../frontend/src/style.css";
import {fixtureControl} from "./client-stub";
createRoot(document.getElementById("root")!).render(<>{new URLSearchParams(location.search).has("controls")&&<aside aria-label="Synthetic fixture controls" style={{padding:4,background:"#eee"}}>{["other-turn","own-turn","offline","online","pending","clear-pending","illegal","paused","resumed","snapshot","next-phase","back-bidding","error"].map(action=><button key={action} onClick={()=>fixtureControl(action)}>{action}</button>)}</aside>}<App/></>);
