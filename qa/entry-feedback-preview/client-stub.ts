const session = {token:"synthetic-preview-token",playerId:"synthetic-preview-player",displayName:"玩家"};
export const getClientState = () => ({session,room:null});
export const client = {
 login: async (displayName:string) => {session.displayName=displayName;return session;},
 adoptNickname: (token:string,displayName:string) => {if(token!==session.token)return false;session.displayName=displayName;return true;},
};
