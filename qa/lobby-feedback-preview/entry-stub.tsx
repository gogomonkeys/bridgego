import {useEffect} from "react";
export default function Entry({onEnter}:{onEnter:(token:string)=>void}){useEffect(()=>onEnter("synthetic"),[onEnter]);return null;}
