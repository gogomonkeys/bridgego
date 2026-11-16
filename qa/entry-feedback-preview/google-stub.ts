export * from "../../frontend/src/google-login";
export const loadGoogleIdentity = async () => {throw Error("Google disabled in synthetic preview");};
export const createLoginAttempt = (deps:any) => ({
  start: async () => deps.update("idle", "", true),
  cancel: () => deps.update("idle", ""),
  dispose: () => {},
});
