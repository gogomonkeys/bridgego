import {useEffect} from "react";
export default function Tutorial({onDone,onAvailability}:{onDone:()=>void;onAvailability:(enabled:boolean)=>void}){useEffect(()=>{onAvailability(true);onDone();},[]);return null;}
